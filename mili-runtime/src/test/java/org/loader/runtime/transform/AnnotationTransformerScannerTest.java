package org.loader.runtime.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationException;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.TransformationResult;
import org.loader.api.transform.annotation.MiliInject;
import org.loader.api.transform.callback.InjectionContext;
import org.loader.api.transform.symbol.MiliSymbol;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.loader.runtime.transform.engine.AnnotationTransformerScanner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 声明式注解扫描器测试。
 *
 * <h2>这个测试在守什么</h2>
 * ADR-0011 的硬约束是「Mod 的编译依赖里没有 ASM」。能做到这一点，
 * 前提是 Mod 只写注解、由平台合成转换器。而这条路能走通，
 * 完全依赖于一件事：<b>回调签名错误必须在加载期被拦下</b>。
 *
 * <p>因为签名错误若漏到字节码生成阶段，报错形态是灾难性的：
 * <pre>
 *   VerifyError: Bad type on operand stack
 *     at net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:0)
 * </pre>
 * 堆栈指向游戏代码，Mod 作者与玩家都无法反推是自己的 Mod 出了问题。
 *
 * <p>而这些错误（少 static、参数类型不对、void 用在 MODIFY_RETURN 上）
 * <b>全是纯反射可判定的</b>。所以测试的核心不是「注解能扫出来」，
 * 而是<b>「该拒绝的都拒绝了，且拒绝时说清了原因」</b>。
 */
@DisplayName("声明式注解扫描器")
class AnnotationTransformerScannerTest {

    private static final String MC_VERSION = MiliSymbol.MINECRAFT_VERSION;

    // ── 合法的声明 ──────────────────────────────────────────────────

    @org.loader.api.transform.annotation.MiliTransformer(target = "minecraft.server.tick")
    public static final class ValidHooks {

        @MiliInject(at = InjectionPoint.HEAD)
        public static void onEnter(InjectionContext ctx) {
        }

        @MiliInject(at = InjectionPoint.RETURN)
        public static void onExit() {
        }
    }

    // ── 各种非法声明 ────────────────────────────────────────────────

    @org.loader.api.transform.annotation.MiliTransformer(target = "minecraft.server.tick")
    public static final class InstanceCallback {

        /** 非 static —— 生成的是 INVOKESTATIC，实例方法无法链接。 */
        @MiliInject(at = InjectionPoint.HEAD)
        public void onEnter() {
        }
    }

    @org.loader.api.transform.annotation.MiliTransformer(target = "minecraft.server.tick")
    public static final class VoidModifyReturn {

        @MiliInject(at = InjectionPoint.MODIFY_RETURN)
        public static void onReturn() {
        }
    }

    @org.loader.api.transform.annotation.MiliTransformer(target = "minecraft.server.tick")
    public static final class NonVoidHead {

        /** HEAD 注入点返回非 void —— 多出的返回值无处消费，栈失衡。 */
        @MiliInject(at = InjectionPoint.HEAD)
        public static int onEnter() {
            return 1;
        }
    }

    @org.loader.api.transform.annotation.MiliTransformer(target = "minecraft.server.tick")
    public static final class TooManyParams {

        @MiliInject(at = InjectionPoint.HEAD)
        public static void onEnter(InjectionContext ctx, int extra) {
        }
    }

    @org.loader.api.transform.annotation.MiliTransformer(target = "minecraft.server.tick")
    public static final class WrongParamType {

        @MiliInject(at = InjectionPoint.HEAD)
        public static void onEnter(String notAContext) {
        }
    }

    /**
     * {@code target} 留空 —— 走「未声明 target 符号名」这条分支。
     *
     * <p>类名保留为 {@code NoSymbol} 是历史命名；
     * 它与 {@link UnknownSymbol} 的区别是<b>符号名为空</b>，
     * 而不是「符号名不存在」。
     */
    @org.loader.api.transform.annotation.MiliTransformer(target = "")
    public static final class NoSymbol {

        @MiliInject(at = InjectionPoint.HEAD)
        public static void onEnter() {
        }
    }

    @org.loader.api.transform.annotation.MiliTransformer(target = "minecraft.not.a.symbol")
    public static final class UnknownSymbol {

        @MiliInject(at = InjectionPoint.HEAD)
        public static void onEnter() {
        }
    }

    @org.loader.api.transform.annotation.MiliTransformer(target = "minecraft.server.tick")
    public static final class NoCallbacks {
    }

    @org.loader.api.transform.annotation.MiliTransformer(target = "minecraft.server.tick")
    public static final class MissingInvokeTarget {

        /** BEFORE_INVOKE 必须指定被调用的方法坐标。 */
        @MiliInject(at = InjectionPoint.BEFORE_INVOKE)
        public static void before() {
        }
    }

    public static final class NotAnnotated {
        public static void whatever() {
        }
    }

    // ── 测试 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("合法声明被合成为转换器，阶段默认 MOD")
    void validDeclarationIsScanned() {
        var t = AnnotationTransformerScanner.scan(ValidHooks.class, MC_VERSION, "testmod");

        assertNotNull(t, "应产出转换器");
        assertEquals(2, t.callbacks().size(), "两个 @MiliInject 方法应都被收集");
        assertEquals(TransformationPhase.MOD, t.phase(),
                "Mod 默认阶段必须是 MOD —— CORE 由平台保留");
        assertEquals(MC_VERSION, t.minecraftVersion());
        assertEquals("testmod#ValidHooks", t.id(),
                "id 必须全局唯一且可读 —— 它是冲突检测与审计的主键");
    }

    @Test
    @DisplayName("未标注的类返回 null，不抛异常")
    void unannotatedClassReturnsNull() {
        assertNull(AnnotationTransformerScanner.scan(NotAnnotated.class, MC_VERSION, "m"),
                "未标注的类不是转换声明，返回 null 让调用方正常跳过");
    }

    @Test
    @DisplayName("生成的字节码引用回调所在的类")
    void generatedBytecodeReferencesCallbackOwner() {
        var t = AnnotationTransformerScanner.scan(ValidHooks.class, MC_VERSION, "testmod");
        byte[] target = sampleTargetClass();
        byte[] result = ((TransformationResult.Transformed) t.transform(context(target))).bytecode();

        // 回调 owner 必须是 ValidHooks 的内部名。
        // 注意嵌套类的内部名含 '$'（Outer$Inner）—— 这类细节手写极易出错。
        assertTrue(containsCall(result, internalName(ValidHooks.class)),
                "生成的字节码必须调用声明的回调。实际内部名: "
                        + internalName(ValidHooks.class));
    }

    /** 类的 JVM 内部名 —— 嵌套类含 '$'，与 {@code getName()} 不同。 */
    private static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    @Test
    @DisplayName("回调描述符由反射推导 —— 不存在手写转录错误")
    void callbackDescriptorIsDerivedNotHandwritten() {
        var t = AnnotationTransformerScanner.scan(ValidHooks.class, MC_VERSION, "testmod");

        // onEnter(InjectionContext) → (L.../InjectionContext;)V
        var enter = t.callbacks().stream()
                .filter(c -> c.method().getName().equals("onEnter")).findFirst().orElseThrow();
        assertEquals("(Lorg/loader/api/transform/callback/InjectionContext;)V",
                enter.callbackDescriptor(),
                "带 InjectionContext 的回调描述符必须自动推导。"
                        + "本仓库的文档里对同一方法曾有两处互相矛盾的描述符写法 —— "
                        + "手写描述符必错，推导才可靠。");

        // onExit() → ()V
        var exit = t.callbacks().stream()
                .filter(c -> c.method().getName().equals("onExit")).findFirst().orElseThrow();
        assertEquals("()V", exit.callbackDescriptor());
    }

    // ── 必须拒绝的签名 ──────────────────────────────────────────────

    @Test
    @DisplayName("非 static 回调被拒绝")
    void instanceCallbackRejected() {
        var e = assertThrows(TransformationException.class,
                () -> AnnotationTransformerScanner.scan(
                        InstanceCallback.class, MC_VERSION, "testmod"),
                "注入生成 INVOKESTATIC，实例回调无法链接。"
                        + "若放行，报错会推迟到 defineClass 且指向 Minecraft。");
        assertTrue(e.getMessage().contains("static"), "错误信息应点明 static 要求");
    }

    @Test
    @DisplayName("void 回调用于 MODIFY_RETURN 被拒绝")
    void voidModifyReturnRejected() {
        var e = assertThrows(TransformationException.class,
                () -> AnnotationTransformerScanner.scan(
                        VoidModifyReturn.class, MC_VERSION, "m"));
        assertTrue(e.getMessage().contains("MODIFY_RETURN"),
                "错误信息应点明注入点");
    }

    @Test
    @DisplayName("HEAD 注入点返回非 void 被拒绝 —— 栈会失衡")
    void nonVoidHeadRejected() {
        var e = assertThrows(TransformationException.class,
                () -> AnnotationTransformerScanner.scan(
                        NonVoidHead.class, MC_VERSION, "m"),
                "HEAD 插入的回调返回值无处消费 —— 栈高度改变，VerifyError。");
        assertTrue(e.getMessage().contains("void"));
    }

    @Test
    @DisplayName("参数形态错误被拒绝（捕获类型不匹配也在此拦截）")
    void badParameterShapeRejected() {
        // ctx 后跟一个捕获参数，但目标第一实参是 BooleanSupplier 不是 int
        var e1 = assertThrows(TransformationException.class,
                () -> AnnotationTransformerScanner.scan(
                        TooManyParams.class, MC_VERSION, "m"),
                "捕获参数与目标实参逐位严格相等 —— int ≠ BooleanSupplier");
        assertTrue(e1.getMessage().contains("捕获参数"),
                "错误信息应指出捕获参数不匹配: " + e1.getMessage());

        var e2 = assertThrows(TransformationException.class,
                () -> AnnotationTransformerScanner.scan(
                        WrongParamType.class, MC_VERSION, "m"));
        assertTrue(e2.getMessage().contains("捕获参数"),
                "String 不匹配目标实参 —— 错误信息应说明捕获按位严格相等: "
                        + e2.getMessage());
    }

    @Test
    @DisplayName("BEFORE_INVOKE 缺少调用坐标被拒绝")
    void missingInvokeTargetRejected() {
        var e = assertThrows(TransformationException.class,
                () -> AnnotationTransformerScanner.scan(
                        MissingInvokeTarget.class, MC_VERSION, "m"));
        assertTrue(e.getMessage().contains("target"));
    }

    // ── 符号解析 ────────────────────────────────────────────────────

    @Test
    @DisplayName("未知符号名必须报错 —— 绝不静默跳过")
    void unknownSymbolFailsLoudly() {
        var e = assertThrows(TransformationException.class,
                () -> AnnotationTransformerScanner.scan(
                        UnknownSymbol.class, MC_VERSION, "m"),
                "符号名写错若静默跳过，表现就是「Mod 加载成功但功能永远不生效」—— "
                        + "本系统最想消灭的失效模式。");
        assertTrue(e.getMessage().contains("minecraft.not.a.symbol"),
                "错误信息应回显写错的符号名");
    }

    @Test
    @DisplayName("未声明 target 必须报错并列出可用符号")
    void missingSymbolReportsAvailableOnes() {
        var e = assertThrows(TransformationException.class,
                () -> AnnotationTransformerScanner.scan(
                        NoSymbol.class, MC_VERSION, "m"));
        assertTrue(e.getMessage().contains("minecraft.server.tick"),
                "应列出可用符号，让作者知道该写什么");
    }

    @Test
    @DisplayName("有 @MiliTransformer 但无 @MiliInject 必须报错")
    void noCallbacksRejected() {
        assertThrows(TransformationException.class,
                () -> AnnotationTransformerScanner.scan(
                        NoCallbacks.class, MC_VERSION, "m"),
                "空转换器会匹配目标类却什么都不做，"
                        + "与「转换失败」无法区分");
    }

    @Test
    @DisplayName("扫描器符号表与 MiliMapping 覆盖同一批坐标")
    void scannerSymbolsMatchMapping() {
        // 两份符号表漂移是最隐蔽的失效：Mod 声明的符号能加载，
        // 但注入目标与平台内部用的不是同一个方法 —— 且不报错。
        assertDoesNotThrow(
                org.loader.runtime.transform.engine.AnnotationTransformerScanner
                        ::assertConsistentWithMapping,
                "扫描器的短名表必须与 MiliMapping 覆盖同一批坐标");
    }

    // ── 目标存在性 ──────────────────────────────────────────────────

    @Test
    @DisplayName("目标方法缺失时抛 TargetNotFound")
    void missingTargetThrows() {
        var t = AnnotationTransformerScanner.scan(ValidHooks.class, MC_VERSION, "m");
        byte[] empty = emptyTargetClass();

        assertThrows(
                org.loader.api.transform.TransformationTargetNotFoundException.class,
                () -> t.transform(context(empty)),
                "目标缺失必须明确失败 —— 返回 Skipped 会让 Mod 静默不生效");
    }

    @Test
    @DisplayName("真实转换产出通过字节码验证")
    void outputPassesVerification() {
        var t = AnnotationTransformerScanner.scan(ValidHooks.class, MC_VERSION, "m");
        byte[] result = ((TransformationResult.Transformed)
                t.transform(context(sampleTargetClass()))).bytecode();

        // 必须传 ClassLoader：4 参重载无法解析外部类型，
        // 验证器会降级为纯栈深校验 —— 而本测试要守的正是
        // 「参数类型是否正确」，栈深校验恰恰看不到类型。
        assertDoesNotThrow(() -> org.loader.runtime.transform.verify.BytecodeVerifier
                .verify(MiliSymbol.SERVER_TICK.owner(), result, t.id(), "m",
                        AnnotationTransformerScannerTest.class.getClassLoader()));
    }

    /**
     * 带 {@code InjectionContext} 参数的回调必须真的把参数压栈。
     *
     * <h2>这个测试在守什么</h2>
     * {@link ValidHooks#onEnter} 接收一个 {@code InjectionContext}，
     * 而注入生成的是 {@code INVOKESTATIC onEnter(ctx)}。
     * {@code ctx} 在字节码里并不存在 —— 引擎必须调用
     * {@code InjectionContextFactory.forMethod} 现造一个。
     *
     * <p>曾经的 bug：{@code pushCallback} 无条件发出
     * {@code INVOKESTATIC <描述符>}而从不压参数。产出的是栈下溢的字节码，
     * 报错形态是
     * {@code AnalyzerException: Error at instruction 0: Cannot pop operand off an empty stack}
     * —— 指令 0 看起来完全正常（它就是那条回调调用），
     * 真正原因「少了一个参数」却在三行之外。
     *
     * <p><b>为什么用指令序列断言而不是只靠 verify()</b>：
     * {@code BytecodeVerifier} 在缺依赖时会降级，降级后不再做类型推断。
     * 一旦某天它降级了，这个测试就会静默通过 ——
     * 而失效现象是「Mod 写了带参回调，游戏启动即崩」，
     * 属于最不该静默的那一类。因此直接断言字节码形状。
     */
    @Test
    @DisplayName("带 InjectionContext 的回调必须压入由工厂构造的参数")
    void contextArgumentIsMaterializedBeforeCallback() {
        var t = AnnotationTransformerScanner.scan(ValidHooks.class, MC_VERSION, "m");
        byte[] result = ((TransformationResult.Transformed)
                t.transform(context(sampleTargetClass()))).bytecode();

        List<String> instructions = instructionsOf(result, MiliSymbol.SERVER_TICK.name());

        // 三参工厂（携带宿主实例）—— 上轮实例上下文改造后的形态。
        int factory = instructions.indexOf("INVOKESTATIC "
                + "org/loader/runtime/transform/asm/InjectionContextFactory"
                + ".forMethodWithTarget"
                + "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)"
                + "Lorg/loader/api/transform/callback/InjectionContext;");
        int callback = instructions.indexOf("INVOKESTATIC "
                + internalName(ValidHooks.class)
                + ".onEnter(Lorg/loader/api/transform/callback/InjectionContext;)V");

        assertTrue(factory >= 0,
                "带参回调前必须调用工厂构造 InjectionContext，实际指令序列:\n  "
                        + String.join("\n  ", instructions));
        assertTrue(callback >= 0,
                "应调用 onEnter 回调，实际指令序列:\n  "
                        + String.join("\n  ", instructions));
        assertTrue(factory < callback,
                "工厂调用必须排在回调之前 —— 否则栈上的还是空值。\n"
                        + "实际指令序列:\n  " + String.join("\n  ", instructions));

        // 发射序列：LDC owner → LDC name → ALOAD 0（this）→ INVOKESTATIC。
        // tickServer 是实例方法，宿主实例必须真的传进去（sampleTargetClass
        // 未声明为 static），而非 ACONST_NULL。
        assertEquals("LDC " + MiliSymbol.SERVER_TICK.owner(), instructions.get(factory - 3),
                "工厂第一个实参应是目标类内部名");
        assertEquals("LDC " + MiliSymbol.SERVER_TICK.name(), instructions.get(factory - 2),
                "工厂第二个实参应是目标方法名");
        assertEquals("VAR 25 0", instructions.get(factory - 1),
                "工厂第三个实参应是宿主实例 this（ALOAD 0）");
    }

    /**
     * 带捕获参数的回调必须在生成期做逐位校验，而非产出非法字节码。
     *
     * <h2>为什么绕过扫描器直接构造注入</h2>
     * 声明式路径（{@code @MiliInject}）在<b>扫描期</b>就会拦下类型
     * 不匹配的捕获参数 —— 那是第一道防线。
     * 但 {@link org.loader.runtime.transform.asm.MiliClassTransformer}
     * 是<b>公开可调用的</b>：平台内部转换器、以及任何直接使用引擎的代码
     * 都能绕过扫描器构造 {@code MethodInjection}。
     *
     * <p>因此引擎自身必须再兜一道。塞默认值（null / 0）会让调用方
     * 收到<b>看似合法实则错误</b>的数据：坐标变成 0、tickId 变成 0，
     * 基于这些值做出错误决策却没有任何错误提示。
     * 宁可生成期失败并说清原因。
     */
    @Test
    @DisplayName("捕获类型不匹配在生成期报错 —— 绝不塞默认值")
    void unknownCallbackParameterRejectedAtGenerationTime() {
        var injection = new org.loader.runtime.transform.asm.MiliClassTransformer.MethodInjection(
                MiliSymbol.SERVER_TICK,
                null,
                org.loader.api.transform.InjectionPoint.HEAD,
                // String 不是目标第一实参（BooleanSupplier）—— 捕获类型不匹配
                "(Ljava/lang/String;)V",
                null,
                0,
                internalName(ValidHooks.class),
                "onEnter",
                "(Ljava/lang/String;)V",
                true,
                "probe#onEnter",
                0);

        var e = assertThrows(TransformationException.class,
                () -> org.loader.runtime.transform.asm.MiliClassTransformer.apply(
                        sampleTargetClass(), MiliSymbol.SERVER_TICK.owner(), List.of(injection)),
                "引擎不能假设「所有调用方都走过扫描器」—— "
                        + "它自己就是公开 API。");
        assertTrue(e.getMessage().contains("捕获参数")
                        && e.getMessage().contains("Ljava/lang/String;"),
                "错误信息应同时说明捕获位序号与实际声明类型: " + e.getMessage());
    }

    /** 列出目标方法的全部指令（可读形式）。 */
    private static List<String> instructionsOf(byte[] bytes, String methodName) {
        List<String> out = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d,
                                            String sig, String[] ex) {
                if (!n.equals(methodName)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        out.add("LDC " + value);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String o, String mn,
                                                String md, boolean itf) {
                        out.add((opcode == Opcodes.INVOKESTATIC ? "INVOKESTATIC " : "INVOKE ")
                                + o + "." + mn + md);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        out.add("OP " + opcode);
                    }

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        out.add("VAR " + opcode + " " + var);
                    }
                };
            }
        }, 0);
        return out;
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private static TransformationContext context(byte[] bytes) {
        return new TransformationContext(
                MiliSymbol.SERVER_TICK.owner(), bytes,
                new org.loader.api.transform.TransformationEnvironment(
                        AnnotationTransformerScannerTest.class.getClassLoader(),
                        MC_VERSION,
                        org.loader.api.transform.TransformationEnvironment
                                .RuntimeEnvironmentValue.DEDICATED_SERVER,
                        "0.1.0-test"),
                TransformationPhase.MOD, null, "testmod#ValidHooks");
    }

    /** 生成一个 owner 与符号表一致、含 tickServer 的类。 */
    private static byte[] sampleTargetClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                MiliSymbol.SERVER_TICK.owner(), null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC,
                MiliSymbol.SERVER_TICK.name(), MiliSymbol.SERVER_TICK.descriptor(),
                null, null);
        mv.visitCode();
        // 必须消费掉入参再返回。
        // tickServer 的描述符是 (Ljava/util/function/BooleanSupplier;)V ——
        // 带一个参数。若方法体只写 RETURN，局部变量表里留着未读的参数，
        // CheckClassAdapter 的数据流校验会报「Cannot pop operand off an
        // empty stack」。这不是引擎的 bug，是 fixture 自己造的非法字节码。
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** owner 一致但没有 tickServer —— 构造「目标缺失」场景。 */
    private static byte[] emptyTargetClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                MiliSymbol.SERVER_TICK.owner(), null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "other", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static boolean containsCall(byte[] bytes, String owner) {
        final boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d,
                                             String s, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String o, String mn,
                                                String md, boolean itf) {
                        if (owner.equals(o)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return found[0];
    }
}