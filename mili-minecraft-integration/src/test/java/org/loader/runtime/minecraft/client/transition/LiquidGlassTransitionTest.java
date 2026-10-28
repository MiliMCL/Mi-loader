package org.loader.runtime.minecraft.client.transition;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 液态玻璃转场状态机 —— 行为契约测试。
 *
 * <h2>测什么、不测什么</h2>
 * 状态机是时间的纯函数，所以这里<b>直接驱动时刻</b>断言几何与信号，
 * 不 sleep、不 mock 时钟。刻意不测具体缓动曲线的每个点 ——
 * 那是把实现抄进测试；只测契约点：边界值、单调段中点、
 * 恰好一次信号、超时强制收尾。
 */
class LiquidGlassTransitionTest {

    private static final long MS = 1_000_000L;

    @AfterEach
    void clearProperties() {
        System.clearProperty("mili.menu.transition.speed");
        System.clearProperty("mili.menu.transition.cover");
        System.clearProperty("mili.menu.transition.drain");
        System.clearProperty("mili.menu.transition.wave");
    }

    // ── 阶段推进 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("涌起期：进度按缓动推进，换屏信号始终为 false")
    void coverPhaseTimeline() {
        LiquidGlassTransition t = LiquidGlassTransition.start(
                LiquidGlassTransition.Params.DEFAULT, 0L, 213, 150, 426, 240);

        LiquidGlassTransition.Frame f0 = t.sample(0L);
        assertEquals(LiquidGlassTransition.Phase.COVER, f0.phase());
        assertEquals(0.0, f0.p(), 1e-9);
        assertFalse(f0.swapDue());

        // easeInOutCubic(0.5) = 0.5 —— 对称缓动的中点恒等
        LiquidGlassTransition.Frame mid = t.sample(150 * MS);
        assertEquals(0.5, mid.p(), 1e-9);
        assertFalse(mid.swapDue());

        LiquidGlassTransition.Frame edge = t.sample(299 * MS);
        assertEquals(LiquidGlassTransition.Phase.COVER, edge.phase());
        assertFalse(edge.swapDue(), "涌起未完成前绝不能发换屏信号");
    }

    @Test
    @DisplayName("换屏信号在涌起边界恰好发一次")
    void swapFiresExactlyOnce() {
        LiquidGlassTransition t = LiquidGlassTransition.start(
                LiquidGlassTransition.Params.DEFAULT, 0L, 213, 150, 426, 240);

        LiquidGlassTransition.Frame at300 = t.sample(300 * MS);
        assertEquals(LiquidGlassTransition.Phase.COVER, at300.phase(),
                "边界时刻仍属涌起（p=1），换屏在下一帧");
        LiquidGlassTransition.Frame at301 = t.sample(301 * MS);
        assertTrue(at301.swapDue(), "进入泻落的第一帧必须发换屏信号");
        assertEquals(LiquidGlassTransition.Phase.DRAIN, at301.phase());
        LiquidGlassTransition.Frame at302 = t.sample(302 * MS);
        assertFalse(at302.swapDue(), "换屏信号绝不能重复");
    }

    @Test
    @DisplayName("泻落期：圆心按 easeInQuad 下移，末帧 done")
    void drainPhaseTimeline() {
        LiquidGlassTransition t = LiquidGlassTransition.start(
                LiquidGlassTransition.Params.DEFAULT, 0L, 213, 100, 426, 240);
        t.sample(300 * MS);
        // 泻落期的半径恒为 Rmax（呼吸脉动只在涌起期）—— 用包级静态函数
        // 重算期望值，避免从涌起帧读出带脉动的半径。
        int rmax = LiquidGlassTransition.computeRmax(213, 100, 426, 240);

        LiquidGlassTransition.Frame half = t.sample(300 * MS + 180 * MS);
        assertEquals(LiquidGlassTransition.Phase.DRAIN, half.phase());
        assertEquals(0.25, half.p(), 1e-9, "easeInQuad(0.5) = 0.25");
        int cyHalf = 100 + (int) Math.round(0.25 * (2.0 * rmax + 240 + 40));
        assertEquals(cyHalf, half.cy(), "圆心 Y 必须严格按 q 线性映射");

        LiquidGlassTransition.Frame end = t.sample(660 * MS);
        assertTrue(end.done());
        assertEquals(100 + 2 * rmax + 240 + 40, end.cy());
        LiquidGlassTransition.Frame after = t.sample(700 * MS);
        assertTrue(after.done(), "done 之后保持 done");
    }

    @Test
    @DisplayName("硬超时：expired+done，不再等待换屏")
    void hardExpiry() {
        LiquidGlassTransition t = LiquidGlassTransition.start(
                LiquidGlassTransition.Params.DEFAULT, 0L, 213, 150, 426, 240);
        // 2×(300+360)+200 = 1520ms 之后必须强制收尾
        LiquidGlassTransition.Frame f = t.sample(1600 * MS);
        assertTrue(f.expired());
        assertTrue(f.done());
        assertTrue(t.isEnded());
    }

    // ── 几何 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Rmax 覆盖最远角并带 40px 余量")
    void rmaxCoversFarthestCorner() {
        int rmax = LiquidGlassTransition.computeRmax(10, 10, 426, 240);
        double farthest = Math.hypot(426 - 10, 240 - 10);
        assertEquals((int) Math.ceil(farthest) + 40, rmax);

        LiquidGlassTransition t = LiquidGlassTransition.start(
                LiquidGlassTransition.Params.DEFAULT, 0L, 10, 10, 426, 240);
        LiquidGlassTransition.Frame coverEnd = t.sample(299 * MS);
        assertTrue(coverEnd.r() >= rmax - 1,
                "呼吸脉动最多吃掉 ~0.2% 半径，余量 40px 必须吸收");
    }

    @Test
    @DisplayName("圆盘弦：圆心列给出 [cy-r, cy+r]，弦外无值")
    void discChordGeometry() {
        LiquidGlassTransition t = LiquidGlassTransition.start(
                LiquidGlassTransition.Params.DEFAULT, 0L, 0, 0, 426, 240);
        LiquidGlassTransition.Frame f = t.sample(0L);
        // p=0 时 R=1：圆心列的弦就是 [cy-1, cy+1]
        assertEquals(-1, t.discTopY(f, 0));
        assertEquals(1, t.discBottomY(f, 0));
        assertEquals(Integer.MIN_VALUE, t.discTopY(f, 2), "x=2 已在 R=1 圆外");
        assertEquals(Integer.MAX_VALUE, t.discBottomY(f, 2));
    }

    @Test
    @DisplayName("液面 Y 贴着圆盘顶弧，偏差不超过波幅")
    void liquidYHugsArc() {
        LiquidGlassTransition t = LiquidGlassTransition.start(
                LiquidGlassTransition.Params.DEFAULT, 0L, 213, 100, 426, 240);
        LiquidGlassTransition.Frame f = t.sample(400 * MS);
        long maxDeviation = 0;
        for (int x = 0; x <= 426; x += 7) {
            long dx = x - f.cx();
            long r = f.r();
            double arc = Math.abs(dx) >= r
                    ? f.cy() : f.cy() - Math.sqrt((double) r * r - (double) dx * dx);
            long dev = Math.abs(t.liquidY(f, x, 400 * MS) - Math.round(arc));
            maxDeviation = Math.max(maxDeviation, dev);
        }
        assertTrue(maxDeviation <= LiquidGlassTransition.Params.DEFAULT.waveAmplitudePx(),
                "液面只能偏离顶弧 ±波幅，实测 " + maxDeviation);
    }

    // ── 参数钳制 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("系统属性越界被钳制：speed 下限 0.6，wave 上限 14")
    void paramsAreClamped() {
        System.setProperty("mili.menu.transition.speed", "0.1");
        System.setProperty("mili.menu.transition.wave", "99");
        LiquidGlassTransition.Params p = LiquidGlassTransition.Params.fromProperties();
        // speed 钳到 0.6 → 300/0.6 = 500
        assertEquals(500, p.coverMillis());
        assertEquals(14, p.waveAmplitudePx());
    }

    @Test
    @DisplayName("属性缺失时使用定稿参数 300/360/6")
    void defaultsWhenNoProperties() {
        LiquidGlassTransition.Params p = LiquidGlassTransition.Params.fromProperties();
        assertEquals(300, p.coverMillis());
        assertEquals(360, p.drainMillis());
        assertEquals(6, p.waveAmplitudePx());
    }

    @Test
    @DisplayName("垃圾属性字符串回退默认值，不抛异常")
    void garbagePropertyFallsBack() {
        System.setProperty("mili.menu.transition.speed", "abc");
        LiquidGlassTransition.Params p = LiquidGlassTransition.Params.fromProperties();
        assertEquals(300, p.coverMillis());
    }

    // ── 缓动曲线契约点 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("缓动：定义域钳制与中点恒等")
    void easingContracts() {
        assertEquals(0.0, LiquidGlassTransition.easeInOutCubic(0.0), 1e-12);
        assertEquals(0.5, LiquidGlassTransition.easeInOutCubic(0.5), 1e-12);
        assertEquals(1.0, LiquidGlassTransition.easeInOutCubic(1.0), 1e-12);
        assertEquals(1.0, LiquidGlassTransition.easeInOutCubic(1.5), 1e-12, "越界钳到 1");
        assertEquals(0.0, LiquidGlassTransition.easeInQuad(-0.5), 1e-12, "越界钳到 0");
        assertEquals(0.25, LiquidGlassTransition.easeInQuad(0.5), 1e-12);
    }
}
