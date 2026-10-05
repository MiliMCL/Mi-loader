package org.loader.api.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TransformationContext} 契约测试。
 */
@DisplayName("TransformationContext 契约")
class TransformationContextTest {

    private static final byte[] BYTES = {1, 2, 3};

    private static TransformationEnvironment env() {
        return new TransformationEnvironment(
                // 静态方法里不能用 getClass()（它是非静态方法），
                // 只能通过类字面量取 ClassLoader —— 效果等价。
                TransformationContextTest.class.getClassLoader(),
                "26.2",
                TransformationEnvironment.RuntimeEnvironmentValue.DEDICATED_SERVER,
                "0.1.0");
    }

    @Test
    @DisplayName("字段可读回")
    void exposesAllFields() {
        var ctx = new TransformationContext(
                "net/minecraft/server/MinecraftServer", BYTES, env(),
                TransformationPhase.CORE, "mod-a", "t-1");

        assertEquals("net/minecraft/server/MinecraftServer", ctx.className());
        assertArrayEquals(BYTES, ctx.originalBytes());
        assertEquals(TransformationPhase.CORE, ctx.phase());
        assertEquals("mod-a", ctx.modId());
        assertEquals("t-1", ctx.transformerId());
        assertFalse(ctx.isPlatformTransformation());
    }

    @Test
    @DisplayName("平台自身转换 modId 为 null")
    void platformTransformationHasNullModId() {
        var ctx = new TransformationContext(
                "net/minecraft/server/MinecraftServer", BYTES, env(),
                TransformationPhase.CORE, null, "mili-core");

        assertNull(ctx.modId());
        assertTrue(ctx.isPlatformTransformation());
    }

    @Test
    @DisplayName("withIdentity 派生新实例且共享字节码")
    void withIdentityCreatesNewInstance() {
        var ctx = new TransformationContext(
                "A", BYTES, env(), TransformationPhase.MOD, "mod-a", "t-1");

        var derived = ctx.withIdentity("t-2", "mod-b");

        assertNotSame(ctx, derived);
        assertEquals("t-1", ctx.transformerId());
        assertEquals("t-2", derived.transformerId());
        assertEquals("mod-b", derived.modId());
        // 同一类加载内共享同一份字节码引用 —— 不重复拷贝
        assertSame(ctx.originalBytes(), derived.originalBytes());
    }

    @Test
    @DisplayName("null 必填项被拒绝")
    void rejectsNullRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new TransformationContext(null, BYTES, env(),
                        TransformationPhase.MOD, "m", "t"));
        assertThrows(IllegalArgumentException.class,
                () -> new TransformationContext("A", null, env(),
                        TransformationPhase.MOD, "m", "t"));
        assertThrows(IllegalArgumentException.class,
                () -> new TransformationContext("A", BYTES, null,
                        TransformationPhase.MOD, "m", "t"));
        assertThrows(IllegalArgumentException.class,
                () -> new TransformationContext("A", BYTES, env(),
                        null, "m", "t"));
        assertThrows(IllegalArgumentException.class,
                () -> new TransformationContext("A", BYTES, env(),
                        TransformationPhase.MOD, "m", null));
    }

    @Test
    @DisplayName("className 使用 JVM 内部名（斜杠分隔）")
    void usesInternalName() {
        // 内部名可直接喂给 ASM visit/visitMethodInsn，少一次转换也少一类 bug。
        var ctx = new TransformationContext(
                "net/minecraft/server/MinecraftServer", BYTES, env(),
                TransformationPhase.CORE, null, "t");

        assertTrue(ctx.className().contains("/"));
        assertFalse(ctx.className().contains("."),
                "内部名不应含点号，否则易与点分名混用");
    }

    @Test
    @DisplayName("TransformationEnvironment 版本比对是精确匹配")
    void environmentVersionMatchingIsExact() {
        var env = env();

        assertTrue(env.isMinecraftVersion("26.2"));
        assertFalse(env.isMinecraftVersion("26.x"),
                "范围值不应被视为匹配");
        assertFalse(env.isMinecraftVersion("26.20"));
        assertFalse(env.isMinecraftVersion("latest"));
    }

    @Test
    @DisplayName("TransformationEnvironment 缺省环境回退为 UNKNOWN")
    void environmentFallsBackToUnknown() {
        var env = new TransformationEnvironment(null, "26.2", null, "0.1.0");

        assertEquals(TransformationEnvironment.RuntimeEnvironmentValue.UNKNOWN,
                env.runtimeEnvironment());
    }
}