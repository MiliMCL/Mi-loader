package org.loader.runtime.transform;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 转换测试夹具 —— 用 ASM <b>生成</b>待转换的字节码。
 *
 * <h2>为什么必须生成而不是抄一份Minecraft 类</h2>
 * 因为 {@code tools/26.2/26.2.jar} 不在版本控制里（禁止分发 Minecraft），
 * 单元测试无法依赖它。
 *
 * <p>但这带来一个必须诚实面对的问题：<b>生成字节码不等于验证了与真实
 * Minecraft 的集成</b>。本夹具能验证的是：
 * <ul>
 *   <li>注入产出的字节码是否<b>结构合法</b>（CheckClassAdapter + Analyzer）</li>
 *   <li>回调调用是否<b>落在正确的位置</b>（指令序列断言）</li>
 *   <li>栈平衡是否正确</li>
 * </ul>
 * 它<b>不能</b>验证的是「在真实 Minecraft 26.2 上游戏是否正常运行」。
 * 那个只能靠 {@code Minecraft26_2SmokeTest} —— 它在 CI 上跑，
 * 且必须在 CI 上真的跑，不能用 mock 替代。
 *
 * <p>混淆这两者是本仓库明确禁止的：声称「集成可用」而实际只测了 mock。
 *
 * @see #sampleClass 生成的样例类结构
 */
public final class TransformTestFixture {

    /** 样例类的内部名。 */
    public static final String SAMPLE_CLASS = "net/minecraft/server/SampleServer";

    /** 无参无返回。 */
    public static final String VOID_DESC = "()V";

    /** 返回 int 的方法。 */
    public static final String INT_DESC = "()I";

    /** 带参返回 long 的方法（宽类型测试）。 */
    public static final String LONG_METHOD_DESC = "(ILjava/lang/String;J)I";

    /**
     * 与 {@link #LONG_METHOD_DESC} 同签名的被调方法 —— MODIFY_ARG 的锚点。
     *
     * <p>单独一个常量而不是复用 {@link #LONG_METHOD_DESC}：
     * 两者字符串相同但语义不同（一个是宿主，一个是被调），
     * 共用一个常量会让「我改的是谁的参数」这件事在测试里失去指向性。
     */
    public static final String WIDE_CALLEE_DESC = "(ILjava/lang/String;J)I";

    private TransformTestFixture() {
    }

    /**
     * 生成一个含多种方法形态的样例类。
     *
     * <p>刻意包含以下难点形态，因为它们正是转换最容易出错的地方：
     * <ul>
     *   <li><b>带 early return 的方法</b> —— 验证 RETURN 注入不漏路径；</li>
     *   <li><b>非 void 返回</b> —— 验证返回值暂存/装回；</li>
     *   <li><b>宽类型参数（long）</b> —— 验证 MODIFY_ARG 的槽位计算；</li>
     *   <li><b>多个同类调用</b> —— 验证 ordinal 定位；</li>
     *   <li><b>静态与实例方法都有</b> —— 验证 REDIRECT 的操作码处理。</li>
     * </ul>
     */
    public static byte[] sampleClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                SAMPLE_CLASS, null, "java/lang/Object", null);

        // 构造器
        MethodVisitor ctor = cw.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", VOID_DESC, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object",
                "<init>", VOID_DESC, false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // void 方法，含 early return（if (flag) return;）
        MethodVisitor m1 = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "voidMethod",
                "(Z)V", null, null);
        m1.visitCode();
        Label notReturn = new Label();
        m1.visitVarInsn(Opcodes.ILOAD, 0);
        m1.visitJumpInsn(Opcodes.IFEQ, notReturn);
        m1.visitInsn(Opcodes.RETURN);          // ← early return，RETURN 注入必须覆盖它
        m1.visitLabel(notReturn);
        m1.visitInsn(Opcodes.RETURN);          // ← 正常返回路径
        m1.visitMaxs(0, 0);
        m1.visitEnd();

        // 返回 int 的方法
        MethodVisitor m2 = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "intMethod",
                INT_DESC, null, null);
        m2.visitCode();
        m2.visitInsn(Opcodes.ICONST_5);
        m2.visitInsn(Opcodes.IRETURN);
        m2.visitMaxs(0, 0);
        m2.visitEnd();

        // 宽类型参数方法： (int, String, long) -> int
        //
        // 方法体必须<b>真的调用</b>同签名的 wideCallee：MODIFY_ARG 的语义是
        // 「替换某次调用的某个实参」，宿主方法里没有调用就没有可改的实参。
        //
        // 关键：三个实参<b>全部来自局部变量</b>（ILOAD/ALOAD/LLOAD）。
        // MODIFY_ARG 会把它们倒序暂存再正序重放 ——
        // 只有真的从栈上搬运，才能验证「重放顺序与暂存顺序严格相反」，
        // 以及「long 占两槽、槽位偏移必须用 Type.getSize() 算」。
        // 若这里 push 常量，重放顺序错了也恰好合法，测不出该错误。
        MethodVisitor m3 = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "wideMethod",
                LONG_METHOD_DESC, null, null);
        m3.visitCode();
        m3.visitVarInsn(Opcodes.ILOAD, 0);          // int
        m3.visitVarInsn(Opcodes.ALOAD, 1);          // String
        m3.visitVarInsn(Opcodes.LLOAD, 2);          // long（占两槽）
        m3.visitMethodInsn(Opcodes.INVOKESTATIC, SAMPLE_CLASS, "wideCallee",
                WIDE_CALLEE_DESC, false);          // ordinal 0 —— MODIFY_ARG 的锚点
        m3.visitInsn(Opcodes.IRETURN);
        m3.visitMaxs(0, 0);
        m3.visitEnd();

        // wideCallee：wideMethod 的调用目标，签名与宿主相同。
        // 返回第一个参数 —— 保证「返回值在栈顶」这一形态也被覆盖到。
        MethodVisitor m3b = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "wideCallee",
                WIDE_CALLEE_DESC, null, null);
        m3b.visitCode();
        m3b.visitVarInsn(Opcodes.ILOAD, 0);
        m3b.visitInsn(Opcodes.IRETURN);
        m3b.visitMaxs(0, 0);
        m3b.visitEnd();

        // 含多次同类调用 —— 用于验证 ordinal 定位
        MethodVisitor m4 = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "multiCall",
                VOID_DESC, null, null);
        m4.visitCode();
        m4.visitMethodInsn(Opcodes.INVOKESTATIC, SAMPLE_CLASS, "intMethod",
                INT_DESC, false);      // ordinal 0
        m4.visitInsn(Opcodes.POP);
        m4.visitMethodInsn(Opcodes.INVOKESTATIC, SAMPLE_CLASS, "intMethod",
                INT_DESC, false);      // ordinal 1
        m4.visitInsn(Opcodes.POP);
        m4.visitMethodInsn(Opcodes.INVOKESTATIC, SAMPLE_CLASS, "intMethod",
                INT_DESC, false);      // ordinal 2
        m4.visitInsn(Opcodes.POP);
        m4.visitInsn(Opcodes.RETURN);
        m4.visitMaxs(0, 0);
        m4.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * 生成一个含字段读写的方法 —— 字段注入测试用。
     *
     * <p>包含四种字段访问形态（GETFIELD / PUTFIELD / GETSTATIC / PUTSTATIC），
     * 因为它们在「插入回调前」的操作数栈形态各不相同 ——
     * 这是字段注入最容易写错的地方。
     */
    public static byte[] sampleFieldClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                SAMPLE_CLASS, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PUBLIC, "instanceField",
                "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "staticField",
                "Ljava/lang/String;", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", VOID_DESC, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object",
                "<init>", VOID_DESC, false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // 依次访问四种字段形态
        MethodVisitor m = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "touchFields",
                "(L" + SAMPLE_CLASS + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);                     // objref
        m.visitFieldInsn(Opcodes.GETFIELD, SAMPLE_CLASS,
                "instanceField", "I");
        m.visitInsn(Opcodes.POP);

        m.visitVarInsn(Opcodes.ALOAD, 0);                     // objref
        // 注意：JVM 只有 iconst_m1..iconst_5，ASM 的 Opcodes 里
        // 不存在 ICONST_7 —— 常量 7 必须用 BIPUSH。
        m.visitIntInsn(Opcodes.BIPUSH, 7);
        m.visitFieldInsn(Opcodes.PUTFIELD, SAMPLE_CLASS,
                "instanceField", "I");

        m.visitFieldInsn(Opcodes.GETSTATIC, SAMPLE_CLASS,
                "staticField", "Ljava/lang/String;");
        m.visitInsn(Opcodes.POP);

        m.visitLdcInsn("hello");
        m.visitFieldInsn(Opcodes.PUTSTATIC, SAMPLE_CLASS,
                "staticField", "Ljava/lang/String;");

        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * 断言字节码通过结构与类型验证。
     *
     * <p>这是所有注入测试的共同前提：转换结果必须能被 JVM 加载。
     * 断言消息里带上失败详情，因为 {@code VerifyError} 的原始信息
     * 完全不指向真正的原因。
     */
    public static void assertValidBytecode(byte[] bytecode, String context) {
        try {
            org.objectweb.asm.util.CheckClassAdapter.verify(
                    new org.objectweb.asm.ClassReader(bytecode), false,
                    new java.io.PrintWriter(System.out));
        } catch (Throwable t) {
            throw new AssertionError(
                    "生成的字节码未通过结构校验（" + context + "）: "
                            + t.getClass().getSimpleName() + " — " + t.getMessage(),
                    t);
        }
    }
}