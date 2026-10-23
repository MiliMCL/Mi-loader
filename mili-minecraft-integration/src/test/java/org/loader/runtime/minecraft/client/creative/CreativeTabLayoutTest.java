package org.loader.runtime.minecraft.client.creative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 创造栏页签布局的纯算术测试。
 *
 * <p>原版 7 个列位不能被改变：这里逐条钉住 {@code 27 * column} 与
 * 右对齐公式；Mod 页签的哨兵列必须落在面板右侧，不能覆盖原版页签。
 */
class CreativeTabLayoutTest {

    private static final int IMAGE_WIDTH = 195;

    @Test
    @DisplayName("原版列 0..6 的坐标与 26.2 公式完全一致")
    void vanillaColumnsUnchanged() {
        for (int column = 0; column <= 4; column++) {
            assertEquals(27 * column,
                    CreativeTabLayout.tabX(IMAGE_WIDTH, column, false));
        }
        // 右对齐：imageWidth - 27 * (7 - column) + 1
        assertEquals(195 - 27 * (7 - 5) + 1,
                CreativeTabLayout.tabX(IMAGE_WIDTH, 5, true));
        assertEquals(195 - 27 * (7 - 6) + 1,
                CreativeTabLayout.tabX(IMAGE_WIDTH, 6, true));
    }

    @Test
    @DisplayName("Mod 页签从面板右侧 200px 开始，偶数/奇数索引共用一个 x")
    void modColumnsGoRight() {
        int base = CreativeTabLayout.MOD_COLUMN_BASE;
        assertEquals(200, CreativeTabLayout.tabX(IMAGE_WIDTH, base, false));
        assertEquals(200, CreativeTabLayout.tabX(IMAGE_WIDTH, base + 1, false),
                "索引 0/1 分别在 TOP/BOTTOM 行，x 可以相同");
        assertEquals(227, CreativeTabLayout.tabX(IMAGE_WIDTH, base + 2, false));
        assertEquals(227, CreativeTabLayout.tabX(IMAGE_WIDTH, base + 3, false));
    }

    @Test
    @DisplayName("clampSprite 把原版的上界从 length 收到 length-1")
    void clampSpriteAvoidsAioobe() {
        // 原版 sprite 数组长度 7；列号 100 在原版 clamp 下会返回 7 → 越界
        assertEquals(6, CreativeTabLayout.clampSprite(100, 0, 7));
        for (int column = 0; column < 7; column++) {
            assertEquals(column, CreativeTabLayout.clampSprite(column, 0, 7),
                    "原版列号的行为必须保持不变");
        }
    }
}
