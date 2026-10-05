package org.loader.runtime.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.TransformationVerificationException;
import org.loader.api.transform.target.TargetMethod;
import org.loader.runtime.transform.asm.MiliClassTransformer;
import org.loader.runtime.transform.verify.BytecodeVerifier;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字节码验证器测试。
 *
 * <h2>为什么这道关卡值得单独存在</h2>
 * 转换器产出非法字节码时，JVM 的报错方式对 Mod 作者几乎毫无帮助：
 * <pre>
 *   java.lang.VerifyError: Bad type on operand stack
 *       at net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:0)
 * </pre>
 * 堆栈指向游戏代码，完全不指向真正的原因。
 *
 * <p>把验证放在 {@code defineClass} 之前，等于把「游戏运行中的
 * 晦涩异常」换成「启动时的、带完整上下文的明确报错」。
 */
class BytecodeVerificationTest {

    private static final String CLASS_NAME = "test/VerifyTarget";

    /**
     * 生成一个带类型错误的类：把 long 当 int 用。
     *
     * <p><b>刻意用 {@link ClassWriter#COMPUTE_MAXS} 而非
     * {@code COMPUTE_FRAMES}</b>。若用 COMPUTE_FRAMES，ASM 会对这段
     * 类型不一致的代码做帧计算，可能在<b>生成阶段</b>就报错或产出别的东西 ——
     * 那样测试会以「生成错误」而非「验证错误」失败，
     * 就<b>测不到验证器</b>了。COMPUTE_MAXS 让 ASM 原样发出指令，
     * 从而由验证器来拒绝 —— 这正是本测试要验的行为。
     */
    private static byte[] typeMismatchClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, CLASS_NAME,
                null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "broken", "()I", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.LCONST_0);   // 压入 long
        mv.visitInsn(Opcodes.IRETURN);    // 但方法返回 int —— 类型不匹配
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 生成一个完全合法的类作为对照组。 */
    private static byte[] validClass() {
        return TransformTestFixture.sampleClass();
    }

    @Test
    @DisplayName("合法字节码通过验证")
    void validBytecodePasses() {
        assertDoesNotThrow(() -> BytecodeVerifier.verify(
                CLASS_NAME, validClass(), "test-transformer", "mod-a"));
    }

    @Test
    @DisplayName("类型不匹配的字节码被拦截，异常带上完整上下文")
    void typeMismatchIsRejected() {
        byte[] broken = typeMismatchClass();

        TransformationVerificationException ex = assertThrows(
                TransformationVerificationException.class,
                () -> BytecodeVerifier.verify(
                        CLASS_NAME, broken, "test-transformer", "mod-a"));

        // 这四条上下文缺一不可：只有类名+原因的错误，用户依然无从下手
        assertTrue(ex.getMessage().contains(CLASS_NAME),
                "必须报出类名: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("test-transformer"),
                "必须报出转换器 id: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("mod-a"),
                "必须报出 Mod id: " + ex.getMessage());
    }

    @Test
    @DisplayName("完全不是 class 文件的内容被拒绝")
    void garbageBytesAreRejected() {
        assertThrows(TransformationVerificationException.class,
                () -> BytecodeVerifier.verify(
                        CLASS_NAME, new byte[]{1, 2, 3, 4, 5}, "t", null));
    }

    @Test
    @DisplayName("空字节码被拒绝并给出明确原因")
    void emptyBytecodeIsRejected() {
        assertThrows(TransformationVerificationException.class,
                () -> BytecodeVerifier.verify(CLASS_NAME, new byte[0], "t", null));
        assertThrows(TransformationVerificationException.class,
                () -> BytecodeVerifier.verify(CLASS_NAME, null, "t", null));
    }

    @Test
    @DisplayName("真实注入产出的字节码必须通过三层验证")
    void realInjectionOutputPassesAllThreeLayers() {
        TargetMethod target = TargetMethod.of(
                TransformTestFixture.SAMPLE_CLASS, "intMethod",
                TransformTestFixture.INT_DESC);

        byte[] injected = MiliClassTransformer.apply(
                TransformTestFixture.sampleClass(),
                TransformTestFixture.SAMPLE_CLASS,
                List.of(new MiliClassTransformer.MethodInjection(
                        target, null, InjectionPoint.HEAD, "()V", null, -1,
                        "example/Dispatcher", "onInject", "()V", false, "t", 0)));

        assertDoesNotThrow(() -> BytecodeVerifier.verify(
                TransformTestFixture.SAMPLE_CLASS, injected, "t", "mod-a"),
                "注入产出必须通过 CheckClassAdapter + Analyzer 的全部校验");
    }

    @Test
    @DisplayName("宽类型（long/double）注入后仍能通过验证")
    void wideTypeInjectionPassesVerification() {
        TargetMethod target = TargetMethod.of(
                TransformTestFixture.SAMPLE_CLASS, "wideMethod",
                TransformTestFixture.LONG_METHOD_DESC);

        byte[] injected = MiliClassTransformer.apply(
                TransformTestFixture.sampleClass(),
                TransformTestFixture.SAMPLE_CLASS,
                List.of(
                        new MiliClassTransformer.MethodInjection(
                                target, null, InjectionPoint.HEAD, "()V", null, -1,
                                "example/Dispatcher", "onInject", "()V", false, "t", 0),
                        new MiliClassTransformer.MethodInjection(
                                target, null, InjectionPoint.RETURN, "()V", null, -1,
                                "example/Dispatcher", "onInject", "()V", false, "t", 0)));

        assertDoesNotThrow(() -> BytecodeVerifier.verify(
                TransformTestFixture.SAMPLE_CLASS, injected, "t", "mod-a"));
    }

    @Test
    @DisplayName("字段注入产出必须通过验证 —— GETSTATIC 的空栈形态是最易错处")
    void fieldInjectionOutputPassesVerification() {
        byte[] injected = MiliClassTransformer.apply(
                TransformTestFixture.sampleFieldClass(),
                TransformTestFixture.SAMPLE_CLASS,
                List.of(
                        new MiliClassTransformer.MethodInjection(
                                null,
                                org.loader.api.transform.target.TargetField.of(
                                        TransformTestFixture.SAMPLE_CLASS,
                                        "staticField", "Ljava/lang/String;"),
                                InjectionPoint.BEFORE_FIELD_ACCESS,
                                "()V", null, -1,
                                "example/Dispatcher", "onInject", "()V", false, "t", 0),
                        new MiliClassTransformer.MethodInjection(
                                null,
                                org.loader.api.transform.target.TargetField.of(
                                        TransformTestFixture.SAMPLE_CLASS,
                                        "instanceField", "I"),
                                InjectionPoint.AFTER_FIELD_ACCESS,
                                "()V", null, -1,
                                "example/Dispatcher", "onInject", "()V", false, "t", 0)));

        assertDoesNotThrow(() -> BytecodeVerifier.verify(
                TransformTestFixture.SAMPLE_CLASS, injected, "t", "mod-a"));
    }

    @Test
    @DisplayName("验证器对未改动的原始字节码同样通过 —— 不能只测转换后的路径")
    void untouchedBytecodeAlsoPasses() {
        // 这条看似多余，但它守住一个真实风险：
        // 若验证器本身有 bug（例如 SimpleVerifier 参数传错），
        // 它会拒绝一切 —— 那时所有转换都会失败，问题同样难查。
        assertDoesNotThrow(() -> BytecodeVerifier.verify(
                CLASS_NAME, validClass(), null, null));
    }
}