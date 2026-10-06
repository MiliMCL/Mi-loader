package org.loader.runtime.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.TransformationException;
import org.loader.api.transform.TransformationConflictException;
import org.loader.api.transform.TransformationTargetNotFoundException;
import org.loader.api.transform.annotation.MiliInject;
import org.loader.api.transform.annotation.MiliTransformer;
import org.loader.api.transform.callback.InjectionContext;
import org.loader.api.transform.target.TargetInvocation;
import org.loader.api.transform.target.TargetMethod;
import org.loader.runtime.transform.asm.MiliClassTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 可取消注入 / MODIFY_CONSTANT / 实例上下文的端到端测试。
 *
 * <p>方法与 {@code InjectionPointTest} 一致：ASM 生成合成目标类 →
 * 引擎转换 → defineClass → <b>实际调用</b>并断言行为。
 * 它们覆盖的失效模式在编译期不可见、在运行期不可逆：
 * 取消分支生成错一处就是 VerifyError 或静默不取消。
 */
class CancellableAndConstantTest implements Opcodes {

    // ── 回调持有者（由转换后的合成类引用，父加载器可见） ──────────────────────

    public static class Cb {
        /** 每个用例自行设置；回调据此决定是否取消。 */
        public static volatile boolean cancel = true;
        /** 最近一次回调收到的宿主实例。 */
        public static volatile Object lastTarget;

        public static void headCancel(InjectionContext ctx) {
            if (cancel) {
                ctx.cancel();
            }
        }

        public static int const500() {
            return 500;
        }

        public static String constReplaced() {
            return "replaced";
        }

        public static void grabTarget(InjectionContext ctx) {
            lastTarget = ctx.target();
        }
    }

    private static final String CB = Cb.class.getName().replace('.', '/');

    // ── 合成类生成 ───────────────────────────────────────────────────────────

    /** {@code static void run() { sideEffect = 1; }} —— void 可取消 HEAD。 */
    private static byte[] voidHeadTarget() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/synth/VoidHead",
                null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "sideEffect", "I", null, null).visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(ICONST_1);
        mv.visitFieldInsn(PUTSTATIC, "test/synth/VoidHead", "sideEffect", "I");
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static int val() { return 42; }} —— int 可取消 HEAD。 */
    private static byte[] intHeadTarget() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/synth/IntHead",
                null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "val", "()I", null, null);
        mv.visitCode();
        mv.visitLdcInsn(42);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static int add(int,int)} + {@code static int call() { return add(2,3); }}。 */
    private static byte[] invokeCancelTarget() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/synth/InvokeCancel",
                null, "java/lang/Object", null);

        MethodVisitor add = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                "add", "(II)I", null, null);
        add.visitCode();
        add.visitVarInsn(ILOAD, 0);
        add.visitVarInsn(ILOAD, 1);
        add.visitInsn(IADD);
        add.visitInsn(IRETURN);
        add.visitMaxs(2, 2);
        add.visitEnd();

        MethodVisitor call = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                "call", "()I", null, null);
        call.visitCode();
        call.visitLdcInsn(2);
        call.visitLdcInsn(3);
        call.visitMethodInsn(INVOKESTATIC, "test/synth/InvokeCancel",
                "add", "(II)I", false);
        call.visitInsn(IRETURN);
        call.visitMaxs(2, 0);
        call.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static int threshold() { return 100; }} —— MODIFY_CONSTANT。 */
    private static byte[] constantTarget() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/synth/Constant",
                null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                "threshold", "()I", null, null);
        mv.visitCode();
        mv.visitLdcInsn(100);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 实例方法 {@code void set()} —— target() 应返回 this。 */
    private static byte[] instanceTarget() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/synth/Instance",
                null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC, "f", "I", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "set", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        // ICONST 只到 5；7 必须用 BIPUSH —— 不是可互换的简写。
        mv.visitIntInsn(BIPUSH, 7);
        mv.visitFieldInsn(PUTFIELD, "test/synth/Instance", "f", "I");
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 静态方法 {@code static int val()} —— target() 应为 null。 */
    private static byte[] staticCtxTarget() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/synth/StaticCtx",
                null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "val", "()I", null, null);
        mv.visitCode();
        mv.visitLdcInsn(42);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Class<?> define(String name, byte[] bytes) {
        return new ClassLoader(CancellableAndConstantTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.define();
    }

    // ── 可取消 HEAD ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("可取消 HEAD：void 方法取消后副作用不执行")
    void cancellableHeadVoid() throws Exception {
        byte[] transformed = MiliClassTransformer.apply(
                voidHeadTarget(), "test/synth/VoidHead",
                java.util.List.of(headInject("run", "()V", true)));

        Class<?> c = define("test.synth.VoidHead", transformed);
        Cb.cancel = true;
        c.getMethod("run").invoke(null);
        assertEquals(0, c.getField("sideEffect").getInt(null),
                "取消后方法体不得执行");

        Cb.cancel = false;
        c.getMethod("run").invoke(null);
        assertEquals(1, c.getField("sideEffect").getInt(null),
                "不取消时方法体照常执行");
    }

    @Test
    @DisplayName("可取消 HEAD：int 方法取消后返回类型默认值 0")
    void cancellableHeadInt() throws Exception {
        byte[] transformed = MiliClassTransformer.apply(
                intHeadTarget(), "test/synth/IntHead",
                java.util.List.of(headInject("val", "()I", true)));

        Class<?> c = define("test.synth.IntHead", transformed);
        Cb.cancel = true;
        assertEquals(0, c.getMethod("val").invoke(null),
                "取消后 int 方法返回 0，而不是原值 42");

        Cb.cancel = false;
        assertEquals(42, c.getMethod("val").invoke(null));
    }

    // ── 可取消 BEFORE_INVOKE ─────────────────────────────────────────────────

    @Test
    @DisplayName("可取消 BEFORE_INVOKE：跳过调用并返回默认值")
    void cancellableBeforeInvoke() throws Exception {
        TargetMethod called = TargetMethod.of(
                "test/synth/InvokeCancel", "add", "(II)I");
        byte[] transformed = MiliClassTransformer.apply(
                invokeCancelTarget(), "test/synth/InvokeCancel",
                java.util.List.of(new MiliClassTransformer.MethodInjection(
                        TargetMethod.of("test/synth/InvokeCancel", "call", "()I"),
                        null, InjectionPoint.BEFORE_INVOKE, "(L"
                        + InjectionContext.class.getName().replace('.', '/')
                        + ";)V",
                        TargetInvocation.at(called, 0), -1,
                        CB, "headCancel", "(L"
                        + InjectionContext.class.getName().replace('.', '/')
                        + ";)V",
                        true, "test-cancel-invoke", 0, true, null)));

        Class<?> c = define("test.synth.InvokeCancel", transformed);
        Cb.cancel = true;
        assertEquals(0, c.getMethod("call").invoke(null),
                "取消后 add 调用被跳过，返回默认值 0");

        Cb.cancel = false;
        assertEquals(5, c.getMethod("call").invoke(null),
                "不取消时 add(2,3) 照常返回 5");
    }

    // ── MODIFY_CONSTANT ──────────────────────────────────────────────────────

    @Test
    @DisplayName("MODIFY_CONSTANT：int 常量被替换为回调返回值")
    void modifyConstantInt() throws Exception {
        byte[] transformed = MiliClassTransformer.apply(
                constantTarget(), "test/synth/Constant",
                java.util.List.of(new MiliClassTransformer.MethodInjection(
                        TargetMethod.of("test/synth/Constant", "threshold", "()I"),
                        null, InjectionPoint.MODIFY_CONSTANT, "()I",
                        null, -1,
                        CB, "const500", "()I",
                        true, "test-const", 0, false, "100")));

        Class<?> c = define("test.synth.Constant", transformed);
        assertEquals(500, c.getMethod("threshold").invoke(null),
                "常量 100 必须被替换为 500");
    }

    @Test
    @DisplayName("MODIFY_CONSTANT：声明的常量不存在时生成期报错")
    void modifyConstantUnmatchedFails() {
        assertThrows(TransformationException.class, () ->
                MiliClassTransformer.apply(
                        constantTarget(), "test/synth/Constant",
                        java.util.List.of(new MiliClassTransformer.MethodInjection(
                                TargetMethod.of("test/synth/Constant", "threshold", "()I"),
                                null, InjectionPoint.MODIFY_CONSTANT, "()I",
                                null, -1,
                                CB, "const500", "()I",
                                true, "test-const", 0, false, "999"))));
    }

    @Test
    @DisplayName("MODIFY_CONSTANT：同一常量被两个注入命中 → 冲突异常")
    void modifyConstantConflict() {
        assertThrows(TransformationConflictException.class, () ->
                MiliClassTransformer.apply(
                        constantTarget(), "test/synth/Constant",
                        java.util.List.of(
                                constInjection("test-const-a"),
                                constInjection("test-const-b"))));
    }

    private static MiliClassTransformer.MethodInjection constInjection(String id) {
        return new MiliClassTransformer.MethodInjection(
                TargetMethod.of("test/synth/Constant", "threshold", "()I"),
                null, InjectionPoint.MODIFY_CONSTANT, "()I",
                null, -1,
                CB, "const500", "()I",
                true, id, 0, false, "100");
    }

    // ── 实例上下文 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("实例方法上的回调通过 ctx.target() 拿到宿主实例")
    void instanceContext() throws Exception {
        byte[] transformed = MiliClassTransformer.apply(
                instanceTarget(), "test/synth/Instance",
                java.util.List.of(new MiliClassTransformer.MethodInjection(
                        TargetMethod.of("test/synth/Instance", "set", "()V"),
                        null, InjectionPoint.HEAD,
                        "(L" + InjectionContext.class.getName().replace('.', '/')
                                + ";)V",
                        null, -1,
                        CB, "grabTarget", "(L"
                        + InjectionContext.class.getName().replace('.', '/')
                        + ";)V",
                        true, "test-target", 0, false, null)));

        Class<?> c = define("test.synth.Instance", transformed);
        Object instance = c.getDeclaredConstructor().newInstance();
        Cb.lastTarget = null;
        c.getMethod("set").invoke(instance);

        assertSame(instance, Cb.lastTarget,
                "ctx.target() 必须是调用该方法的实例本身");
        assertEquals(7, c.getField("f").getInt(instance),
                "方法体照常执行");

        // 静态方法上 target() 必须为 null（ACONST_NULL 路径）
        Cb.lastTarget = new Object();
        byte[] staticTransformed = MiliClassTransformer.apply(
                staticCtxTarget(), "test/synth/StaticCtx",
                java.util.List.of(new MiliClassTransformer.MethodInjection(
                        TargetMethod.of("test/synth/StaticCtx", "val", "()I"),
                        null, InjectionPoint.HEAD,
                        "(L" + InjectionContext.class.getName().replace('.', '/')
                                + ";)V",
                        null, -1,
                        CB, "grabTarget", "(L"
                        + InjectionContext.class.getName().replace('.', '/')
                        + ";)V",
                        true, "test-target-static", 0, false, null)));
        Class<?> c2 = define("test.synth.StaticCtx", staticTransformed);
        c2.getMethod("val").invoke(null);
        assertNull(Cb.lastTarget, "静态方法上 ctx.target() 必须为 null");
    }

    private static MiliClassTransformer.MethodInjection headInject(
            String method, String descriptor, boolean cancellable) {
        String ctxDesc = "(L" + InjectionContext.class.getName().replace('.', '/')
                + ";)V";
        return new MiliClassTransformer.MethodInjection(
                TargetMethod.of("test/synth/" + (descriptor.equals("()V")
                        ? "VoidHead" : "IntHead"), method, descriptor),
                null, InjectionPoint.HEAD, ctxDesc,
                null, -1,
                CB, "headCancel", ctxDesc,
                true, "test-head-" + method, 0, cancellable, null);
    }

    // ── 扫描器校验 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("扫描器拒绝在 RETURN 上声明 cancellable")
    void scannerRejectsCancellableAtReturn() {
        assertThrows(TransformationException.class, () ->
                org.loader.runtime.transform.engine.AnnotationTransformerScanner.scan(
                        BadCancellableHook.class, "26.2", null));
    }

    @Test
    @DisplayName("扫描器解析 MODIFY_CONSTANT 的常量声明")
    void scannerParsesConstant() {
        var transformer = org.loader.runtime.transform.engine.AnnotationTransformerScanner
                .scan(ConstHook.class, "26.2", null);
        assertNotNull(transformer);
        assertEquals(1, transformer.callbacks().size());
        var spec = transformer.callbacks().get(0);
        assertEquals(InjectionPoint.MODIFY_CONSTANT, spec.point());
        assertEquals("100", spec.constant());
    }

    @MiliTransformer(target = "minecraft.server.tick")
    public static class BadCancellableHook {
        @MiliInject(at = InjectionPoint.RETURN, cancellable = true)
        public static void cb() {
        }
    }

    @MiliTransformer(target = "minecraft.server.tick")
    public static class ConstHook {
        @MiliInject(at = InjectionPoint.MODIFY_CONSTANT, constant = "100")
        public static int cb() {
            return 500;
        }
    }
}
