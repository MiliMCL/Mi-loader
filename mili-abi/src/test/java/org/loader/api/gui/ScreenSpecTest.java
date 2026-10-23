package org.loader.api.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScreenSpec} 的暂停/HUD 语义。
 *
 * <p>默认值必须保持「像原版 Screen 一样暂停」—— 绝大多数 Mod 界面
 * （配置、确认框）在单人游戏里暂停是正确的。诊断面板显式关闭暂停，
 * 否则它会把被观测的 tick 循环停掉。
 */
class ScreenSpecTest {

    @Test
    @DisplayName("默认暂停、非 HUD —— 与原版 Screen 行为一致")
    void defaultsPreserveVanillaBehavior() {
        ScreenSpec spec = ScreenSpec.builder("test:config", "Config")
                .label("a", "hello", 0, 0)
                .build();

        assertTrue(spec.pauseGame());
        assertFalse(spec.hudMode());
    }

    @Test
    @DisplayName("诊断面板可显式关闭暂停并启用 HUD 背景")
    void diagnosticPanelCanOptOut() {
        ScreenSpec spec = ScreenSpec.builder("test:perf", "Perf")
                .size(280, 90)
                .pauseGame(false)
                .hudMode(true)
                .label("l1", "TPS 20.0", 10, 8)
                .build();

        assertFalse(spec.pauseGame());
        assertTrue(spec.hudMode());
        assertTrue(spec.width() == 280 && spec.height() == 90);
    }

    @Test
    @DisplayName("重复 widget id 仍在构建期拒绝")
    void duplicateWidgetIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                ScreenSpec.builder("test:dup", "Dup")
                        .label("x", "a", 0, 0)
                        .label("x", "b", 0, 20)
                        .build());
    }

    @Test
    @DisplayName("id 必须是 modid:name")
    void idMustBeNamespaced() {
        assertThrows(IllegalArgumentException.class,
                () -> ScreenSpec.builder("no-namespace", "X"));
    }
}
