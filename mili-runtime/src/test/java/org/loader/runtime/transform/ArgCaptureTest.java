package org.loader.runtime.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationEnvironment;
import org.loader.api.transform.TransformationException;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.TransformationResult;
import org.loader.api.transform.annotation.MiliInject;
import org.loader.api.transform.annotation.MiliTransformer;
import org.loader.api.transform.callback.InjectionContext;
import org.loader.api.transform.target.TargetInvocation;
import org.loader.api.transform.target.TargetMethod;
import org.loader.runtime.transform.asm.MiliClassTransformer;
import org.loader.runtime.transform.engine.AnnotationTransformerScanner;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实参捕获（typed positional capture）的端到端测试。
 *
 * <p>方法与 {@code CancellableAndConstantTest} 一致：ASM 生成合成目标类 →
 * 引擎转换 → defineClass → <b>实际调用</b>并断言行为。
 *
 * <p>覆盖的失效模式：
 * <ul>
 *   <li>槽位计算错误（实例 this 偏移、long/double 双槽推进）——
 *       生成结构合法但读到错位数据的字节码，VerifyError 或值错误；</li>
 *   <li>装箱/子类类型在扫描器与引擎两处都必须被拒绝；</li>
 *   <li>RETURN 捕获读到「当前值」的文档化语义。</li>
 * </ul>
 */
class ArgCaptureTest implements Opcodes {

    // ── 回调持有者（转换后的合成类引用，父加载器可见） ────────────────────

    public static class Cap {
        public static volatile long sum;
        public static volatile Object ctxRef;
        public static volatile long capturedA;
        public static volatile long capturedB;
        public static volatile double capturedD;
        public static volatile boolean supplierValue;
        public static volatile Object capturedSupplier;

        public static void headTwoInts(int a, int b) {
            sum = a + (long) b;
        }

        public static void headCtxTwoInts(InjectionContext ctx, int a, int b) {
            ctxRef = ctx;
            sum = a + (long) b;
        }

        public static void headOneInt(int x) {
            sum = x;
        }

        public static void headWide(int a, long b, double d) {
            capturedA = a;
            capturedB = b;
            capturedD = d;
        }

        public static void headSlot(int a) {
            sum = a;
        }

        public static void returnSlot(int a) {
            sum = a;
        }

        public static int modReturn(int v) {
            return v * 10;
        }

        public static void serverHead(BooleanSupplier keepGoing) {
            supplierValue = keepGoing.getAsBoolean();
            capturedSupplier = keepGoing;
        }
    }

    private static final String CB = Cap.class.getName().replace('.', '/');
    private static final String CTX_DESC =
            "L" + InjectionContext.class.getName().replace('.', '/') + ";";

    // ── 合成宿主 ─────────────────────────────────────────────────────────────

    /** {@code static int add(int a, int b) { return a + b; }}。 */
    private static byte[] twoIntTarget() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/synth/CapAdd",
                null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                "add", "(II)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ILOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitInsn(IADD);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 实例方法 {@code int scale(int x) { return x * 3; }} —— this 占槽 0。 */
    private static byte[] instanceScaleTarget() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/synth/CapInstance",
                null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC,
                "scale", "(I)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ILOAD, 1);
        mv.visitInsn(ICONST_3);
        mv.visitInsn(IMUL);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static double mix(int a, long b, double d, String s) { return d; }}
     *  —— 双槽推进：d 的正确槽位是 3（若按「每参数 +1」会算成 2）。 */
    private static byte[] wideTarget() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/synth/CapWide",
                null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                "mix", "(IJDLjava/lang/String;)D", null, null);
        mv.visitCode();
        mv.visitVarInsn(DLOAD, 3);
        mv.visitInsn(DRETURN);
        mv.visitMaxs(2, 6);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code static int bump(int a) { a = a + 1; return a; }}
     * —— 方法体重写参数槽，用于验证 HEAD（入口值）与
     * RETURN（当前值）的读取语义差异。
     */
    private static byte[] slotMutationTarget(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName,
                null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                "bump", "(I)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ILOAD, 0);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ISTORE, 0);
        mv.visitVarInsn(ILOAD, 0);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static int identity(int v) { return v; }} —— MODIFY_RETURN。 */
    private static byte[] modifyReturnTarget() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/synth/CapModRet",
                null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                "identity", "(I)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ILOAD, 0);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * 模拟 {@code MinecraftServer#tickServer(BooleanSupplier)}：
     * {@code void tickServer(BooleanSupplier bs) { if (bs.getAsBoolean()) counter++; }}
     * —— 实例方法（this 槽 0，bs 槽 1），供注解全链路测试使用。
     */
    private static byte[] fakeServerTarget() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/synth/FakeServer",
                null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "counter", "I", null, null)
                .visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC,
                "tickServer", "(Ljava/util/function/BooleanSupplier;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE,
                "java/util/function/BooleanSupplier", "getAsBoolean", "()Z", true);
        Label skip = new Label();
        mv.visitJumpInsn(IFEQ, skip);
        mv.visitFieldInsn(GETSTATIC, "test/synth/FakeServer", "counter", "I");
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitFieldInsn(PUTSTATIC, "test/synth/FakeServer", "counter", "I");
        mv.visitLabel(skip);
        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Class<?> define(String name, byte[] bytes) {
        return new ClassLoader(ArgCaptureTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.define();
    }

    private static MiliClassTransformer.MethodInjection headCapture(
            String owner, String method, String hostDescriptor,
            String callbackDescriptor, String callbackName) {
        return new MiliClassTransformer.MethodInjection(
                TargetMethod.of(owner, method, hostDescriptor),
                null, InjectionPoint.HEAD, callbackDescriptor,
                null, -1,
                CB, callbackName, callbackDescriptor,
                true, "test-capture-" + callbackName, 0, false, null);
    }

    // ── HEAD 捕获 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HEAD 捕获：static 宿主的两个实参按位正确")
    void headCapturesTwoInts() throws Exception {
        byte[] transformed = MiliClassTransformer.apply(
                twoIntTarget(), "test/synth/CapAdd",
                List.of(headCapture("test/synth/CapAdd", "add", "(II)I",
                        "(II)V", "headTwoInts")));

        Class<?> c = define("test.synth.CapAdd", transformed);
        Cap.sum = 0;
        assertEquals(5, c.getMethod("add", int.class, int.class)
                .invoke(null, 2, 3), "方法体照常执行");
        assertEquals(5, Cap.sum, "回调收到的实参必须是 (2, 3)");
    }

    @Test
    @DisplayName("HEAD 捕获：ctx 与实参共存时各自正确")
    void headCapturesWithContext() throws Exception {
        byte[] transformed = MiliClassTransformer.apply(
                twoIntTarget(), "test/synth/CapAdd",
                List.of(headCapture("test/synth/CapAdd", "add", "(II)I",
                        "(" + CTX_DESC + "II)V", "headCtxTwoInts")));

        Class<?> c = define("test.synth.CapAdd", transformed);
        Cap.sum = 0;
        Cap.ctxRef = null;
        c.getMethod("add", int.class, int.class).invoke(null, 2, 3);
        assertEquals(5, Cap.sum);
        assertNotNull(Cap.ctxRef, "ctx 必须被构造并传入");
    }

    @Test
    @DisplayName("实例方法捕获：this 占槽 0，实参从槽 1 读取")
    void instanceCaptureSkipsThis() throws Exception {
        byte[] transformed = MiliClassTransformer.apply(
                instanceScaleTarget(), "test/synth/CapInstance",
                List.of(headCapture("test/synth/CapInstance", "scale", "(I)I",
                        "(I)V", "headOneInt")));

        Class<?> c = define("test.synth.CapInstance", transformed);
        Object instance = c.getDeclaredConstructor().newInstance();
        Cap.sum = 0;
        assertEquals(12, c.getMethod("scale", int.class).invoke(instance, 4),
                "方法体照常执行");
        assertEquals(4, Cap.sum,
                "回调收到的必须是实参 4，而不是 this 或错位数据");
    }

    @Test
    @DisplayName("宽类型捕获：long/double 双槽推进（d 的槽必须是 3）")
    void wideTypesAdvanceDoubleSlots() throws Exception {
        byte[] transformed = MiliClassTransformer.apply(
                wideTarget(), "test/synth/CapWide",
                List.of(headCapture("test/synth/CapWide", "mix",
                        "(IJDLjava/lang/String;)D",
                        "(IJD)V", "headWide")));

        Class<?> c = define("test.synth.CapWide", transformed);
        Cap.capturedA = 0;
        Cap.capturedB = 0;
        Cap.capturedD = 0;
        assertEquals(2.5, c.getMethod("mix", int.class, long.class,
                        double.class, String.class)
                .invoke(null, 7, 100L, 2.5, "x"), 0.0, "方法体照常执行");
        assertEquals(7, Cap.capturedA);
        assertEquals(100L, Cap.capturedB, "long 捕获必须读到完整宽槽");
        assertEquals(2.5, Cap.capturedD, 0.0,
                "double 的槽位是 0+1+2=3；按「每参数+1」会读成 2 而错位");
    }

    // ── RETURN / MODIFY_RETURN 捕获 ──────────────────────────────────────────

    @Test
    @DisplayName("HEAD 读入口值，RETURN 读当前值 —— 文档化语义")
    void slotMutationSemantics() throws Exception {
        // HEAD：方法体尚未执行，捕获到入口值 5（传入的实参）
        byte[] headTransformed = MiliClassTransformer.apply(
                slotMutationTarget("test/synth/CapSlotHead"), "test/synth/CapSlotHead",
                List.of(headCapture("test/synth/CapSlotHead", "bump", "(I)I",
                        "(I)V", "headSlot")));
        Class<?> head = define("test.synth.CapSlotHead", headTransformed);
        Cap.sum = -1;
        assertEquals(6, head.getMethod("bump", int.class).invoke(null, 5),
                "方法体照常执行");
        assertEquals(5, Cap.sum,
                "HEAD 捕获读到入口值 5（方法体的 a = a + 1 尚未发生）");

        // RETURN：方法体已执行，参数槽被重写为 6，捕获到当前值
        byte[] returnTransformed = MiliClassTransformer.apply(
                slotMutationTarget("test/synth/CapSlotReturn"), "test/synth/CapSlotReturn",
                List.of(new MiliClassTransformer.MethodInjection(
                        TargetMethod.of("test/synth/CapSlotReturn", "bump", "(I)I"),
                        null, InjectionPoint.RETURN, "(I)V",
                        null, -1,
                        CB, "returnSlot", "(I)V",
                        true, "test-capture-return", 0, false, null)));
        Class<?> ret = define("test.synth.CapSlotReturn", returnTransformed);
        Cap.sum = -1;
        assertEquals(6, ret.getMethod("bump", int.class).invoke(null, 5));
        assertEquals(6, Cap.sum,
                "RETURN 捕获读到局部变量槽的当前值（参数被方法体重写后）");
    }

    @Test
    @DisplayName("MODIFY_RETURN 捕获实参并改写返回值")
    void modifyReturnCapturesAndRewrites() throws Exception {
        byte[] transformed = MiliClassTransformer.apply(
                modifyReturnTarget(), "test/synth/CapModRet",
                List.of(new MiliClassTransformer.MethodInjection(
                        TargetMethod.of("test/synth/CapModRet", "identity", "(I)I"),
                        null, InjectionPoint.MODIFY_RETURN, "(I)I",
                        null, -1,
                        CB, "modReturn", "(I)I",
                        true, "test-capture-modret", 0, false, null)));

        Class<?> c = define("test.synth.CapModRet", transformed);
        assertEquals(420, c.getMethod("identity", int.class).invoke(null, 42),
                "回调捕获 v=42 并返回 v*10");
    }

    // ── 引擎侧防御（绕过扫描器的编程式路径） ────────────────────────────────

    @Test
    @DisplayName("引擎拒绝装箱捕获（Integer ≠ int 描述符）")
    void engineRejectsBoxedCapture() {
        assertThrows(TransformationException.class, () ->
                MiliClassTransformer.apply(
                        twoIntTarget(), "test/synth/CapAdd",
                        List.of(headCapture("test/synth/CapAdd", "add", "(II)I",
                                "(Ljava/lang/Integer;I)V", "headTwoInts"))));
    }

    @Test
    @DisplayName("引擎拒绝捕获参数多于宿主实参")
    void engineRejectsOverflowCapture() {
        assertThrows(TransformationException.class, () ->
                MiliClassTransformer.apply(
                        modifyReturnTarget(), "test/synth/CapModRet",
                        List.of(headCapture("test/synth/CapModRet", "identity", "(I)I",
                                "(II)V", "headTwoInts"))));
    }

    @Test
    @DisplayName("引擎拒绝 BEFORE_INVOKE 上的实参捕获（构造器防御）")
    void engineRejectsCaptureOnBeforeInvoke() {
        assertThrows(TransformationException.class, () -> {
            // 合成一个含 add 调用的宿主，BEFORE_INVOKE 回调却声明捕获参数
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/synth/CapInvoke",
                    null, "java/lang/Object", null);
            MethodVisitor call = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                    "call", "()I", null, null);
            call.visitCode();
            call.visitLdcInsn(2);
            call.visitLdcInsn(3);
            call.visitMethodInsn(INVOKESTATIC, "test/synth/CapInvoke",
                    "add", "(II)I", false);
            call.visitInsn(IRETURN);
            call.visitMaxs(2, 0);
            call.visitEnd();
            cw.visitEnd();

            MiliClassTransformer.apply(
                    cw.toByteArray(), "test/synth/CapInvoke",
                    List.of(new MiliClassTransformer.MethodInjection(
                            TargetMethod.of("test/synth/CapInvoke", "call", "()I"),
                            null, InjectionPoint.BEFORE_INVOKE, "(II)V",
                            TargetInvocation.at(TargetMethod.of(
                                    "test/synth/CapInvoke", "add", "(II)I"), 0), -1,
                            CB, "headTwoInts", "(II)V",
                            true, "test-capture-invoke", 0, false, null)));
        });
    }

    // ── 扫描器侧校验（注解路径） ─────────────────────────────────────────────

    @Test
    @DisplayName("扫描器拒绝类型不匹配的捕获参数")
    void scannerRejectsMismatchedCapture() {
        TransformationException e = assertThrows(TransformationException.class, () ->
                AnnotationTransformerScanner.scan(MismatchedHook.class, "26.2", null));
        assertTrue(e.getMessage().contains("捕获参数"),
                "错误信息必须指出捕获参数问题，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("扫描器拒绝调用点注入上的实参捕获")
    void scannerRejectsCaptureOnCallSite() {
        TransformationException e = assertThrows(TransformationException.class, () ->
                AnnotationTransformerScanner.scan(CaptureOnModifyArgHook.class,
                        "26.2", null));
        assertTrue(e.getMessage().contains("不支持捕获"),
                "错误信息必须指出注入点不支持捕获，实际: " + e.getMessage());
    }

    // ── 注解全链路：scan → transform → defineClass → 实调 ───────────────────

    @Test
    @DisplayName("全链路：注解回调 (BooleanSupplier) 注入合成 tickServer")
    void annotationEndToEndOnSyntheticServer() throws Exception {
        var transformer = AnnotationTransformerScanner.scan(
                ServerCaptureHook.class, "26.2", "testmod");
        assertNotNull(transformer);

        TransformationEnvironment env = new TransformationEnvironment(
                getClass().getClassLoader(), "26.2",
                TransformationEnvironment.RuntimeEnvironmentValue.CLIENT, "test");
        TransformationContext context = new TransformationContext(
                "test/synth/FakeServer", fakeServerTarget(), env,
                TransformationPhase.CORE, "testmod", transformer.id());

        TransformationResult result = transformer.transform(context);
        assertTrue(result instanceof TransformationResult.Transformed);
        byte[] transformed = ((TransformationResult.Transformed) result).bytecode();

        Class<?> c = define("test.synth.FakeServer", transformed);
        Object server = c.getDeclaredConstructor().newInstance();

        Cap.supplierValue = false;
        c.getMethod("tickServer", BooleanSupplier.class)
                .invoke(server, (BooleanSupplier) () -> true);
        assertTrue(Cap.supplierValue,
                "回调必须收到捕获的 BooleanSupplier 且 getAsBoolean() == true");
        assertEquals(1, c.getField("counter").getInt(server),
                "方法体照常执行：supplier 为 true 时 counter 自增");
    }

    // ── 扫描器测试用 hook 类 ─────────────────────────────────────────────────

    @MiliTransformer(target = "minecraft.server.tick")
    public static class MismatchedHook {
        @MiliInject(at = InjectionPoint.HEAD)
        public static void cb(String s) {
        }
    }

    @MiliTransformer(target = "minecraft.server.tick")
    public static class CaptureOnModifyArgHook {
        @MiliInject(at = InjectionPoint.MODIFY_ARG,
                target = "net/minecraft/server/MinecraftServer#getTicks()I",
                argIndex = 0)
        public static void cb(BooleanSupplier bs) {
        }
    }

    @MiliTransformer(target = "minecraft.server.tick")
    public static class ServerCaptureHook {
        @MiliInject(at = InjectionPoint.HEAD)
        public static void onServerTick(BooleanSupplier keepGoing) {
            Cap.serverHead(keepGoing);
        }
    }
}
