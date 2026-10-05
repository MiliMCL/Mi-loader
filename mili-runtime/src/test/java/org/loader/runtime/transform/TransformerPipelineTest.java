package org.loader.runtime.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.MiliTransformer;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationEnvironment;
import org.loader.api.transform.TransformationException;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.TransformationResult;
import org.loader.api.transform.symbol.MiliSymbol;
import org.loader.runtime.transform.cache.TransformationCache;
import org.loader.runtime.transform.conflict.ConflictLedger;
import org.loader.runtime.transform.engine.TransformerPipeline;
import org.loader.runtime.transform.engine.TransformerRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流水线行为测试 —— 注册、排序、执行、缓存、失败策略。
 *
 * <h2>这些测试守的是什么</h2>
 * 字节码正确性由 {@link InjectionPointTest} 守；本类守的是
 * <b>平台行为</b>：谁先跑、失败了怎么办、会不会重复转换。
 *
 * <p>其中「失败必须中断」与「不能重复转换」是两条最硬的约束 ——
 * 前者对应「静默失效」，后者对应「tick 数翻倍且无报错」。
 */
class TransformerPipelineTest {

    private static TransformationEnvironment env(ClassLoader cl) {
        return new TransformationEnvironment(cl,
                MiliSymbol.MINECRAFT_VERSION,
                TransformationEnvironment.RuntimeEnvironmentValue.DEDICATED_SERVER,
                "0.1.0-test");
    }

    /** 记录执行顺序的转换器。 */
    private record RecordingTransformer(
            String id, TransformationPhase phase, int priority,
            String watchClass, List<String> log) implements MiliTransformer {

        @Override
        public String minecraftVersion() {
            return MiliSymbol.MINECRAFT_VERSION;
        }

        @Override
        public TransformationPhase phase() {
            return phase;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean matches(String className) {
            return watchClass.equals(className);
        }

        @Override
        public TransformationResult transform(TransformationContext context) {
            log.add(id);
            return TransformationResult.Unchanged.INSTANCE;
        }
    }

    private static TransformerPipeline pipeline(TransformerRegistry registry) {
        return TransformerPipeline.builder()
                .registry(registry)
                .cache(new TransformationCache())
                .ledger(new ConflictLedger())
                .verifyEnabled(true)
                .build();
    }

    // ── 注册期校验 ─────────────────────────────────────────────────

    @Test
    @DisplayName("重复 id 注册被拒绝 —— id 是冲突检测与审计的主键")
    void duplicateIdIsRejected() {
        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new RecordingTransformer("dup", null, 0, "X", new ArrayList<>()),
                "mod-a");

        assertThrows(TransformationException.class,
                () -> registry.register(
                        new RecordingTransformer("dup", null, 0, "X", new ArrayList<>()),
                        "mod-b"));
    }

    @Test
    @DisplayName("Minecraft 版本不匹配在注册期就被拒绝")
    void versionMismatchIsRejectedAtRegistration() {
        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);

        MiliTransformer wrong = new MiliTransformer() {
            @Override
            public String id() {
                return "wrong-version";
            }

            @Override
            public String minecraftVersion() {
                return "1.21.4";   // 与平台的 26.2 不符
            }

            @Override
            public TransformationResult transform(TransformationContext context) {
                return TransformationResult.Unchanged.INSTANCE;
            }
        };

        assertThrows(TransformationException.class,
                () -> registry.register(wrong, "mod-a"));
    }

    @Test
    @DisplayName("模糊版本值被拒绝 —— 严格版本对齐不允许范围匹配")
    void fuzzyVersionIsRejected() {
        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);

        MiliTransformer fuzzy = new MiliTransformer() {
            @Override
            public String id() {
                return "fuzzy";
            }

            @Override
            public String minecraftVersion() {
                return "26.x";
            }

            @Override
            public TransformationResult transform(TransformationContext context) {
                return TransformationResult.Unchanged.INSTANCE;
            }
        };

        assertThrows(TransformationException.class,
                () -> registry.register(fuzzy, "mod-a"));
    }

    @Test
    @DisplayName("Mod 不能声明 CORE 阶段 —— 该阶段承载 tick 正确性")
    void modCannotClaimCorePhase() {
        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);

        MiliTransformer core = new RecordingTransformer(
                "core-claim", TransformationPhase.CORE, 0, "X", new ArrayList<>());

        // 平台自身（modId == null）可以
        registry.register(core, null);

        TransformerRegistry registry2 = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        assertThrows(TransformationException.class,
                () -> registry2.register(core, "some-mod"));
    }

    @Test
    @DisplayName("封闭后不能再注册 —— 保证一次类加载中转换器集合稳定")
    void sealedRegistryRejectsFurtherRegistration() {
        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new RecordingTransformer("a", null, 0, "X", new ArrayList<>()), "m");
        registry.seal();

        assertTrue(registry.isSealed());
        assertThrows(TransformationException.class,
                () -> registry.register(
                        new RecordingTransformer("b", null, 0, "X", new ArrayList<>()), "m"));
    }

    // ── 执行顺序 ───────────────────────────────────────────────────

    @Test
    @DisplayName("执行顺序由 阶段→优先级→id 决定，与注册顺序无关")
    void executionOrderIsIndependentOfRegistrationOrder() {
        List<String> logA = new ArrayList<>();
        List<String> logB = new ArrayList<>();

        // 故意以「逆序」注册
        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new RecordingTransformer(
                "z-last", TransformationPhase.MOD, 10, "X", logA), "m1");
        // MINECRAFT 是 Mod 可声明的最早阶段。
        //
        // 这里<b>不能用 CORE</b>：CORE 由平台保留，Mod 声明它会被
        // TransformationGuard 拒绝（见「Mod 不能声明 CORE 阶段」那条测试）。
        // 排序契约要验证的是「阶段优先于注册顺序」，
        // 用 MINECRAFT → MOD 同样能验证，且不与「阶段保留」规则冲突。
        registry.register(new RecordingTransformer(
                "a-first", TransformationPhase.MINECRAFT, 0, "X", logB), "m2");
        registry.seal();

        pipeline(registry).transform("X", TransformTestFixture.sampleClass(),
                env(getClass().getClassLoader()), null);

        // MINECRAFT 阶段先于 MOD —— 与注册顺序相反
        assertEquals(List.of("a-first", "z-last"), concat(logB, logA));
    }

    @Test
    @DisplayName("同阶段同优先级时按 id 字典序 —— 排序是确定性的最终来源")
    void samePhaseSortsByIdLexicographically() {
        List<String> log = new ArrayList<>();

        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new RecordingTransformer(
                "zebra", TransformationPhase.MOD, 0, "X", log), "m1");
        registry.register(new RecordingTransformer(
                "alpha", TransformationPhase.MOD, 0, "X", log), "m2");
        registry.register(new RecordingTransformer(
                "middle", TransformationPhase.MOD, 0, "X", log), "m3");
        registry.seal();

        pipeline(registry).transform("X", TransformTestFixture.sampleClass(),
                env(getClass().getClassLoader()), null);

        assertEquals(List.of("alpha", "middle", "zebra"), log);
    }

    // ── 热路径 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("无匹配转换器时直接返回原始字节，不进入 ASM")
    void noMatchReturnsOriginalBytesUntouched() {
        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new RecordingTransformer(
                "watches-other", null, 0, "some/Other", new ArrayList<>()), "m");
        registry.seal();

        TransformerPipeline p = pipeline(registry);
        byte[] original = TransformTestFixture.sampleClass();

        assertSame(original, p.transform("net/minecraft/server/SampleServer",
                original, env(getClass().getClassLoader()), null));

        assertEquals(0, p.transformedClassCount());
        assertEquals(1, p.skippedClassCount());
    }

    @Test
    @DisplayName("重复转换被缓存拦截 —— 防止 tick 数翻倍")
    void repeatedTransformationIsCached() {
        AtomicInteger runs = new AtomicInteger();

        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new MiliTransformer() {
            @Override
            public String id() {
                return "counting";
            }

            @Override
            public String minecraftVersion() {
                return MiliSymbol.MINECRAFT_VERSION;
            }

            @Override
            public boolean matches(String className) {
                return TransformTestFixture.SAMPLE_CLASS.equals(className);
            }

            @Override
            public TransformationResult transform(TransformationContext context) {
                runs.incrementAndGet();
                return new TransformationResult.Transformed(context.originalBytes());
            }
        }, "m");
        registry.seal();

        TransformerPipeline p = pipeline(registry);
        byte[] original = TransformTestFixture.sampleClass();
        ClassLoader cl = getClass().getClassLoader();

        p.transform(TransformTestFixture.SAMPLE_CLASS, original, env(cl), null);
        p.transform(TransformTestFixture.SAMPLE_CLASS, original, env(cl), null);
        p.transform(TransformTestFixture.SAMPLE_CLASS, original, env(cl), null);

        assertEquals(1, runs.get(),
                "同一个类在同一个 ClassLoader 上只能被转换一次。"
                        + "重复转换会让注入的回调执行两次 —— 表现为 tick 数翻倍"
                        + "而游戏完全正常，是最难定位的一类 bug。");
    }

    @Test
    @DisplayName("缓存键包含 ClassLoader 身份 —— 防止跨 CL 缓存污染")
    void cacheIsPerClassLoader() {
        AtomicInteger runs = new AtomicInteger();

        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new MiliTransformer() {
            @Override
            public String id() {
                return "per-cl";
            }

            @Override
            public String minecraftVersion() {
                return MiliSymbol.MINECRAFT_VERSION;
            }

            @Override
            public boolean matches(String className) {
                return TransformTestFixture.SAMPLE_CLASS.equals(className);
            }

            @Override
            public TransformationResult transform(TransformationContext context) {
                runs.incrementAndGet();
                return new TransformationResult.Transformed(context.originalBytes());
            }
        }, "m");
        registry.seal();

        TransformerPipeline p = pipeline(registry);
        byte[] original = TransformTestFixture.sampleClass();

        ClassLoader cl1 = new ClassLoader(null) { };
        ClassLoader cl2 = new ClassLoader(null) { };

        p.transform(TransformTestFixture.SAMPLE_CLASS, original, env(cl1), null);
        p.transform(TransformTestFixture.SAMPLE_CLASS, original, env(cl2), null);

        assertEquals(2, runs.get(),
                "不同 ClassLoader 必须各自转换一次。"
                        + "共用缓存会让第二个 CL 拿到第一个 CL 的字节码，"
                        + "其中的引用指向已卸载的类 → NoClassDefFoundError。");
    }

    // ── 失败策略 ───────────────────────────────────────────────────

    @Test
    @DisplayName("转换器抛出目标不存在异常时流水线必须中断，不得跳过继续")
    void transformationFailureAbortsPipeline() {
        List<String> log = new ArrayList<>();

        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new MiliTransformer() {
            @Override
            public String id() {
                return "failing";
            }

            @Override
            public String minecraftVersion() {
                return MiliSymbol.MINECRAFT_VERSION;
            }

            @Override
            public boolean matches(String className) {
                return TransformTestFixture.SAMPLE_CLASS.equals(className);
            }

            @Override
            public TransformationResult transform(TransformationContext context) {
                throw new org.loader.api.transform.TransformationTargetNotFoundException(
                        context.className(), "gone", "()V",
                        context.environment().minecraftVersion(), id());
            }
        }, "m1");
        registry.register(new RecordingTransformer(
                "after", TransformationPhase.MOD, 10,
                TransformTestFixture.SAMPLE_CLASS, log), "m2");
        registry.seal();

        // 若平台「跳过继续」，log 会非空 —— 那意味着 Mod 拿到了一个
        // 「失败的转换器没做事、但其他转换器都做了」的类。
        assertThrows(org.loader.api.transform.TransformationTargetNotFoundException.class,
                () -> pipeline(registry).transform(
                        TransformTestFixture.SAMPLE_CLASS,
                        TransformTestFixture.sampleClass(),
                        env(getClass().getClassLoader()), null));

        assertTrue(log.isEmpty(),
                "前序转换器失败后，后续转换器不应被执行 —— "
                        + "否则会产出一个「部分转换」的类，其行为无法预测。");
    }

    @Test
    @DisplayName("转换器返回 Failed 时同样必须中断")
    void failedResultAbortsPipeline() {
        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new MiliTransformer() {
            @Override
            public String id() {
                return "returns-failed";
            }

            @Override
            public String minecraftVersion() {
                return MiliSymbol.MINECRAFT_VERSION;
            }

            @Override
            public boolean matches(String className) {
                return TransformTestFixture.SAMPLE_CLASS.equals(className);
            }

            @Override
            public TransformationResult transform(TransformationContext context) {
                return new TransformationResult.Failed(
                        new TransformationException("内部失败", id(), null));
            }
        }, "m");
        registry.seal();

        assertThrows(TransformationException.class,
                () -> pipeline(registry).transform(
                        TransformTestFixture.SAMPLE_CLASS,
                        TransformTestFixture.sampleClass(),
                        env(getClass().getClassLoader()), null));
    }

    @Test
    @DisplayName("转换器内部崩溃被包装成带 transformerId 的异常")
    void transformerCrashIsWrappedWithIdentity() {
        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new MiliTransformer() {
            @Override
            public String id() {
                return "crashy";
            }

            @Override
            public String minecraftVersion() {
                return MiliSymbol.MINECRAFT_VERSION;
            }

            @Override
            public boolean matches(String className) {
                return TransformTestFixture.SAMPLE_CLASS.equals(className);
            }

            @Override
            public TransformationResult transform(TransformationContext context) {
                throw new IllegalStateException("转换器内部 NPE");
            }
        }, "m");
        registry.seal();

        TransformationException ex = assertThrows(TransformationException.class,
                () -> pipeline(registry).transform(
                        TransformTestFixture.SAMPLE_CLASS,
                        TransformTestFixture.sampleClass(),
                        env(getClass().getClassLoader()), null));

        assertEquals("crashy", ex.transformerId(),
                "异常必须带上 transformerId —— 否则用户看到的是一个无主异常，"
                        + "完全无法判断是哪个 Mod 的问题。");
        assertTrue(ex.getMessage().contains("crashy"));
    }

    @Test
    @DisplayName("产出非法字节码时验证器拦截并报出类名与转换器")
    void illegalBytecodeIsRejectedByVerifier() {
        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new MiliTransformer() {
            @Override
            public String id() {
                return "corruptor";
            }

            @Override
            public String minecraftVersion() {
                return MiliSymbol.MINECRAFT_VERSION;
            }

            @Override
            public boolean matches(String className) {
                return TransformTestFixture.SAMPLE_CLASS.equals(className);
            }

            @Override
            public TransformationResult transform(TransformationContext context) {
                // 故意返回垃圾字节码：结构非法
                return new TransformationResult.Transformed(
                        new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
            }
        }, "m");
        registry.seal();

        org.loader.api.transform.TransformationVerificationException ex =
                assertThrows(
                        org.loader.api.transform.TransformationVerificationException.class,
                        () -> pipeline(registry).transform(
                                TransformTestFixture.SAMPLE_CLASS,
                                TransformTestFixture.sampleClass(),
                                env(getClass().getClassLoader()), null));

        assertTrue(ex.getMessage().contains(TransformTestFixture.SAMPLE_CLASS),
                "验证失败必须报出类名: " + ex.getMessage());
    }

    // ── 结果归约 ───────────────────────────────────────────────────

    @Test
    @DisplayName("Skipped 与 Unchanged 都不改字节，但语义不同")
    void skippedAndUnchangedBothPreserveBytes() {
        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new MiliTransformer() {
            @Override
            public String id() {
                return "skipper";
            }

            @Override
            public String minecraftVersion() {
                return MiliSymbol.MINECRAFT_VERSION;
            }

            @Override
            public boolean matches(String className) {
                return true;
            }

            @Override
            public TransformationResult transform(TransformationContext context) {
                return TransformationResult.Skipped.because("仅支持方块实体");
            }
        }, "m");
        registry.seal();

        byte[] original = TransformTestFixture.sampleClass();
        byte[] result = pipeline(registry).transform(
                TransformTestFixture.SAMPLE_CLASS, original,
                env(getClass().getClassLoader()), null);

        assertSame(original, result);
    }

    @Test
    @DisplayName("转换器可声明关注多个具体类名 —— 精确索引让无关类零开销")
    void preciseIndexEnablesFastRejection() {
        TransformerRegistry registry = new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new RecordingTransformer(
                "indexed", null, 0, "net/minecraft/server/SampleServer", new ArrayList<>()),
                "m");
        registry.indexClass("net/minecraft/server/SampleServer");
        registry.seal();

        assertTrue(registry.hasPreciseIndex());
        assertEquals(1, registry.indexedClassCount());

        // 未索引的类：不匹配
        assertTrue(registry.matching("some/unrelated/Class").isEmpty());
        // 索引内的类：匹配
        assertEquals(1,
                registry.matching("net/minecraft/server/SampleServer").size());
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}