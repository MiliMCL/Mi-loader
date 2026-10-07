package org.loader.runtime.minecraft.block;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * blockstate 变体键解析的纯单元测试 —— 不需要 Minecraft，CI 全环境可跑。
 *
 * <p>样例取自实际 Mod（stardewvalley）的 blockstate 文件。
 */
class BlockStatePropertyInferenceTest {

    @Test
    void parsesSimpleVariantKeys() {
        String json = """
                {
                  "variants": {
                    "age=0": { "model": "stardewvalley:block/crops/amaranth/stage1" },
                    "age=1": { "model": "stardewvalley:block/crops/amaranth/stage2" }
                  }
                }
                """;
        Map<String, Set<String>> out = BlockStatePropertyInference.parseVariantProperties(json);
        assertEquals(Set.of("0", "1"), out.get("age"));
        assertEquals(1, out.size());
    }

    @Test
    void parsesMultiPropertyKeys() {
        String json = """
                {
                  "variants": {
                    "facing=north,lit=false": { "model": "a" },
                    "facing=east,lit=true": { "model": "b" }
                  }
                }
                """;
        Map<String, Set<String>> out = BlockStatePropertyInference.parseVariantProperties(json);
        assertEquals(Set.of("north", "east"), out.get("facing"));
        assertEquals(Set.of("false", "true"), out.get("lit"));
    }

    @Test
    void ignoresModelPathsAndMultipart() {
        // 模型路径里的字符串在深度 ≥2，不能被当成变体键
        String json = """
                {
                  "variants": {
                    "age=0": { "model": "qux=not_a_property" }
                  }
                }
                """;
        Map<String, Set<String>> out = BlockStatePropertyInference.parseVariantProperties(json);
        assertEquals(Set.of("0"), out.get("age"));

        // multipart 没有 variants：推断保持无属性
        assertTrue(BlockStatePropertyInference
                .parseVariantProperties("{ \"multipart\": [ { \"when\": {\"age\": \"0\"} } ] }")
                .isEmpty());
    }

    @Test
    void toleratesMalformedJson() {
        assertTrue(BlockStatePropertyInference.parseVariantProperties("{ not json").isEmpty());
        assertTrue(BlockStatePropertyInference.parseVariantProperties("").isEmpty());
        // "age=" 是畸形键（空值）：整个键被跳过，age 不出现在结果里
        Map<String, Set<String>> out = BlockStatePropertyInference
                .parseVariantProperties("{ \"variants\": { \"age=\": { \"model\": \"x\" } } }");
        assertTrue(out.isEmpty() || !out.containsKey("age"));
    }
}
