package org.loader.api.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlockSpec} 的属性声明扩展 —— builder 行为与不可变性契约。
 */
class BlockSpecPropertyTest {

    @Test
    void builderCollectsPropertiesInOrder() {
        BlockSpec spec = BlockSpec.builder("crops/amaranth")
                .material(BlockSpec.Material.PLANT)
                .property(BlockPropertySpec.integer("age", 0, 6))
                .property(BlockPropertySpec.booleanProperty("hydrated"))
                .build();

        List<BlockPropertySpec> props = spec.properties();
        assertEquals(2, props.size());
        assertEquals("age", props.get(0).name());
        assertEquals("hydrated", props.get(1).name());
    }

    @Test
    void builderWithoutPropertiesYieldsEmptyList() {
        BlockSpec spec = BlockSpec.builder("plain_block").build();
        assertTrue(spec.properties().isEmpty());
    }

    @Test
    void duplicatePropertyNameIsRejected() {
        // 同名重复几乎总是复制粘贴错误 —— 静默去重只会掩盖问题
        assertThrows(IllegalArgumentException.class, () -> BlockSpec.builder("b")
                .property(BlockPropertySpec.integer("age", 0, 6))
                .property(BlockPropertySpec.integer("age", 0, 8))
                .build());
        assertThrows(IllegalArgumentException.class, () -> BlockSpec.builder("b")
                .property(BlockPropertySpec.integer("age", 0, 6))
                .property(BlockPropertySpec.booleanProperty("age"))
                .build());
    }

    @Test
    void nullPropertyIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockSpec.builder("b").property(null));
    }

    @Test
    void propertiesListIsImmutable() {
        BlockSpec spec = BlockSpec.builder("b")
                .property(BlockPropertySpec.integer("age", 0, 6))
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> spec.properties().add(BlockPropertySpec.booleanProperty("x")));
    }
}
