package org.loader.runtime.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.TransformationException;
import org.loader.api.transform.target.TargetMethod;
import org.loader.runtime.transform.asm.MiliClassTransformer;
import org.loader.runtime.transform.verify.BytecodeVerifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InjectionPoint#OVERWRITE} 的字节码层测试。
 *
 * <h2>OVERWRITE 与其它注入点的根本区别</h2>
 * 其它注入点是「往指令流里插东西」，原始指令保留；
 * OVERWRITE 是「把指令流整个换掉」。这个区别决定了它的全部风险：
 * <ul>
 *   <li>它让同方法上的其他注入<b>静默失效</b>（原始方法体没了，插进去的
 *       指令永远不会被执行）；</li>
 *   <li>它让方法内的调试信息、栈映射图、局部变量表全部作废；</li>
 *   <li>它一旦配错回调签名，产出的是<b>结构合法但语义错误</b>的字节码
 *       ——{@code CheckClassAdapter} 抓不到。</li>
 * </ul>
 *
 * <p>因此本测试的重点不是「能覆写」，而是<b>「不能覆写的情况会不会被
 * 明确拒绝」</b>。默认禁用与双重权限限制见
 * {@link org.loader.runtime.transform.security.TransformationGuard}
 * 与 {@code TransformationSecurityTest}。
 */
@DisplayName("OVERWRITE 注入点")
class OverwriteInjectionTest {

    private static final String TARGET_CLASS = "net/minecraft/server/OverwriteTarget";
    private static final String DISPATCH = "org/loader/runtime/transform/OverwriteDispatch";

    /** 覆写目标：一个带 early return 的实例方法。 */
    private static final TargetMethod TARGET = TargetMethod.of(
            TARGET_CLASS, "tickServer", "()V");

    /**
     * 生成带 early return 的目标类。
     *
     * <p>方法体里放了可识别的调用（{@code marker()}）——
     * 覆写后它必须消失。这比断言「字节码变短了」精确得多。
     */
    private static byte[] targetClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                TARGET_CLASS, null, "java/lang/Object", null);

        MethodVisitor init = cw.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // marker(): 被丢弃的原始实现里唯一可识别的痕迹
        MethodVisitor marker = cw.visitMethod(
                Opcodes.ACC_PRIVATE, "marker", "()V", null, null);
        marker.visitCode();
        marker.visitInsn(Opcodes.RETURN);
        marker.visitMaxs(0, 0);
        marker.visitEnd();

        MethodVisitor tick = cw.visitMethod(
                Opcodes.ACC_PUBLIC, "tickServer", "()V", null, null);
        tick.visitCode();
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET_CLASS, "marker", "()V", false);
        // early return：任何「只处理最后一个 RETURN」的错误实现都会漏掉这里
        Label second = new Label();
        tick.visitJumpInsn(Opcodes.GOTO, second);
        tick.visitInsn(Opcodes.RETURN);
        tick.visitLabel(second);
        tick.visitInsn(Opcodes.RETURN);
        tick.visitMaxs(0, 0);
        tick.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MiliClassTransformer.MethodInjection overwrite(
            InjectionPoint point, String callbackDescriptor,
            String owner, String name, boolean isStatic, String id) {
        return new MiliClassTransformer.MethodInjection(
                TARGET, null, point, callbackDescriptor, null, -1,
                owner, name, callbackDescriptor, isStatic, id, 0);
    }

    // ── 基本行为 ────────────────────────────────────────────────────

    @Test
    @DisplayName("原始方法体被完整丢弃 —— 只剩回调调用")
    void originalBodyIsDiscarded() {
        byte[] result = MiliClassTransformer.apply(
                targetClass(), TARGET_CLASS,
                List.of(overwrite(InjectionPoint.OVERWRITE, "()V",
                        DISPATCH, "onOverwrite", false, "ow")));

        // marker 调用必须消失 —— 它是原始方法体存在的唯一证据
        assertEquals(0, countCallsTo(result, TARGET_CLASS, "marker"),
                "OVERWRITE 后原始方法体必须消失。marker() 仍被调用说明"
                        + "原实现并未被丢弃 —— 那不是覆写，只是加了个尾巴。");

        // 回调调用必须存在，且恰好一次
        assertEquals(1, countCallsTo(result, DISPATCH, "onOverwrite"),
                "覆写后的方法体应只包含一次回调调用。");
    }

    @Test
    @DisplayName("不保留原实现的合成副本 —— 不给反射遍历留下幽灵方法")
    void noSyntheticCopyOfOriginal() {
        byte[] result = MiliClassTransformer.apply(
                targetClass(), TARGET_CLASS,
                List.of(overwrite(InjectionPoint.OVERWRITE, "()V",
                        DISPATCH, "onOverwrite", false, "ow")));

        List<String> methods = methodNames(result);
        assertFalse(methods.stream().anyMatch(m -> m.contains("$mili")
                        || m.contains("original") || m.contains("Original")),
                "OVERWRITE 不应保留原实现的合成副本（实际方法: " + methods + "）。\n"
                        + "保留副本会带来两个隐蔽问题："
                        + "① 反射遍历方法的 Mod 会「注入一个永不被调用的方法」，且不报错；"
                        + "② getDeclaredMethods() 结果被污染，影响依赖反射的 Mod。\n"
                        + "需要「改前后都跑」请用 HEAD + RETURN。");
    }

    @Test
    @DisplayName("覆写结果通过三层字节码验证")
    void outputPassesVerification() {
        byte[] result = MiliClassTransformer.apply(
                targetClass(), TARGET_CLASS,
                List.of(overwrite(InjectionPoint.OVERWRITE, "()V",
                        DISPATCH, "onOverwrite", false, "ow")));

        assertDoesNotThrow(() -> BytecodeVerifier.verify(
                TARGET_CLASS, result, "ow", null));
    }

    // ── 参数转发 ────────────────────────────────────────────────────

    @Test
    @DisplayName("实例方法覆写会转发 this")
    void instanceMethodForwardsThis() {
        byte[] result = MiliClassTransformer.apply(
                targetClass(), TARGET_CLASS,
                List.of(overwrite(InjectionPoint.OVERWRITE, "()V",
                        DISPATCH, "onOverwrite", false, "ow")));

        // 回调必须是 INVOKESPECIAL/INVOKEVIRTUAL —— static 回调拿不到 this
        int[] opcodes = {Opcodes.INVOKESTATIC};
        new ClassReader(result).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                if (!TARGET.name().equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String o, String n,
                                                String d, boolean itf) {
                        if (DISPATCH.equals(o)) {
                            opcodes[0] = opcode;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);

        assertTrue(opcodes[0] == Opcodes.INVOKESPECIAL
                        || opcodes[0] == Opcodes.INVOKEVIRTUAL,
                "实例方法覆写必须用非静态调用形式转发 this，实际 opcode="
                        + opcodes[0] + "（" + (opcodes[0] == Opcodes.INVOKESTATIC
                        ? "INVOKESTATIC 拿不到 this" : "?") + "）");
    }

    @Test
    @DisplayName("静态方法覆写用 INVOKESTATIC")
    void staticMethodUsesStaticInvoke() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                TARGET_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tickServer", "(I)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] result = MiliClassTransformer.apply(cw.toByteArray(), TARGET_CLASS,
                List.of(new MiliClassTransformer.MethodInjection(
                        TargetMethod.of(TARGET_CLASS, "tickServer", "(I)V"),
                        null, InjectionPoint.OVERWRITE, "(I)V", null, -1,
                        DISPATCH, "onOverwriteStatic", "(I)V", true, "ow", 0)));

        final int[] opcode = {-1};
        new ClassReader(result).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                if (!"tickServer".equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String o, String n,
                                                String d, boolean itf) {
                        if (DISPATCH.equals(o)) {
                            opcode[0] = op;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);

        assertEquals(Opcodes.INVOKESTATIC, opcode[0],
                "静态方法的覆写回调必须是静态调用形式");
        assertDoesNotThrow(() -> BytecodeVerifier.verify(TARGET_CLASS, result, "ow", null));
    }

    // ── 必须明确拒绝的情况 ──────────────────────────────────────────

    @Test
    @DisplayName("OVERWRITE 与 HEAD 共存必须报错 —— 否则 HEAD 静默失效")
    void overwriteWithHeadMustFailLoudly() {
        // 这是 OVERWRITE 最危险的组合：原始方法体被丢弃后，
        // HEAD 注入的指令永远不会被执行，而调用方得不到任何提示。
        List<MiliClassTransformer.MethodInjection> injections = List.of(
                overwrite(InjectionPoint.OVERWRITE, "()V",
                        DISPATCH, "onOverwrite", true, "ow"),
                new MiliClassTransformer.MethodInjection(
                        TARGET, null, InjectionPoint.HEAD, "()V", null, -1,
                        DISPATCH, "onHead", "()V", true, "head-mod", 10));

        TransformationException e = assertThrows(TransformationException.class,
                () -> MiliClassTransformer.apply(
                        targetClass(), TARGET_CLASS, injections),
                "OVERWRITE 与其它注入共存必须报错。"
                        + "若静默通过，Mod 会看到「注入成功」而实际上从未执行 —— "
                        + "这与本系统要消灭的静默失效是同一类问题。");
        assertTrue(e.getMessage().contains("OVERWRITE"),
                "错误信息应点明冲突涉及 OVERWRITE，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("多个 OVERWRITE 互相冲突")
    void multipleOverwritesConflict() {
        List<MiliClassTransformer.MethodInjection> injections = List.of(
                overwrite(InjectionPoint.OVERWRITE, "()V",
                        DISPATCH, "onOverwriteA", true, "ow-a"),
                overwrite(InjectionPoint.OVERWRITE, "()V",
                        DISPATCH, "onOverwriteB", true, "ow-b"));

        assertThrows(
                org.loader.api.transform.TransformationConflictException.class,
                () -> MiliClassTransformer.apply(
                        targetClass(), TARGET_CLASS, injections),
                "同一方法上两个 OVERWRITE 必须报冲突 —— 同时应用等于随机丢弃一个。");
    }

    @Test
    @DisplayName("回调签名与原方法不匹配必须被拒绝")
    void mismatchedCallbackDescriptorRejected() {
        // 签名不匹配时若放行，参数转发会错位，产出结构合法但语义错误的
        // 字节码 —— 校验器抓不到，只会在游戏里表现为诡异行为。
        assertThrows(TransformationException.class,
                () -> MiliClassTransformer.apply(
                        targetClass(), TARGET_CLASS,
                        List.of(overwrite(InjectionPoint.OVERWRITE, "(I)V",
                                DISPATCH, "onOverwrite", false, "ow"))),
                "回调签名必须与原方法完全一致，否则参数转发错位。");
    }

    @Test
    @DisplayName("静态目标配实例回调必须被拒绝")
    void staticTargetWithInstanceCallbackRejected() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                TARGET_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tickServer", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        assertThrows(TransformationException.class,
                () -> MiliClassTransformer.apply(cw.toByteArray(), TARGET_CLASS,
                        List.of(new MiliClassTransformer.MethodInjection(
                                TargetMethod.of(TARGET_CLASS, "tickServer", "()V"),
                                null, InjectionPoint.OVERWRITE, "()V", null, -1,
                                DISPATCH, "onOverwrite", "()V",
                                false,   // 声明为实例回调
                                "ow", 0))),
                "静态方法的回调无法接收 this，必须显式拒绝。");
    }

    @Test
    @DisplayName("缺少回调 owner/name 必须被拒绝")
    void missingCallbackRejected() {
        assertThrows(TransformationException.class,
                () -> MiliClassTransformer.apply(
                        targetClass(), TARGET_CLASS,
                        List.of(overwrite(InjectionPoint.OVERWRITE, "()V",
                                null, "onOverwrite", true, "ow"))),
                "缺少回调目标时必须报错 —— 否则生成的字节码无法链接，"
                        + "报错会推迟到 defineClass 之后且指向 Minecraft。");
    }

    @Test
    @DisplayName("OVERWRITE 误配字段目标必须被拒绝")
    void overwriteWithFieldRejected() {
        assertThrows(TransformationException.class,
                () -> MiliClassTransformer.apply(
                        targetClass(), TARGET_CLASS,
                        List.of(new MiliClassTransformer.MethodInjection(
                                TARGET,
                                org.loader.api.transform.target.TargetField.of(
                                        TARGET_CLASS, "f", "I"),
                                InjectionPoint.OVERWRITE, "()V", null, -1,
                                DISPATCH, "onOverwrite", "()V", true, "ow", 0))),
                "OVERWRITE 作用于方法体，指定字段目标说明声明有误 —— "
                        + "必须报错而不是忽略字段。");
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private static int countCallsTo(byte[] bytes, String owner, String name) {
        final int[] count = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String desc,
                                             String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String o, String mn,
                                                String md, boolean itf) {
                        if (owner.equals(o) && name.equals(mn)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static List<String> methodNames(byte[] bytes) {
        List<String> names = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String desc,
                                             String sig, String[] ex) {
                names.add(n + desc);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return names;
    }
}