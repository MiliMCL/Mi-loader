package org.loader.api.transform.target;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标解析层测试。
 *
 * <p><b>为什么这层值得单独测</b>：描述符写错不会在编译期暴露，
 * 只会让注入静默不命中 —— 而「静默不命中」正是本次审计发现
 * {@code tickServer()} 与真实 {@code tickServer(BooleanSupplier)} 偏差的
 * 根因。严格校验比事后排查便宜得多。
 */
@DisplayName("目标解析 TargetMethod / TargetInvocation / TargetField")
class TargetResolverTest {

    private static final String SERVER = "net/minecraft/server/MinecraftServer";

    @Test
    @DisplayName("合法三元组被接受")
    void acceptsValidTriplet() {
        TargetMethod m = TargetMethod.of(SERVER, "tickServer",
                "(Ljava/util/function/BooleanSupplier;)V");

        assertEquals(SERVER, m.owner());
        assertEquals("tickServer", m.name());
        assertEquals("(Ljava/util/function/BooleanSupplier;)V", m.descriptor());
        assertEquals("net.minecraft.server.MinecraftServer", m.ownerDotted());
    }

    @Test
    @DisplayName("描述符缺少返回类型分隔符时被拒绝")
    void rejectsDescriptorWithoutReturnSeparator() {
        // "(Ljava/lang/String;" —— 有 '(' 但没有 ')'，说明参数列表未闭合。
        assertThrows(IllegalArgumentException.class,
                () -> TargetMethod.of(SERVER, "x", "(Ljava/lang/String;"));
    }

    @Test
    @DisplayName("空字段被拒绝")
    void rejectsBlankFields() {
        assertThrows(IllegalArgumentException.class,
                () -> TargetMethod.of("", "tickServer", "()V"));
        assertThrows(IllegalArgumentException.class,
                () -> TargetMethod.of(SERVER, "  ", "()V"));
        assertThrows(IllegalArgumentException.class,
                () -> TargetMethod.of(SERVER, "tickServer", ""));
        assertThrows(IllegalArgumentException.class,
                () -> TargetMethod.of(null, "tickServer", "()V"));
    }

    @Test
    @DisplayName("不同描述符视为不同方法 —— 防止重载误匹配")
    void descriptorIsPartOfIdentity() {
        TargetMethod a = TargetMethod.of(SERVER, "tickServer",
                "(Ljava/util/function/BooleanSupplier;)V");
        TargetMethod b = TargetMethod.of(SERVER, "tickServer",
                "()V");

        assertNotEquals(a, b,
                "同名但描述符不同的方法必须是不同的目标");
    }

    @Test
    @DisplayName("equals/hashCode 一致，可安全做 Map key")
    void equalsAndHashCodeConsistent() {
        TargetMethod a = TargetMethod.of(SERVER, "tickServer",
                "(Ljava/util/function/BooleanSupplier;)V");
        TargetMethod b = TargetMethod.of(SERVER, "tickServer",
                "(Ljava/util/function/BooleanSupplier;)V");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        var map = new java.util.HashMap<TargetMethod, String>();
        map.put(a, "first");
        assertEquals("first", map.get(b));
    }

    @Test
    @DisplayName("TargetInvocation ordinal 决定调用点")
    void invocationOrdinalSelectsCallSite() {
        TargetMethod callee = TargetMethod.of("java/util/List", "add",
                "(Ljava/lang/Object;)Z");

        TargetInvocation first = TargetInvocation.first(callee);
        TargetInvocation second = TargetInvocation.at(callee, 1);

        assertEquals(0, first.ordinal());
        assertEquals(1, second.ordinal());
        assertNotEquals(first.conflictKey(), second.conflictKey(),
                "不同 ordinal 是不同的注入点，不能共享冲突主键");
    }

    @Test
    @DisplayName("负数 ordinal 被拒绝")
    void invocationRejectsNegativeOrdinal() {
        TargetMethod callee = TargetMethod.of("java/util/List", "add", "(Ljava/lang/Object;)Z");
        assertThrows(IllegalArgumentException.class,
                () -> TargetInvocation.at(callee, -1));
    }

    @Test
    @DisplayName("opcode 未限定时匹配任意调用指令")
    void invocationWithoutOpcodeMatchesAny() {
        TargetMethod callee = TargetMethod.of("java/util/List", "add", "(Ljava/lang/Object;)Z");
        TargetInvocation inv = TargetInvocation.first(callee);

        assertTrue(inv.matchesOpcode(182)); // INVOKEVIRTUAL
        assertTrue(inv.matchesOpcode(183)); // INVOKESPECIAL
        assertTrue(inv.matchesOpcode(184)); // INVOKESTATIC
    }

    @Test
    @DisplayName("opcode 限定后只匹配指定指令 —— 用于区分构造器调用")
    void invocationWithOpcodeMatchesOnlyThatOpcode() {
        TargetMethod callee = TargetMethod.of("java/lang/StringBuilder", "<init>", "()V");
        TargetInvocation inv = TargetInvocation.first(callee).constructorOnly();

        assertTrue(inv.matchesOpcode(183));   // INVOKESPECIAL — 构造器
        assertFalse(inv.matchesOpcode(182));  // INVOKEVIRTUAL
        assertFalse(inv.matchesOpcode(184));  // INVOKESTATIC
    }

    @Test
    @DisplayName("TargetField 描述符参与身份判定")
    void fieldDescriptorIsPartOfIdentity() {
        TargetField count = TargetField.of(SERVER, "tickCount", "I");
        TargetField names = TargetField.of(SERVER, "tickCount", "Ljava/lang/String;");

        assertNotEquals(count, names);
        assertEquals("I", count.descriptor());
    }

    @Test
    @DisplayName("toString 保留完整坐标，便于日志与异常")
    void toStringIsDiagnostic() {
        String rendered = TargetMethod.of(SERVER, "tickServer",
                "(Ljava/util/function/BooleanSupplier;)V").toString();

        assertTrue(rendered.startsWith(SERVER + "#tickServer"), rendered);
        assertTrue(rendered.contains("(Ljava/util/function/BooleanSupplier;)V"), rendered);
    }

    @Test
    @DisplayName("真实 26.2 坐标可无异常构造 —— 防回归")
    void realCoordinatesConstruct() {
        // 这组坐标来自对真实 26.2 class 文件的常量池解析。
        // 若有人改动符号表，这里的构造会立刻暴露问题。
        assertDoesNotThrow(() -> {
            TargetMethod.of("net/minecraft/server/MinecraftServer", "tickServer",
                    "(Ljava/util/function/BooleanSupplier;)V");
            TargetMethod.of("net/minecraft/server/MinecraftServer", "tickChildren",
                    "(Ljava/util/function/BooleanSupplier;)V");
            TargetMethod.of("net/minecraft/client/multiplayer/ClientLevel", "tick",
                    "(Ljava/util/function/BooleanSupplier;)V");
        });
    }
}