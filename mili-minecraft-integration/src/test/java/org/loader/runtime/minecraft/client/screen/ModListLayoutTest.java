package org.loader.runtime.minecraft.client.screen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mod 列表面板的几何布局测试。
 *
 * <h2>为什么要测一个「算坐标」的函数</h2>
 * 这类代码的错误不会抛异常，只会让界面在某个分辨率下错位 ——
 * 而玩家不会报告「面板右边缘超出屏幕 3 像素」，他们只会说
 * 「UI 坏了」。真实世界里窗口尺寸从 320×240（小窗）到 3840×2160
 * （4K）都有可能，靠手动试几个尺寸远远不够。
 *
 * <p>断言分三类：<b>不越界</b>、<b>内部元素互不重叠</b>、
 * <b>列表区高度为正</b>。这三条覆盖了「界面还能用」的最低标准。
 */
class ModListLayoutTest {

    @ParameterizedTest(name = "{0}x{1}")
    @CsvSource({
            "320,240",
            "640,480",
            "854,480",
            "1280,720",
            "1920,1080",
            "2560,1440",
            "3840,2160",
            "500,300"
    })
    @DisplayName("面板在任何分辨率下都不越界")
    void panelStaysInsideScreen(int width, int height) {
        ModListScreenDispatch.Layout l = ModListScreenDispatch.Layout.of(width, height);

        assertTrue(l.panelX() >= 0, "panelX 为负: " + l.panelX());
        assertTrue(l.panelY() >= 0, "panelY 为负: " + l.panelY());
        assertTrue(l.panelX() + l.panelW() <= width,
                "面板右边缘越界: " + (l.panelX() + l.panelW()) + " > " + width);
        assertTrue(l.panelY() + l.panelH() <= height,
                "面板下边缘越界: " + (l.panelY() + l.panelH()) + " > " + height);
    }

    @ParameterizedTest(name = "{0}x{1}")
    @CsvSource({"320,240", "640,480", "1280,720", "1920,1080", "3840,2160"})
    @DisplayName("列表区高度为正且不压到底栏")
    void listAreaIsPositive(int width, int height) {
        ModListScreenDispatch.Layout l = ModListScreenDispatch.Layout.of(width, height);

        assertTrue(l.listH() > 0, "列表高度非正: " + l.listH());
        assertTrue(l.listY() + l.listH() <= l.footerY(),
                "列表压到底栏: " + (l.listY() + l.listH()) + " > " + l.footerY());
    }

    @ParameterizedTest(name = "{0}x{1}")
    @CsvSource({"640,480", "1280,720", "1920,1080", "3840,2160"})
    @DisplayName("搜索框、列表、关闭按钮互不重叠")
    void regionsDoNotOverlap(int width, int height) {
        ModListScreenDispatch.Layout l = ModListScreenDispatch.Layout.of(width, height);

        // 搜索框在列表之上
        assertTrue(l.searchY() + l.searchH() <= l.listY(),
                "搜索框与列表重叠: " + (l.searchY() + l.searchH()) + " > " + l.listY());

        // 关闭按钮在标题行内，且不与搜索框重叠
        assertTrue(l.closeY() + l.closeSize() <= l.searchY(),
                "关闭按钮压到搜索框");

        // 关闭按钮在面板右侧内部
        assertTrue(l.closeX() + l.closeSize() <= l.panelX() + l.panelW(),
                "关闭按钮超出面板右边缘");
        assertTrue(l.closeX() >= l.panelX(),
                "关闭按钮超出面板左边缘");
    }

    @Test
    @DisplayName("行命中测试：列表外返回 -1，列表内按下标换算")
    void rowHitTesting() {
        ModListScreenDispatch.Layout l = ModListScreenDispatch.Layout.of(1280, 720);

        // 列表上方
        assertEquals(-1, l.rowAt(l.listY() - 5, 0));
        // 列表下方
        assertEquals(-1, l.rowAt(l.listY() + l.listH() + 5, 0));
        // 第一行
        assertEquals(0, l.rowAt(l.listY() + 1, 0));
        // 第二行（行高 34）
        assertEquals(1, l.rowAt(l.listY() + 34 + 1, 0));
        // 滚动 34px 后，列表顶部对应的是第 1 行
        assertEquals(1, l.rowAt(l.listY() + 1, 34));
    }

    @Test
    @DisplayName("超小窗口不会产生负尺寸")
    void tinyWindowDoesNotProduceNegativeSizes() {
        ModListScreenDispatch.Layout l = ModListScreenDispatch.Layout.of(200, 120);

        assertTrue(l.panelW() > 0);
        assertTrue(l.panelH() > 0);
        assertTrue(l.listH() > 0);
        assertTrue(l.searchW() > 0);
    }

    @Test
    @DisplayName("文本截断：宽度足够时原样返回，不足时以省略号收尾")
    void ellipsizeNeverExceedsBudget() {
        // 用等宽估算代替 Font（单测里没有 Minecraft）
        String longText = "a-very-long-mod-id-that-will-not-fit-in-the-row";
        String cut = ModListScreenDispatch.ellipsize(null, longText, 60);
        assertTrue(cut.endsWith("..."), "应带省略号: " + cut);
        assertTrue(cut.length() < longText.length(), "应被截断: " + cut);

        assertEquals("", ModListScreenDispatch.ellipsize(null, "", 60));
        assertEquals("", ModListScreenDispatch.ellipsize(null, "abc", 0));
    }
}
