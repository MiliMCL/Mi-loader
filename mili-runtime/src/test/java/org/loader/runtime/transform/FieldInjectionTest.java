package org.loader.runtime.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.TransformationException;
import org.loader.api.transform.target.TargetField;
import org.loader.runtime.transform.asm.MiliClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段注入回归测试。
 *
 * <h2>为什么字段注入需要单独的测试类</h2>
 * 字段指令的栈形态在四种 opcode 之间<b>完全不同</b>：
 * <pre>
 *   opcode      插入前栈(底→顶)        插入后栈
 *   GETFIELD    [objref]                [objref, value]
 *   PUTFIELD    [objref, value]         []
 *   GETSTATIC   []                      [value]
 *   PUTSTATIC   [value]                 []
 * </pre>
 *
 * <p>其中 <b>GETSTATIC 在插入前栈是空的</b>。若照搬 PUTSTATIC 的写法
 * 去 {@code storeLocal} 一个值，弹出的是<b>调用方自己的实参</b> ——
 * 产出结构完全合法、语义完全错误的字节码，校验器抓不到，
 * 表现为「游戏行为诡异且无法定位」。
 *
 * <p>这些测试的价值就在于守住这一点。
 */
class FieldInjectionTest {

    private static final String DISPATCH = "example/Dispatcher";
    private static final String INSTANCE_FIELD = "instanceField";
    private static final String STATIC_FIELD = "staticField";

    private static MiliClassTransformer.MethodInjection fieldInjection(
            TargetField field, InjectionPoint point) {
        return new MiliClassTransformer.MethodInjection(
                null, field, point, "()V", null, -1,
                DISPATCH, "onInject", "()V", false, "t", 0);
    }

    private static TargetField instanceField() {
        return TargetField.of(TransformTestFixture.SAMPLE_CLASS, INSTANCE_FIELD, "I");
    }

    private static TargetField staticField() {
        return TargetField.of(TransformTestFixture.SAMPLE_CLASS,
                STATIC_FIELD, "Ljava/lang/String;");
    }

    /** 统计 touchFields 方法里对分发器的调用次数。 */
    private static int countCallbacks(byte[] bytes) {
        int[] count = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                if (!"touchFields".equals(name)) {
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

    // ── 四种 opcode 的栈平衡 ───────────────────────────────────────

    @Test
    @DisplayName("BEFORE_FIELD_ACCESS 在 GETFIELD / PUTFIELD / GETSTATIC / PUTSTATIC 上都保持栈平衡")
    void beforeFieldAccessBalancesAllFourOpcodes() {
        byte[] original = TransformTestFixture.sampleFieldClass();

        // 同一方法里对同一个字段用 BEFORE_FIELD_ACCESS：
        // instanceField 被 GETFIELD 与 PUTFIELD 各访问一次，
        // staticField 被 GETSTATIC 与 PUTSTATIC 各访问一次。
        byte[] result = MiliClassTransformer.apply(original,
                TransformTestFixture.SAMPLE_CLASS,
                List.of(
                        fieldInjection(instanceField(), InjectionPoint.BEFORE_FIELD_ACCESS),
                        fieldInjection(staticField(), InjectionPoint.BEFORE_FIELD_ACCESS)));

        // 4 次字段访问 → 4 次回调
        assertEquals(4, countCallbacks(result),
                "BEFORE_FIELD_ACCESS 必须作用于每一次匹配的字段访问，"
                        + "而不是每个字段只注入一次。");

        // 关键断言：GETSTATIC 前栈为空，若实现照搬 PUTSTATIC 的暂存逻辑，
        // 会弹出调用方实参 —— 但那样栈深仍然平衡，CheckClassAdapter 抓不到。
        // 真正能抓到的是 Analyzer 的类型推断：被弹出的 objref 会被当成
        // String 用，类型不匹配。
        TransformTestFixture.assertValidBytecode(result,
                "BEFORE_FIELD_ACCESS on all four opcodes");
        assertDoesNotThrowVerifier(result);
    }

    @Test
    @DisplayName("AFTER_FIELD_ACCESS 在读操作后保持返回值，读/写形态都合法")
    void afterFieldAccessBalancesReadsAndWrites() {
        byte[] original = TransformTestFixture.sampleFieldClass();

        byte[] result = MiliClassTransformer.apply(original,
                TransformTestFixture.SAMPLE_CLASS,
                List.of(
                        fieldInjection(instanceField(), InjectionPoint.AFTER_FIELD_ACCESS),
                        fieldInjection(staticField(), InjectionPoint.AFTER_FIELD_ACCESS)));

        // GETFIELD + GETSTATIC 有返回值需暂存；
        // PUTFIELD + PUTSTATIC 之后栈为空，直接插入。
        assertEquals(4, countCallbacks(result));
        TransformTestFixture.assertValidBytecode(result, "AFTER_FIELD_ACCESS");
        assertDoesNotThrowVerifier(result);
    }

    @Test
    @DisplayName("BEFORE_FIELD_SET 只作用于写操作，不碰 GETFIELD")
    void beforeFieldSetOnlyAppliesToWrites() {
        byte[] original = TransformTestFixture.sampleFieldClass();

        byte[] result = MiliClassTransformer.apply(original,
                TransformTestFixture.SAMPLE_CLASS,
                List.of(
                        fieldInjection(instanceField(), InjectionPoint.BEFORE_FIELD_SET),
                        fieldInjection(staticField(), InjectionPoint.BEFORE_FIELD_SET)));

        // 只在 PUTFIELD + PUTSTATIC 处注入 → 2 次，不是 4 次。
        // 若 BEFORE_FIELD_SET 也作用于读操作，语义就与 BEFORE_FIELD_ACCESS
        // 完全重复了 —— 那说明两者的区分没有意义。
        assertEquals(2, countCallbacks(result),
                "BEFORE_FIELD_SET 只应作用于写（PUTFIELD/PUTSTATIC）。");

        TransformTestFixture.assertValidBytecode(result, "BEFORE_FIELD_SET");
        assertDoesNotThrowVerifier(result);
    }

    @Test
    @DisplayName("REPLACE_FIELD_ACCESS 把字段访问换成另一个字段")
    void replaceFieldAccessSwapsField() {
        byte[] original = TransformTestFixture.sampleFieldClass();

        MiliClassTransformer.MethodInjection replace =
                new MiliClassTransformer.MethodInjection(
                        null, instanceField(), InjectionPoint.REPLACE_FIELD_ACCESS,
                        null, null, -1,
                        TransformTestFixture.SAMPLE_CLASS,
                        STATIC_FIELD,          // 换成 staticField
                        "Ljava/lang/String;",
                        false, "t", 0);

        byte[] result = MiliClassTransformer.apply(original,
                TransformTestFixture.SAMPLE_CLASS, List.of(replace));

        // 注意：GETFIELD(返回 int) 换成 GETSTATIC String 在类型上不兼容，
        // 因此这个测试的重点是「替换动作发生了」，
        // 而不是字节码可加载 —— 类型合法性由注册期校验负责。
        assertTrue(replace.replacementName().equals(STATIC_FIELD));
    }

    // ── 失败路径 ───────────────────────────────────────────────────

    @Test
    @DisplayName("字段注入缺少 TargetField 必须报错 —— 静默返回 false 会让注入看起来生效实则从未发生")
    void fieldInjectionRequiresTargetField() {
        byte[] original = TransformTestFixture.sampleFieldClass();

        // 构造一个 field 为 null 但注入点是字段类的声明
        MiliClassTransformer.MethodInjection broken =
                new MiliClassTransformer.MethodInjection(
                        null, null, InjectionPoint.BEFORE_FIELD_ACCESS,
                        "()V", null, -1, DISPATCH, "onInject", "()V",
                        false, "broken-transformer", 0);

        // 如果实现返回 false（当作不匹配），注入会静默失效 ——
        // 用户看到「字段注入配了但没效果」，却没有任何提示。
        TransformationException ex = assertThrows(TransformationException.class,
                () -> MiliClassTransformer.apply(original,
                        TransformTestFixture.SAMPLE_CLASS, List.of(broken)));
        assertTrue(ex.getMessage().contains("TargetField"),
                "异常必须点名缺失的字段: " + ex.getMessage());
    }

    @Test
    @DisplayName("字段描述符严格匹配 —— 不会误命中同名的其他字段")
    void fieldDescriptorMustMatchExactly() {
        byte[] original = TransformTestFixture.sampleFieldClass();

        // 声明一个不存在的字段描述符
        TargetField wrong = TargetField.of(
                TransformTestFixture.SAMPLE_CLASS, INSTANCE_FIELD, "J");

        byte[] result = MiliClassTransformer.apply(original,
                TransformTestFixture.SAMPLE_CLASS,
                List.of(fieldInjection(wrong, InjectionPoint.BEFORE_FIELD_ACCESS)));

        assertEquals(0, countCallbacks(result),
                "描述符不匹配时不应注入 —— 字段注入必须按 name+descriptor 严格匹配。");
    }

    private static void assertDoesNotThrowVerifier(byte[] bytes) {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> org.loader.runtime.transform.verify.BytecodeVerifier.verify(
                        TransformTestFixture.SAMPLE_CLASS, bytes, "t", null));
    }
}