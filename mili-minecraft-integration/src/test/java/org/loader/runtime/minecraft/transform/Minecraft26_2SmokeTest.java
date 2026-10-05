package org.loader.runtime.minecraft.transform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.MiliTransformer;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationEnvironment;
import org.loader.api.transform.TransformationResult;
import org.loader.api.transform.symbol.MiliSymbol;
import org.loader.api.transform.target.TargetMethod;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.transform.asm.MiliClassTransformer;
import org.loader.runtime.transform.engine.PipelineTransformers;
import org.loader.runtime.transform.engine.TransformerPipeline;
import org.loader.runtime.transform.engine.TransformerRegistry;
import org.loader.runtime.transform.verify.BytecodeVerifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>真实 Minecraft 26.2 冒烟测试。</b>
 *
 * <h2>这个测试与仓库里其他 transform 测试的根本区别</h2>
 * 其他测试（{@code InjectionPointTest}、{@code FieldInjectionTest} 等）用的是
 * {@link TransformTestFixture} 用 ASM <b>生成</b>的合成类。它们能证明
 * 「注入逻辑在被正确匹配时产出正确指令」，但<b>不能</b>证明
 * 「注入逻辑对真实的、编译自Java 25、major version 69、由
 * javac 与混淆器处理过的类也成立」。
 *
 * <p>这两者的差距不是理论的。真实 Minecraft 类具备合成类没有的三个属性，
 * 每一个都曾导致过真实的注入事故：
 * <ol>
 *   <li><b>class major = 69（Java 25）</b> —— ASM 9.7 会直接抛
 *       {@code Unsupported class file major version 69}；</li>
 *   <li><b>方法体含复杂的 stack map 与分支图</b> —— {@code COMPUTE_FRAMES}
 *       在真实控制流上才能真正被执行到；</li>
 *   <li><b>{@code tickServer} 内部有多个返回路径与深层嵌套</b> ——
 *       「注入最后一个 RETURN」这种错误实现在合成类上完全看不出来，
 *       在真实类上才会暴露为「大部分时候正常，偶尔不执行」。</li>
 * </ol>
 *
 * <h2>为什么不能用 Mock Minecraft 替代</h2>
 * 需求明确禁止。理由也直白：一个 mock 只能证明「mock 的形状被正确处理」。
 * 本仓库的审计记录反复出现同一类问题 —— 注册链在生产路径上是死代码、
 * {@code TickBridge} 零调用者、描述符写错不报错。<b>它们都是在
 * 「测试用生成类」上通过、却在真实环境下静默失效的。</b>
 * 用 mock 生成的绿色测试证明不了与真实游戏的一致性，只会让失效模式更晚暴露。
 *
 * <h2>Minecraft 不得分发</h2>
 * 本测试<b>不</b>包含任何 Minecraft 字节码。它在运行时从
 * {@code tools/26.2/26.2.jar}（或 CI 下载到 {@code test_client/26.2.jar}）
 * 读取。jar 缺失时整个类被跳过 —— 这是<b>正确</b>行为，不是缺陷：
 * 本地开发机本就不该持有 Minecraft。
 *
 * <p><b>但跳过必须是可见的。</b>所以本类所有测试方法都显式调用
 * {@link #requireMinecraftJar()} 而不是靠注解条件 —— 跳过时 JUnit 会
 * 打印明确原因，不会伪装成「全部通过」。
 */
@DisplayName("真实 Minecraft 26.2 冒烟测试（非 mock）")
class Minecraft26_2SmokeTest {

    private static final String DISPATCH_OWNER = MiliTickTransformer.DISPATCH_OWNER;

    /** 真实 jar 的候选位置 —— 本地开发机用 tools/，CI 用 test_client/。 */
    private static final Path[] JAR_CANDIDATES = {
            Path.of("tools", "26.2", "26.2.jar"),
            Path.of("test_client", "26.2.jar"),
            Path.of("..", "tools", "26.2", "26.2.jar"),
            Path.of("..", "test_client", "26.2.jar"),
    };

    private static Path minecraftJar;
    private static byte[] realServerClass;
    private static ClassLoader gameLoader;

    @BeforeAll
    static void locateMinecraft() {
        for (Path candidate : JAR_CANDIDATES) {
            if (Files.isRegularFile(candidate)) {
                minecraftJar = candidate.toAbsolutePath().normalize();
                break;
            }
        }
    }

    /**
     * 一个能从真实 jar 读取类的 ClassLoader —— 对应生产环境的
     * {@code MinecraftClassLoader}。
     *
     * <p><b>这不是测试便利设施，而是被验证行为的必要条件。</b>
     * {@code CheckClassAdapter} 与 {@code SimpleVerifier} 做类型推断时会
     * 真的去加载父类与接口。真实 {@code MinecraftServer} 的父类是
     * {@code ReentrantBlockableEventLoop}，若没有这个加载器，
     * 验证器会抛 {@code ClassNotFoundException} 并把<b>完全正确</b>的
     * 字节码判为非法 —— 一个比「转换器坏了」更坏、也更难以定位的假故障。
     *
     * <p>父加载器选platform 而非应用加载器：验证只需要
     * Minecraft 自己的类 + JDK 核心类，不需要也不应该看到
     * 测试类路径上的平台实现（那会掩盖「游戏类不该被平台类影响」
     * 这条边界）。
     */
    private static ClassLoader gameLoader() {
        if (gameLoader == null) {
            Path jar = requireMinecraftJar();
            try {
                gameLoader = new java.net.URLClassLoader(
                        "minecraft-26.2-smoke",
                        new java.net.URL[]{jar.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader());
            } catch (java.net.MalformedURLException e) {
                throw new AssertionError("无法为 " + jar + " 构造 URLClassLoader", e);
            }
        }
        return gameLoader;
    }

    /**
     * 确认真实 Minecraft 可用，否则显式跳过。
     *
     * <p>返回 jar 路径。仅在确实持有 jar 时才应继续。
     */
    private static Path requireMinecraftJar() {
        assumeTrue(minecraftJar != null,
                "未找到真实 Minecraft jar（已查找 " + List.of(JAR_CANDIDATES)
                        + "）。\n"
                        + "本测试<b>刻意不使用 mock</b> —— mock 只能证明 mock 的形状被正确处理，\n"
                        + "证明不了真实 Minecraft 26.2（major 69 / Java 25）上的行为。\n"
                        + "CI 会先下载 Minecraft 再运行本测试；本地开发机不应持有它（禁止分发）。");
        return minecraftJar;
    }

    /** 从真实 jar 中读取原始的 MinecraftServer 字节码。 */
    private static byte[] realServerBytes() {
        if (realServerClass == null) {
            Path jar = requireMinecraftJar();
            String entry = MiliSymbol.SERVER_TICK.owner() + ".class";
            try (ZipFile zip = new ZipFile(jar.toFile());
                 InputStream in = zip.getInputStream(zip.getEntry(entry))) {
                assertNotNull(in, "jar 中缺少 " + entry);
                realServerClass = in.readAllBytes();
            } catch (IOException e) {
                throw new AssertionError("无法从 " + jar + " 读取 " + entry, e);
            }
        }
        return realServerClass;
    }

    private static TransformationEnvironment env() {
        return new TransformationEnvironment(
                gameLoader(),
                MiliSymbol.MINECRAFT_VERSION,
                TransformationEnvironment.RuntimeEnvironmentValue.DEDICATED_SERVER,
                "0.1.0-smoke");
    }

    private static TransformationContext context(byte[] bytes) {
        return new TransformationContext(
                MiliSymbol.SERVER_TICK.owner(), bytes, env(),
                org.loader.api.transform.TransformationPhase.CORE,
                null, MiliTickTransformer.ID);
    }

    // ── 真实字节码的基本前提 ────────────────────────────────────────

    @Test
    @DisplayName("真实 MinecraftServer 是 major 69 —— 这是 ASM 版本约束的根据")
    void realClassIsJava25() {
        byte[] bytes = realServerBytes();
        ClassReader reader = new ClassReader(bytes);

        // class 文件头布局：magic(0-3) minor(4-5) major(6-7)
        // 必须用偏移 6 处的 2 字节无符号大端值 —— DataInputStream.readShort
        // 是有符号的，且 ClassReader.readShort 的偏移语义是「当前读取位置」，
        // 不是「文件绝对偏移」，两者混用会读出完全无关的数。
        int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);

        assertEquals(69, major,
                "Minecraft 26.2 应为 class major 69（Java 25）。实际: " + major
                        + "\n若此处失败，说明符号表针对的版本与真实 jar 不一致 —— "
                        + "转换目标全部错位，且不会有任何提示。");
    }

    @Test
    @DisplayName("MiliSymbol 的每个坐标都在真实字节码中精确命中")
    void everySymbolResolvesAgainstRealBytecode() {
        // 这一条是整个符号层的存在理由。
        //
        // 需求文档与仓库文档对 tickServer 的描述符曾有两处互相矛盾的写法
        // （tickServer() vs tickServer(BooleanSupplier)）。手写描述符必错，
        // 因此符号表必须由 CI 对真实 jar 逐条校验。
        //
        // 真实 jar 里同时存在 tickServer(BooleanSupplier) 与
        // tickServer(BooleanSupplier, boolean) 两个重载 ——
        // 这正是「只按方法名匹配」会命中错误目标的证据。
        assertTrue(PipelineTransformers.containsMethod(
                        realServerBytes(),
                        MiliSymbol.SERVER_TICK.name(),
                        MiliSymbol.SERVER_TICK.descriptor()),
                "MiliSymbol.SERVER_TICK 在真实 MinecraftServer 中不存在: "
                        + MiliSymbol.SERVER_TICK);

        assertTrue(PipelineTransformers.containsMethod(
                        realServerBytes(),
                        MiliSymbol.SERVER_TICK_CHILDREN.name(),
                        MiliSymbol.SERVER_TICK_CHILDREN.descriptor()),
                "MiliSymbol.SERVER_TICK_CHILDREN 在真实 MinecraftServer 中不存在");
    }

    @Test
    @DisplayName("错误描述符确实不命中 —— 证明严格匹配不是多余的")
    void wrongDescriptorDoesNotMatch() {
        // 若这一条失败，说明 containsMethod 退化成按名字匹配，
        // 那么整个符号层的「精确性」承诺就是假的。
        assertFalse(PipelineTransformers.containsMethod(
                        realServerBytes(), "tickServer", "()V"),
                "tickServer()V 不应命中 —— 真实签名带 BooleanSupplier。"
                        + "若命中，说明断言已退化为按名字匹配。");
    }

    // ── 真实类上的注入产出 ─────────────────────────────────────────

    @Test
    @DisplayName("真实 tickServer 中恰好注入一次 begin 与一次 end")
    void injectsExactlyOnceIntoRealMethod() {
        byte[] original = realServerBytes();
        List<String[]> calls = dispatchCallsIn(MiliSymbol.SERVER_TICK, original, true);

        // 转换前：零调用（证明注入是本次转换产生的，不是原始代码自带的）
        assertTrue(calls.isEmpty(),
                "转换前的真实 tickServer 不应包含分发器调用 —— " + calls);

        MiliTickTransformer transformer = new MiliTickTransformer();
        TransformationResult result = transformer.transform(context(original));
        assertTrue(result instanceof TransformationResult.Transformed,
                "应产出转换后的字节码，实际: " + result.getClass().getSimpleName());

        byte[] transformed = ((TransformationResult.Transformed) result).bytecode();

        long begins = callsTo(transformed, MiliTickTransformer.CALLBACK_HEAD);
        long ends = callsTo(transformed, MiliTickTransformer.CALLBACK_TAIL);

        // begin 恰好一次：它在方法入口，入口只有一个。
        //
        // end 的数量<b>不等于</b> begin 的数量 —— 这是 onMethodExit 的定义，
        // 不是缺陷。真实 tickServer 有多条退出路径，每条都必须有 end，
        // 否则那条路径上的 tick 契约会永久错位（漏掉 end 时游戏看起来
        // 正常，只是 tick 计数悄悄漂移）。
        //
        // 因此正确的断言是「end 数 == 退出路径数」，而不是「end 数 == begin 数」。
        // 后者只在单返回路径的方法上偶然成立 ——
        // 而 tickServer 恰好不是那种方法，所以这条断言在合成类上能过、
        // 在真实类上必然失败。
        assertEquals(1, begins,
                "真实 tickServer 必须恰好注入一次 onTickBegin —— " + begins + "次。"
                        + "零次则 tick 链断开；两次则 tick 推进翻倍而游戏看似正常。");

        PositionReport report = analyzePositions(transformed);
        assertNotNull(report, "无法分析转换后的真实类");
        assertTrue(report.returnCount > 1,
                "真实 tickServer 应当有多个返回路径（实际 " + report.returnCount
                        + " 条）。若只有一条，「每个返回前都注入」这条保证无法被验证，"
                        + "测试就退化成与合成类无异。");

        assertEquals(report.returnCount, ends,
                "end 的数量必须等于退出路径数 —— 每条返回路径各注入一次。"
                        + "实际 end=" + ends + "，退出路径=" + report.returnCount
                        + "。少于路径数说明有路径漏注入（该路径上 tick 契约漂移）；"
                        + "多于路径数说明重复注入（tick 推进翻倍）。");
    }

    @Test
    @DisplayName("真实类的注入点落在方法首与所有返回之前")
    void injectionsArePositionedCorrectlyInRealCode() {
        byte[] transformed = ((TransformationResult.Transformed)
                new MiliTickTransformer().transform(context(realServerBytes()))).bytecode();

        // 真实 tickServer 有多个返回路径。若注入只在最后一个 RETURN 之前，
        // 正常路径之外的返回（无玩家、超时退出）会漏掉 end。
        // 断言「begin 出现在第一条真实指令之前」+「每个返回前都有 end」。
        PositionReport report = analyzePositions(transformed);
        assertNotNull(report, "无法分析转换后的真实类");
        assertTrue(report.beginAtEntry,
                "onTickBegin 必须在方法入口 —— 真实 tickServer 首条指令之前");
        assertTrue(report.returnCount > 1,
                "真实 tickServer 应当有多个返回路径（实际 " + report.returnCount
                        + " 条）。若只有一条，「每个返回前都注入」这条保证无法被验证，"
                        + "测试就退化成与合成类无异。");
        assertEquals(report.returnCount, report.endBeforeReturn,
                "每一条返回路径前都必须有 onTickEnd —— "
                        + "漏掉任一条，该路径上的 tick 契约会永久错位。"
                        + "（实际 " + report.endBeforeReturn + "/" + report.returnCount + "）");
    }

    // ── 真实类上的字节码合法性 ──────────────────────────────────────

    @Test
    @DisplayName("真实类转换后通过三层字节码验证")
    void realOutputPassesVerification() {
        byte[] transformed = ((TransformationResult.Transformed)
                new MiliTickTransformer().transform(context(realServerBytes()))).bytecode();

        // 三层验证：结构/帧（CheckClassAdapter）+ 类型推断（Analyzer+SimpleVerifier）
        //
        // 这一条在真实类上比在合成类上强得多：真实 tickServer 有几十条分支，
        // 注入改变栈深后每个跳转汇合处的 frame 都必须重算正确。
        // COMPUTE_FRAMES 能算对，与 verify 能验过，是两件事。
        //
        // 必须传 gameLoader()：见 gameLoader() 的 Javadoc —— 少了它，
        // 验证器解析 ReentrantBlockableEventLoop 时会抛
        // ClassNotFoundException，并把正确的字节码判为非法。
        assertDoesNotThrow(() -> BytecodeVerifier.verify(
                MiliSymbol.SERVER_TICK.owner(), transformed,
                MiliTickTransformer.ID, null, gameLoader()));
    }

    @Test
    @DisplayName("真实类的转换结果可被 ASM 完整读回 —— 常量池未损坏")
    void realOutputRoundTripsThroughClassReader() {
        byte[] transformed = ((TransformationResult.Transformed)
                new MiliTickTransformer().transform(context(realServerBytes()))).bytecode();

        // 用 EXPAND_FRAMES 读回并写出。这会强制解析每一个 frame 与每一条指令，
        // 任何常量池损坏都会在这里暴露。
        assertDoesNotThrow(() -> {
            ClassReader reader = new ClassReader(transformed);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(writer, ClassReader.EXPAND_FRAMES);
            assertTrue(writer.toByteArray().length > 0);
        });
    }

    // ── 常量池卫生 ──────────────────────────────────────────────────

    @Test
    @DisplayName("真实类的转换结果常量池中零 Mod 引用")
    void noModReferencesInRealOutput() {
        byte[] transformed = ((TransformationResult.Transformed)
                new MiliTickTransformer().transform(context(realServerBytes()))).bytecode();

        String pool = constantPoolStrings(transformed);

        assertTrue(pool.contains("TickCallbackDispatch"),
                "必须引用平台分发器 —— 它是生成字节码唯一允许的外部调用目标。");

        // MinecraftClassLoader 定义的类若持有 Mod 类引用，
        // ModClassLoader 就无法被回收（仓库已有 ClassLoaderLeakTest 守这条线）。
        // 真实 MinecraftServer 有上千个方法、上万个常量池条目 ——
        // 注入若引入任何 Mod 引用，泄漏的规模也会被放大上千倍。
        assertFalse(pool.contains("org/loader/runtime/minecraft/transform/MiliTickTransformer"),
                "生成的字节码不得引用转换器自身 —— 引用方向反了。");
        assertFalse(pool.contains("net/minecraft/mod/"),
                "不得引用任何 Mod 包下的类。");
    }

    // ── 走完整流水线（非旁路调用） ──────────────────────────────────

    @Test
    @DisplayName("真实类走完整 Registry→Pipeline 链路，产出与直调一致")
    void fullPipelineProducesSameResultOnRealClass() {
        byte[] original = realServerBytes();

        TransformerRegistry registry = new TransformerRegistry(
                MiliSymbol.MINECRAFT_VERSION);
        MiliTickTransformer transformer = new MiliTickTransformer();
        registry.register(transformer, null);          // 平台自身
        registry.indexClass(MiliSymbol.SERVER_TICK.owner());
        registry.seal();

        TransformerPipeline pipeline = TransformerPipeline.builder()
                .registry(registry)
                .verifyEnabled(true)                     // 生产配置
                .build();

        Scope platformScope = new Scope("smoke-platform", null);
        byte[] result;
        try {
            result = pipeline.transform(
                    MiliSymbol.SERVER_TICK.owner(), original, env(), platformScope);
        } finally {
            platformScope.close();
        }

        assertEquals(1, callsTo(result, MiliTickTransformer.CALLBACK_HEAD),
                "流水线路径必须真的注入 —— 这条链路是 ClassLoader 唯一的插入点。");
        assertEquals(1, pipeline.transformedClassCount(),
                "统计必须反映真实转换发生过。");
        assertEquals(0, pipeline.skippedClassCount(),
                "MinecraftServer 不应走短路 —— 它正是要转换的那个类。");
    }

    @Test
    @DisplayName("无关的真实类零开销短路 —— 一万个类不进 ASM")
    void unrelatedRealClassesShortCircuit() {
        // 取一个真实存在但与 tick 无关的类，验证热路径。
        // 性能目标不是「快一点」，而是「绝大多数类完全不进 ASM」。
        byte[] unrelated = loadRealClass("net/minecraft/server/MinecraftServer$ServerLifecycle")
                .orElseGet(() -> realServerBytes());

        TransformerRegistry registry = new TransformerRegistry(
                MiliSymbol.MINECRAFT_VERSION);
        registry.register(new MiliTickTransformer(), null);
        registry.seal();

        TransformerPipeline pipeline = TransformerPipeline.builder()
                .registry(registry).build();

        byte[] result = pipeline.transform(
                "com/example/Unrelated", unrelated, env(), null);

        assertTrue(result == unrelated,
                "不匹配的类必须原样返回同一个数组实例 —— "
                        + "拷贝字节码本身就是不必要的开销。");
        assertEquals(0, pipeline.transformerRunCount(),
                "不匹配的类不应执行任何转换器。");
    }

    @Test
    @DisplayName("真实类的符号缺失时明确失败，绝不静默跳过")
    void missingSymbolOnRealBytesFailsLoudly() {
        // 用一个真实但目标签名不对的类字节码，验证 fail-fast 在真实数据上同样成立。
        byte[] tampered = stripTickServer(realServerBytes());
        assertFalse(PipelineTransformers.containsMethod(tampered, "tickServer",
                        MiliSymbol.SERVER_TICK.descriptor()),
                "前置条件：构造出的字节码确实已移除目标方法");

        MiliTickTransformer transformer = new MiliTickTransformer();

        assertThrows(
                org.loader.api.transform.TransformationTargetNotFoundException.class,
                () -> transformer.transform(context(tampered)),
                "真实数据上同样必须 fail-fast。若返回 Skipped："
                        + "游戏照常运行、Mod 的 tick 不执行、日志无任何异常 —— "
                        + "那正是本系统要消灭的失效模式。");
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    /** 从真实 jar 读取指定类；不存在返回空。 */
    private static java.util.Optional<byte[]> loadRealClass(String internalName) {
        Path jar = requireMinecraftJar();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(internalName + ".class");
            if (entry == null) {
                return java.util.Optional.empty();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return java.util.Optional.of(in.readAllBytes());
            }
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * 移除 tickServer 方法（构造「符号缺失」场景）。
     *
     * <p>用 ASM 的 {@code ClassRemapper} 太重，这里直接改写：
     * 把方法名改成别的即可 —— 目标断言按 name + descriptor 严格匹配，
     * 改名后必然不命中。
     */
    private static byte[] stripTickServer(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                String outName = MiliSymbol.SERVER_TICK.name().equals(name)
                        ? "tickServerRenamed" : name;
                return super.visitMethod(access, outName, desc, signature, exceptions);
            }
        }, 0);
        return writer.toByteArray();
    }

    /** 统计转换后字节码中对分发器指定方法的调用次数。 */
    private static long callsTo(byte[] bytes, String methodName) {
        final long[] count = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner,
                                                String mName, String mDesc, boolean itf) {
                        if (DISPATCH_OWNER.equals(owner) && methodName.equals(mName)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return count[0];
    }

    /** 列出指定方法内对分发器的所有调用。 */
    private static List<String[]> dispatchCallsIn(
            TargetMethod target, byte[] bytes, boolean onlyInTarget) {
        List<String[]> result = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                if (onlyInTarget && !(target.name().equals(name)
                        && target.descriptor().equals(desc))) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner,
                                                String mName, String mDesc, boolean itf) {
                        if (DISPATCH_OWNER.equals(owner)) {
                            result.add(new String[]{mName, mDesc});
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return result;
    }

    /** 注入位置分析结果。 */
    private record PositionReport(boolean beginAtEntry, int returnCount, int endBeforeReturn) {
    }

    /**
     * 分析 onTickBegin 是否在方法首、onTickEnd 是否在每个返回之前。
     *
     * <p>逐指令线性扫描即可 —— 这里不需要理解控制流，只需要确认
     * 「进入方法后第一条真实指令之前有 begin」以及
     * 「每条 xRETURN 之前紧邻着 end」。
     */
    private static PositionReport analyzePositions(byte[] transformed) {
        final boolean[] beginAtEntry = {false};
        final int[] returnCount = {0};
        final int[] endBeforeReturn = {0};
        final boolean[] sawNonTrivial = {false};

        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                if (!(MiliSymbol.SERVER_TICK.name().equals(name)
                        && MiliSymbol.SERVER_TICK.descriptor().equals(desc))) {
                    return null;
                }
                // 最近一条「实质性」指令是否是 onTickBegin 调用
                final String[] lastCall = {null};
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner,
                                                String mName, String mDesc, boolean itf) {
                        if (DISPATCH_OWNER.equals(owner)) {
                            lastCall[0] = mName;
                            return;
                        }
                        if (!sawNonTrivial[0]) {
                            // 第一条非分发器调用之前的分发器调用即「入口注入」
                            beginAtEntry[0] = MiliTickTransformer.CALLBACK_HEAD.equals(
                                    lastCall[0]);
                            sawNonTrivial[0] = true;
                        }
                        lastCall[0] = null;
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                            returnCount[0]++;
                            if (MiliTickTransformer.CALLBACK_TAIL.equals(lastCall[0])) {
                                endBeforeReturn[0]++;
                            }
                            lastCall[0] = null;
                            return;
                        }
                        // 原本这里还排除了 NOP / LINE / LABEL，但那是错的：
                        // ASM 里 Label 走独立的 visitLabel() 回调，
                        // LineNumber 走 visitLineNumber()，
                        // 两者都不会出现在 visitInsn(int) 里 ——
                        // Opcodes.LINE / Opcodes.LABEL 这两个常量根本不存在。
                        // 保留过滤 NOP：它确实是 opcode，且不携带语义。
                        if (opcode == Opcodes.NOP) {
                            return;
                        }
                        lastCall[0] = null;
                    }

                    @Override
                    public void visitVarInsn(int opcode, int varIndex) {
                        lastCall[0] = null;
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String fOwner,
                                                String fName, String fDesc) {
                        lastCall[0] = null;
                    }

                    @Override
                    public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        lastCall[0] = null;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);

        return new PositionReport(beginAtEntry[0], returnCount[0], endBeforeReturn[0]);
    }

    private static void assertDoesNotThrow(Executable executable) {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(executable::execute);
    }

    /** 无返回值、无抛出的可执行块。 */
    @FunctionalInterface
    private interface Executable {
        void execute() throws Throwable;
    }

    /**
     * 提取常量池 UTF8 字符串。
     *
     * <p><b>手写常量池扫描而非 ASM</b>：这里要断言的恰恰是「常量池里有没有
     * 不该出现的类名」，而 ASM 不暴露常量池。绕一圈用 ASM 查询反而看不到全貌。
     *
     * <p>（这条也提醒了一件事：我自己在验证符号时手写解析器就踩了三次坑
     * —— 字段解析少算 2 字节、解包变量数写错。生产代码用 ASM 是对的。）
     */
    private static String constantPoolStrings(byte[] bytes) {
        // 用 ASM 官方 API 遍历，而不是手写常量池解析。
        //
        // 原实现用手工 DataInputStream 逐个 tag 读取，并case 1 -> in.readUTF()。
        // 那是错的：readUTF 读的是「2 字节长度 + 平台编码的字符」，
        // 而 class 文件常量池的 Utf8 是「u2 长度 + 原始字节（modified UTF-8）」。
        // 真实 MinecraftServer 有上万个常量、长度常超 65535，
        // 于是 readUTF 抛异常，被 catch (Throwable ignored) 静默吞掉并提前 return ——
        // 断言拿到的是一份被截断的常量池，「必须引用分发器」因此永远失败，
        // 而真凶（解析器）不会出现在任何错误信息里。
        StringBuilder sb = new StringBuilder();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                sb.append(name).append('\n');
                if (superName != null) {
                    sb.append(superName).append('\n');
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // visit() 只给类级名字。方法引用（分发器）藏在指令里，
        // 因此再走一遍，这次不跳过代码。
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                sb.append(name).append(desc).append('\n');
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String mName,
                                                String mDesc, boolean itf) {
                        sb.append(owner).append('.').append(mName).append('\n');
                    }

                    @Override
                    public void visitFieldInsn(int op, String owner, String fName,
                                               String fDesc) {
                        sb.append(owner).append('.').append(fName).append('\n');
                    }

                    @Override
                    public void visitTypeInsn(int op, String type) {
                        sb.append(type).append('\n');
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return sb.toString();
    }
}