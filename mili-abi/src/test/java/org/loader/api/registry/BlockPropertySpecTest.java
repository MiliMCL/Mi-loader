package org.loader.api.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link BlockPropertySpec} 契约测试 —— 纯 ABI 数据类，无 Minecraft 依赖。
 *
 * <p>校验规则是「编译错误优于运行时错误」哲学的落点：非法名字必须
 * 在声明处（Mod 的 initialize 里）就炸出来，而不是等到注册期或
 * 游戏内表现为模型匹配不上。
 */
class BlockPropertySpecTest {

    @Test
    void integerPropertyCarriesNameAndRange() {
        BlockPropertySpec spec = BlockPropertySpec.integer("age", 0, 6);
        assertEquals("age", spec.name());
        assertEquals(BlockPropertySpec.Kind.INTEGER, spec.kind());
        assertEquals(0, spec.min());
        assertEquals(6, spec.max());
    }

    @Test
    void booleanPropertyCarriesName() {
        BlockPropertySpec spec = BlockPropertySpec.booleanProperty("hydrated");
        assertEquals("hydrated", spec.name());
        assertEquals(BlockPropertySpec.Kind.BOOLEAN, spec.kind());
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockPropertySpec.integer(null, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> BlockPropertySpec.integer("  ", 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> BlockPropertySpec.booleanProperty(""));
    }

    @Test
    void rejectsNameCharactersThatBreakBlockstateKeys() {
        // 空格 / '=' / ',' 都会破坏变体键 name=value[,name2=value2] 的格式
        assertThrows(IllegalArgumentException.class,
                () -> BlockPropertySpec.integer("has space", 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> BlockPropertySpec.integer("a=b", 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> BlockPropertySpec.integer("a,b", 0, 1));
    }

    @Test
    void rejectsInvertedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockPropertySpec.integer("age", 6, 0));
    }

    @Test
    void valueSemantics() {
        assertEquals(BlockPropertySpec.integer("age", 0, 6),
                BlockPropertySpec.integer("age", 0, 6));
        assertNotEquals(BlockPropertySpec.integer("age", 0, 6),
                BlockPropertySpec.integer("age", 0, 7));
        assertNotEquals(BlockPropertySpec.integer("age", 0, 6),
                BlockPropertySpec.booleanProperty("age"));
        assertEquals(BlockPropertySpec.integer("age", 0, 6).hashCode(),
                BlockPropertySpec.integer("age", 0, 6).hashCode());
    }

    @Test
    void readableToString() {
        assertEquals("age:0..6", BlockPropertySpec.integer("age", 0, 6).toString());
        assertEquals("lit:boolean", BlockPropertySpec.booleanProperty("lit").toString());
    }
}
