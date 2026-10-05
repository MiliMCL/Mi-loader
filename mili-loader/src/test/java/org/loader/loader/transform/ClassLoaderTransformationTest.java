package org.loader.loader.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.MiliTransformer;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationEnvironment;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.TransformationResult;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.transform.engine.TransformerPipeline;
import org.loader.runtime.transform.engine.TransformerRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClassLoader 转换集成测试。
 *
 * <h2>这个测试在守什么</h2>
 * {@link ClassTransformInterceptor} 是转换系统接入类加载路径的<b>唯一</b>入口。
 * 前面所有的测试都是直接调用流水线 —— 它们证明「转换逻辑正确」，
 * 但完全不证明「转换真的会在类被加载时发生」。
 *
 * <p>这个区别至关重要。本仓库审计发现过完全相同的失效形态：
 * {@code TickBridge.beginTick()} 在生产路径上<b>零调用者</b> ——
 * 类存在、测试通过、方法被正确实现，但没有任何东西调用它。
 * 表现是「Mod 的 tick 回调从不执行」，日志里什么也没有。
 *
 * <p>一个只有「直接调用能工作」的转换系统，就是同一个 bug 的新变体。
 */
@DisplayName("ClassLoader 转换集成")
class ClassLoaderTransformationTest {

    private static final String TARGET_DOTTED = "net.minecraft.server.MinecraftServer";
    private static final String MARKER_OWNER = "platform/Probe";
    private static final String MARKER_NAME = "onProbe";

    /**
     * 注入一个可识别的静态调用作为标记。
     *
     * <p>用「字节码里有没有这个调用」来断言转换发生 ——
     * 比断言「字节码长度变了」精确，且不受帧重算的干扰。
     */
    private static MiliTransformer probeTransformer() {
        return new MiliTransformer() {
            @Override
            public String id() {
                return "test-probe";
            }

            @Override
            public String minecraftVersion() {
                return "26.2";
            }

            @Override
            public TransformationPhase phase() {
                return TransformationPhase.CORE;
            }

            @Override
            public boolean matches(String className) {
                return TARGET_DOTTED.replace('.', '/').equals(className);
            }

            @Override
            public TransformationResult transform(TransformationContext context) {
                return new TransformationResult.Transformed(
                        injectProbeCall(context.originalBytes(),
                                context.className()));
            }
        };
    }

    private static byte[] injectProbeCall(byte[] original, String className) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (mv == null || !"tickServer".equals(name)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                MARKER_OWNER, MARKER_NAME, "()V", false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static boolean containsProbe(byte[] bytes) {
        final boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d,
                                             String s, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String o, String mn,
                                                String md, boolean itf) {
                        if (MARKER_OWNER.equals(o) && MARKER_NAME.equals(mn)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return found[0];
    }

    /** 生成一个含 tickServer 的样例类。 */
    private static byte[] sampleServerClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "net/minecraft/server/MinecraftServer", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC,
                "tickServer", "(Ljava/util/function/BooleanSupplier;)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static TransformerPipeline pipelineWith(MiliTransformer... transformers) {
        TransformerRegistry registry = new TransformerRegistry("26.2");
        for (MiliTransformer t : transformers) {
            registry.register(t, null);
        }
        registry.seal();
        return TransformerPipeline.builder().registry(registry).build();
    }

    private static TransformationEnvironment env() {
        return new TransformationEnvironment(
                ClassLoaderTransformationTest.class.getClassLoader(),
                "26.2",
                TransformationEnvironment.RuntimeEnvironmentValue.DEDICATED_SERVER,
                "0.1.0-test");
    }

    // ── 基本接入 ────────────────────────────────────────────────────

    @Test
    @DisplayName("transformFromStream 真的注入了 —— 类加载路径确实接通了")
    void streamEntryPointActuallyTransforms() throws IOException {
        Scope scope = new Scope("cl-test", null);
        try {
            ClassTransformInterceptor interceptor = new ClassTransformInterceptor(
                    pipelineWith(probeTransformer()), env(), scope);

            byte[] original = sampleServerClass();
            byte[] result = interceptor.transformFromStream(
                    TARGET_DOTTED, getClass().getClassLoader(),
                    new ByteArrayInputStream(original));

            assertTrue(containsProbe(result),
                    "这是本测试最重要的一条断言：转换必须真的发生在类加载路径上。\n"
                            + "一个「直接调用能工作、但类加载时不生效」的转换系统，"
                            + "与 TickBridge 零调用者是同一个 bug。");
            assertEquals(1, interceptor.interceptedClassCount());
        } finally {
            scope.close();
        }
    }

    @Test
    @DisplayName("不匹配的类原样返回同一个数组实例")
    void unmatchedClassPassesThrough() {
        Scope scope = new Scope("cl-test", null);
        try {
            ClassTransformInterceptor interceptor = new ClassTransformInterceptor(
                    pipelineWith(probeTransformer()), env(), scope);

            byte[] original = sampleServerClass();
            byte[] result = interceptor.transform(
                    "com/example/Unrelated", getClass().getClassLoader(), original);

            assertSame(original, result,
                    "不匹配的类必须原样返回同一实例 —— "
                            + "拷贝字节码本身就是不必要的开销。"
                            + "Minecraft 约一万个类，绝大多数走这条路径。");
        } finally {
            scope.close();
        }
    }

    // ── 平台自有类不被转换 ──────────────────────────────────────────

    @Test
    @DisplayName("平台自身的类一律不转换")
    void platformClassesAreNeverTransformed() {
        Scope scope = new Scope("cl-test", null);
        try {
            AtomicInteger runs = new AtomicInteger();
            MiliTransformer alwaysMatching = new MiliTransformer() {
                @Override
                public String id() {
                    return "greedy";
                }

                @Override
                public String minecraftVersion() {
                    return "26.2";
                }

                @Override
                public boolean matches(String className) {
                    return true;         // 贪婪匹配一切
                }

                @Override
                public TransformationResult transform(TransformationContext ctx) {
                    runs.incrementAndGet();
                    return new TransformationResult.Transformed(ctx.originalBytes());
                }
            };

            ClassTransformInterceptor interceptor = new ClassTransformInterceptor(
                    pipelineWith(alwaysMatching), env(), scope);

            byte[] original = sampleServerClass();
            byte[] result = interceptor.transform(
                    "org.loader.loader.SomePlatformClass",
                    getClass().getClassLoader(), original);

            assertSame(original, result,
                    "平台类必须被短路 —— 否则平台行为会依赖装了哪些 Mod，"
                            + "且转换平台类会引入对 Mod 类的引用，形成 ClassLoader 泄漏。");
            assertEquals(0, runs.get(),
                    "贪婪转换器不应在平台类上被调用哪怕一次");
        } finally {
            scope.close();
        }
    }

    // ── 缓存与重复转换 ──────────────────────────────────────────────

    @Test
    @DisplayName("同一 ClassLoader 重复加载只转换一次")
    void repeatedLoadConvertsOnce() {
        Scope scope = new Scope("cl-test", null);
        try {
            AtomicInteger runs = new AtomicInteger();
            MiliTransformer counting = new MiliTransformer() {
                @Override
                public String id() {
                    return "counting";
                }

                @Override
                public String minecraftVersion() {
                    return "26.2";
                }

                @Override
                public boolean matches(String className) {
                    return TARGET_DOTTED.replace('.', '/').equals(className);
                }

                @Override
                public TransformationResult transform(TransformationContext ctx) {
                    runs.incrementAndGet();
                    return new TransformationResult.Transformed(
                            injectProbeCall(ctx.originalBytes(), ctx.className()));
                }
            };

            TransformerPipeline pipeline = pipelineWith(counting);
            ClassTransformInterceptor interceptor =
                    new ClassTransformInterceptor(pipeline, env(), scope);

            ClassLoader loader = getClass().getClassLoader();
            byte[] original = sampleServerClass();

            byte[] first = interceptor.transform(TARGET_DOTTED, loader, original);
            byte[] second = interceptor.transform(TARGET_DOTTED, loader, original);

            assertArrayEquals(first, second, "两次转换结果必须一致");
            assertEquals(1, runs.get(),
                    "重复转换的表现是「tick 数翻倍但游戏完全正常」，"
                            + "比不注入更难查 —— TransformationCache 必须拦住它。");
        } finally {
            scope.close();
        }
    }

    @Test
    @DisplayName("不同 ClassLoader 分别转换 —— 缓存不得跨 CL 污染")
    void differentClassLoadersConvertSeparately() {
        Scope scope = new Scope("cl-test", null);
        try {
            TransformerPipeline pipeline = pipelineWith(probeTransformer());
            ClassTransformInterceptor interceptor =
                    new ClassTransformInterceptor(pipeline, env(), scope);

            byte[] original = sampleServerClass();
            ClassLoader loaderA = new ClassLoader() {
            };
            ClassLoader loaderB = new ClassLoader() {
            };

            interceptor.transform(TARGET_DOTTED, loaderA, original);
            interceptor.transform(TARGET_DOTTED, loaderB, original);

            // 两个 CL 各自转换一次 —— 若缓存键不含 CL 身份，
            // 第二个会命中第一个的缓存，导致「用A的字节码定义B的类」
            assertEquals(1, pipeline.cache().size(),
                    "每个 ClassLoader 应各有一条缓存记录。"
                            + "缓存键若只用类名，同名类在不同 CL 下会互相污染。");
        } finally {
            scope.close();
        }
    }

    // ── 失败路径 ────────────────────────────────────────────────────

    @Test
    @DisplayName("转换失败时异常向上传播 —— 绝不返回未转换的字节码")
    void transformationFailurePropagates() {
        Scope scope = new Scope("cl-test", null);
        try {
            MiliTransformer failing = new MiliTransformer() {
                @Override
                public String id() {
                    return "failing";
                }

                @Override
                public String minecraftVersion() {
                    return "26.2";
                }

                @Override
                public boolean matches(String className) {
                    return true;
                }

                @Override
                public TransformationResult transform(TransformationContext ctx) {
                    throw new org.loader.api.transform.TransformationTargetNotFoundException(
                            ctx.className(), "missing", "()V", "26.2", id());
                }
            };

            ClassTransformInterceptor interceptor = new ClassTransformInterceptor(
                    pipelineWith(failing), env(), scope);

            assertThrows(
                    org.loader.api.transform.TransformationTargetNotFoundException.class,
                    () -> interceptor.transform(TARGET_DOTTED,
                            getClass().getClassLoader(), sampleServerClass()),
                    "转换失败必须中断类加载。若降级为「用未转换的字节码继续」，"
                            + "用户看到的是「Mod 加载成功但功能不生效」。");
        } finally {
            scope.close();
        }
    }

    @Test
    @DisplayName("流为空时抛 IOException 而不是静默返回")
    void emptyStreamFailsLoudly() {
        Scope scope = new Scope("cl-test", null);
        try {
            ClassTransformInterceptor interceptor = new ClassTransformInterceptor(
                    pipelineWith(probeTransformer()), env(), scope);

            InputStream empty = new ByteArrayInputStream(new byte[0]);
            assertThrows(IOException.class,
                    () -> interceptor.transformFromStream(
                            TARGET_DOTTED, getClass().getClassLoader(), empty));
            assertEquals(1, interceptor.readFailureCount());
        } finally {
            scope.close();
        }
    }

    @Test
    @DisplayName("构造器缺参数立即失败")
    void constructorRejectsNulls() {
        assertThrows(IllegalStateException.class,
                () -> new ClassTransformInterceptor(null, env(), null));
        assertThrows(IllegalStateException.class,
                () -> new ClassTransformInterceptor(
                        pipelineWith(probeTransformer()), null, null));
    }

    @Test
    @DisplayName("类名点分与斜杠两种形式都被正确处理")
    void handlesBothNameForms() {
        Scope scope = new Scope("cl-test", null);
        try {
            TransformerPipeline pipeline = pipelineWith(probeTransformer());
            ClassTransformInterceptor interceptor =
                    new ClassTransformInterceptor(pipeline, env(), scope);
            byte[] original = sampleServerClass();

            interceptor.transform("net.minecraft.server.MinecraftServer",
                    getClass().getClassLoader(), original);
            // 第二次用斜杠形式 —— 同一个类，不应重复转换
            interceptor.transform("net/minecraft/server/MinecraftServer",
                    getClass().getClassLoader(), original);

            assertEquals(1, pipeline.transformedClassCount(),
                    "两种名称写法指向同一个类，不应各转换一次 —— "
                            + "重复转换会让注入执行两次。");
        } finally {
            scope.close();
        }
    }
}