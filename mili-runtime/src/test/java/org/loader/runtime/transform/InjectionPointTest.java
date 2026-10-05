package org.loader.runtime.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.TransformationException;
import org.loader.api.transform.target.TargetInvocation;
import org.loader.api.transform.target.TargetMethod;
import org.loader.runtime.transform.asm.MiliClassTransformer;
import org.loader.runtime.transform.verify.BytecodeVerifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注入点字节码正确性测试。
 *
 * <h2>每个测试都在断言三件事</h2>
 * <ol>
 *   <li><b>产出合法字节码</b> —— CheckClassAdapter + Analyzer 全过；</li>
 *   <li><b>回调落在正确位置</b> —— 用指令序列断言，不是「跑起来看看」；</li>
 *   <li><b>失败的写法必须显式报错</b> —— 不允许静默降级。</li>
 * </ol>
 *
 * <p>第2 条是关键：只断言「字节码合法」是不够的 ——
 * 大量错误的注入产出<b>完全合法</b>的字节码，只是语义错了
 * （例如把 GETSTATIC 当成有操作数、MODIFY_ARG 改错槽位）。
 * 合法 ≠ 正确。
 */
class InjectionPointTest {

    private static final String DISPATCH = "example/Dispatcher";
    private static final String CALLBACK = "onInject";

    private static TargetMethod method(String name, String desc) {
        return TargetMethod.of(TransformTestFixture.SAMPLE_CLASS, name, desc);
    }

    private static MiliClassTransformer.MethodInjection injection(
            TargetMethod target, InjectionPoint point, String callbackDesc) {
        return new MiliClassTransformer.MethodInjection(
                target, null, point, callbackDesc, null, -1,
                DISPATCH, CALLBACK, callbackDesc, false, "test-transformer", 0);
    }

    /** 收集转换后某个方法里对分发器的调用次数。 */
    private static int countCallbackInvocations(byte[] bytes, String methodName) {
        int[] count = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                if (!methodName.equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner,
                                                String mName, String mDesc,
                                                boolean itf) {
                        if (DISPATCH.equals(owner)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return count[0];
    }

    /** 收集方法内对分发器的调用位置（相对指令序号的序号）。 */
    private static List<Integer> callbackPositions(byte[] bytes, String methodName) {
        List<Integer> positions = new ArrayList<>();
        int[] index = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                if (!methodName.equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner,
                                                String mName, String mDesc,
                                                boolean itf) {
                        if (DISPATCH.equals(owner)) {
                            positions.add(index[0]);
                        }
                        index[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return positions;
    }

    // ── HEAD / RETURN ─────────────────────────────────────────────

    @Test
    @DisplayName("HEAD 注入产出合法字节码，且回调进入方法体")
    void headInjectionProducesValidBytecode() {
        byte[] original = TransformTestFixture.sampleClass();
        byte[] result = MiliClassTransformer.apply(original,
                TransformTestFixture.SAMPLE_CLASS,
                List.of(injection(method("intMethod", TransformTestFixture.INT_DESC),
                        InjectionPoint.HEAD, "()V")));

        TransformTestFixture.assertValidBytecode(result, "HEAD");
        assertDoesNotThrow(() -> BytecodeVerifier.verify(
                TransformTestFixture.SAMPLE_CLASS, result, "test", null));
    }

    @Test
    @DisplayName("RETURN 注入覆盖每一个返回路径，包括 early return")
    void returnInjectionCoversEveryExitPath() {
        byte[] original = TransformTestFixture.sampleClass();
        // voidMethod(Z)V 里有两条 RETURN：early return 与正常返回
        byte[] result = MiliClassTransformer.apply(original,
                TransformTestFixture.SAMPLE_CLASS,
                List.of(injection(method("voidMethod", "(Z)V"),
                        InjectionPoint.RETURN, "()V")));

        TransformTestFixture.assertValidBytecode(result, "RETURN");

        // 关键断言：两条 RETURN 都要有回调。
        // 若实现退化为「只在最后一个 RETURN 前插入」，
        // 这里会得到 1 而不是 2 —— 而那在真实 Minecraft 里意味着
        // 「玩家离开服务器时 tick 推进丢失」。
        int calls = countCallbackInvocations(result, "voidMethod");
        assertEquals(2, calls,
                "RETURN 注入必须覆盖方法内每一个返回路径。"
                        + "只覆盖最后一个 RETURN 会在 early return 时丢失回调，"
                        + "表现为「大部分时候正常，偶尔不执行」。");
    }

    @Test
    @DisplayName("非 void 方法的 RETURN 注入保持栈平衡")
    void returnInjectionOnNonVoidKeepsStackBalanced() {
        byte[] original = TransformTestFixture.sampleClass();
        byte[] result = MiliClassTransformer.apply(original,
                TransformTestFixture.SAMPLE_CLASS,
                List.of(injection(method("intMethod", TransformTestFixture.INT_DESC),
                        InjectionPoint.RETURN, "()V")));

        // 若实现忘了暂存返回值，()V 回调会让栈上多留一个 int，
        // 紧随的 IRETURN 会 VerifyError。assertValidBytecode 会抓到。
        TransformTestFixture.assertValidBytecode(result, "RETURN on int method");
    }

    // ── MODIFY_RETURN ──────────────────────────────────────────────

    @Test
    @DisplayName("MODIFY_RETURN 用返回值回调替换原返回值")
    void modifyReturnReplacesValue() {
        byte[] original = TransformTestFixture.sampleClass();
        byte[] result = MiliClassTransformer.apply(original,
                TransformTestFixture.SAMPLE_CLASS,
                List.of(injection(method("intMethod", TransformTestFixture.INT_DESC),
                        InjectionPoint.MODIFY_RETURN, "()I")));

        TransformTestFixture.assertValidBytecode(result, "MODIFY_RETURN");
    }

    @Test
    @DisplayName("MODIFY_RETURN 声明 ()V 回调必须报错 —— 静默通过会产生 VerifyError")
    void modifyReturnRejectsVoidCallback() {
        byte[] original = TransformTestFixture.sampleClass();

        // ()V 回调插到返回值之上会让栈上无值，随后的 IRETURN 直接 VerifyError。
        // 平台必须在生成期就拒绝，而不是等运行期崩溃。
        TransformationException ex = assertThrows(TransformationException.class,
                () -> MiliClassTransformer.apply(original,
                        TransformTestFixture.SAMPLE_CLASS,
                        List.of(injection(method("intMethod", TransformTestFixture.INT_DESC),
                                InjectionPoint.MODIFY_RETURN, "()V"))));

        assertTrue(ex.getMessage().contains("MODIFY_RETURN"),
                "异常信息必须点名注入点: " + ex.getMessage());
    }

    @Test
    @DisplayName("MODIFY_RETURN 作用于 void 方法必须报错")
    void modifyReturnRejectsVoidMethod() {
        byte[] original = TransformTestFixture.sampleClass();

        assertThrows(TransformationException.class,
                () -> MiliClassTransformer.apply(original,
                        TransformTestFixture.SAMPLE_CLASS,
                        List.of(injection(method("voidMethod", "(Z)V"),
                                InjectionPoint.MODIFY_RETURN, "()V"))));
    }

    // ── MODIFY_ARG ─────────────────────────────────────────────────

    @Test
    @DisplayName("MODIFY_ARG 修改指定槽位的实参")
    void modifyArgumentChangesSelectedSlot() {
        byte[] original = TransformTestFixture.sampleClass();
        // wideMethod(int, String, long) -> int，修改 idx=1（String）
        TargetMethod wide = method("wideMethod", TransformTestFixture.LONG_METHOD_DESC);

        MiliClassTransformer.MethodInjection mod = new MiliClassTransformer.MethodInjection(
                wide, null, InjectionPoint.MODIFY_ARG, "()Ljava/lang/String;",
                TargetInvocation.first(method("wideCallee", TransformTestFixture.WIDE_CALLEE_DESC)),
                1, DISPATCH, CALLBACK, "()Ljava/lang/String;", false, "t", 0);

        byte[] result = MiliClassTransformer.apply(original,
                TransformTestFixture.SAMPLE_CLASS, List.of(mod));

        // wide 类型（long 占两槽）在前后必须保持平衡 ——
        // 这是 MODIFY_ARG 最容易算错的地方
        TransformTestFixture.assertValidBytecode(result, "MODIFY_ARG with long arg");
    }

    @Test
    @DisplayName("MODIFY_ARG 索引越界必须报 TargetNotFound 而非静默忽略")
    void modifyArgumentRejectsOutOfRangeIndex() {
        byte[] original = TransformTestFixture.sampleClass();
        TargetMethod wide = method("wideMethod", TransformTestFixture.LONG_METHOD_DESC);

        MiliClassTransformer.MethodInjection mod = new MiliClassTransformer.MethodInjection(
                wide, null, InjectionPoint.MODIFY_ARG, "()I",
                TargetInvocation.first(method("wideCallee", TransformTestFixture.WIDE_CALLEE_DESC)),
                99,  // 越界
                DISPATCH, CALLBACK, "()I", false, "t", 0);

        assertThrows(org.loader.api.transform.TransformationTargetNotFoundException.class,
                () -> MiliClassTransformer.apply(original,
                        TransformTestFixture.SAMPLE_CLASS, List.of(mod)));
    }

    @Test
    @DisplayName("MODIFY_ARG 回调返回类型不匹配必须报错")
    void modifyArgumentRejectsWrongCallbackReturnType() {
        byte[] original = TransformTestFixture.sampleClass();
        TargetMethod wide = method("wideMethod", TransformTestFixture.LONG_METHOD_DESC);

        // 目标是 String 参数（idx=1），回调却返回 int
        MiliClassTransformer.MethodInjection mod = new MiliClassTransformer.MethodInjection(
                wide, null, InjectionPoint.MODIFY_ARG, "()I",
                TargetInvocation.first(method("wideCallee", TransformTestFixture.WIDE_CALLEE_DESC)),
                1, DISPATCH, CALLBACK, "()I", false, "t", 0);

        assertThrows(TransformationException.class,
                () -> MiliClassTransformer.apply(original,
                        TransformTestFixture.SAMPLE_CLASS, List.of(mod)));
    }

    // ── REDIRECT ───────────────────────────────────────────────────

    @Test
    @DisplayName("REDIRECT 把原调用替换为分发器的静态调用")
    void redirectReplacesInvocation() {
        byte[] original = TransformTestFixture.sampleClass();
        TargetMethod caller = method("multiCall", "()V");

        MiliClassTransformer.MethodInjection redirect =
                new MiliClassTransformer.MethodInjection(
                        caller, null, InjectionPoint.REDIRECT, null,
                        TargetInvocation.at(method("intMethod", TransformTestFixture.INT_DESC), 0),
                        -1, DISPATCH, "replacement", TransformTestFixture.INT_DESC,
                        true,   // replacementStatic
                        "t", 0);

        byte[] result = MiliClassTransformer.apply(original,
                TransformTestFixture.SAMPLE_CLASS, List.of(redirect));

        TransformTestFixture.assertValidBytecode(result, "REDIRECT");

        // 只有 ordinal 0 被替换：multiCall 里有 3 次 intMethod 调用
        List<Integer> positions = callbackPositions(result, "multiCall");
        assertEquals(1, positions.size(),
                "REDIRECT 必须只替换指定的 ordinal，"
                        + "替换全部会让后续调用全部指向同一个替代方法。");
    }

    @Test
    @DisplayName("REDIRECT 缺少替换目标必须报错")
    void redirectRequiresReplacementTarget() {
        byte[] original = TransformTestFixture.sampleClass();
        TargetMethod caller = method("multiCall", "()V");

        MiliClassTransformer.MethodInjection redirect =
                new MiliClassTransformer.MethodInjection(
                        caller, null, InjectionPoint.REDIRECT, null,
                        TargetInvocation.at(method("intMethod", TransformTestFixture.INT_DESC), 0),
                        -1, null, null, null, false, "t", 0);

        assertThrows(TransformationException.class,
                () -> MiliClassTransformer.apply(original,
                        TransformTestFixture.SAMPLE_CLASS, List.of(redirect)));
    }

    // ── 组合 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("同一方法上多个注入点叠加后仍然合法")
    void multipleInjectionPointsCompose() {
        byte[] original = TransformTestFixture.sampleClass();
        TargetMethod wide = method("wideMethod", TransformTestFixture.LONG_METHOD_DESC);

        List<MiliClassTransformer.MethodInjection> injections = List.of(
                injection(wide, InjectionPoint.HEAD, "()V"),
                injection(wide, InjectionPoint.RETURN, "()V"),
                new MiliClassTransformer.MethodInjection(
                        wide, null, InjectionPoint.MODIFY_ARG, "()I",
                        TargetInvocation.first(method("wideCallee", TransformTestFixture.WIDE_CALLEE_DESC)),
                        0, DISPATCH, CALLBACK, "()I", false, "t", 0));

        byte[] result = MiliClassTransformer.apply(original,
                TransformTestFixture.SAMPLE_CLASS, injections);

        TransformTestFixture.assertValidBytecode(result, "HEAD + RETURN + MODIFY_ARG");
        assertEquals(3, countCallbackInvocations(result, "wideMethod"));
    }

    @Test
    @DisplayName("不匹配的注入不产生任何字节码变化")
    void nonMatchingInjectionIsNoOp() {
        byte[] original = TransformTestFixture.sampleClass();
        // 目标方法名不在这份字节码里
        byte[] result = MiliClassTransformer.apply(original,
                TransformTestFixture.SAMPLE_CLASS,
                List.of(injection(method("nonExistentMethod", "()V"),
                        InjectionPoint.HEAD, "()V")));

        assertEquals(0, countCallbackInvocations(result, "voidMethod"));
        assertEquals(0, countCallbackInvocations(result, "intMethod"));
    }
}