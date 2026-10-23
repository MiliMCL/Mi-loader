package org.loader.api.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PerformanceSnapshot} 的判读语义。
 *
 * <p>这些阈值会直接决定面板是否把一次卡顿显示成「正常」。测试固定住
 * 三档边界与文本格式，避免有人调整阈值时把「明显掉帧」判成 GOOD。
 */
class PerformanceSnapshotTest {

    private static PerformanceSnapshot snapshot(double tps5s, double p95,
                                                int fps, long ageMs) {
        return new PerformanceSnapshot(
                1_000, 300, ageMs,
                20.0, tps5s, 19.8,
                1.2, 1.0, p95, 8.0, 12.0,
                3,
                fps, 48, 58.0, 20.0, 300,
                60_000);
    }

    @Test
    @DisplayName("达标读数判为 GOOD")
    void healthyIsGood() {
        assertEquals(PerformanceSnapshot.Health.GOOD,
                snapshot(19.9, 3.4, 60, 20).health());
    }

    @Test
    @DisplayName("TPS 掉到 16、p95 55ms 判为 DEGRADED")
    void mildStutterIsDegraded() {
        assertEquals(PerformanceSnapshot.Health.DEGRADED,
                snapshot(16.0, 55.0, 42, 20).health());
    }

    @Test
    @DisplayName("TPS 掉到 10 判为 POOR")
    void heavyStutterIsPoor() {
        assertEquals(PerformanceSnapshot.Health.POOR,
                snapshot(10.0, 80.0, 25, 20).health());
    }

    @Test
    @DisplayName("没有 tick（主菜单 / 暂停）判为 UNKNOWN，而不是 0 TPS")
    void noTickDataIsUnknown() {
        assertEquals(PerformanceSnapshot.Health.UNKNOWN,
                PerformanceSnapshot.empty().health());
        // 有样本但最近一次 tick 已过去 10 秒 —— 世界没在跑
        assertEquals(PerformanceSnapshot.Health.UNKNOWN,
                snapshot(20.0, 3.0, 60, 10_000).health());
    }

    @Test
    @DisplayName("文本格式固定，面板列宽不会漂移")
    void textFormatting() {
        PerformanceSnapshot s = snapshot(19.9, 3.4, 61, 20);
        assertEquals("20.0 / 19.9", s.tpsText());
        assertEquals("1.2 / 3.4 / 12.0", s.tickText());
        assertEquals("61 (5s 最低 48)", s.fpsText());
        assertEquals("3 次", s.deadlineText());

        PerformanceSnapshot empty = PerformanceSnapshot.empty();
        assertEquals("—", empty.tpsText());
        assertEquals("—", empty.tickText());
        assertEquals("—", empty.fpsText());
        assertEquals("0", empty.deadlineText());
        assertFalse(empty.hasTickData());
        assertTrue(s.hasTickData());
    }
}
