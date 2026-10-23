package org.loader.runtime.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.diagnostics.PerformanceSnapshot;
import org.loader.runtime.kernel.Scope;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PerformanceMonitor} 的窗口计算与零分配护栏。
 *
 * <h2>为什么这些断言值得写</h2>
 * TPS 面板最容易出的问题不是「算错」，而是「算得太慢」：把每 tick 的样本
 * 塞进 {@code List<Long>}，再在渲染线程上排序，面板一打开就掉帧。
 * 这里同时守住两件事 —— 数值语义（窗口 / 百分位 / 超时）与
 * 稳态分配（每次采样 < 8 B）。
 */
class PerformanceMonitorTest {

    private static final long PERIOD = 50_000_000L;

    private static PerformanceMonitor monitor() {
        return new PerformanceMonitor("perf-test", new Scope("perf-root", null));
    }

    @Test
    @DisplayName("20 TPS 的合成 tick 序列在 1s/5s 窗口内读出 20 TPS")
    void tpsWindows() {
        PerformanceMonitor monitor = monitor();
        long lastEnd = System.nanoTime() - PERIOD;
        long firstStart = lastEnd - 99 * PERIOD;
        for (int i = 0; i < 100; i++) {
            long start = firstStart + i * PERIOD;
            monitor.recordTick(start, start + 1_000_000L);
        }

        PerformanceSnapshot s = monitor.snapshot();
        assertTrue(s.hasTickData(), "100 个 tick 之后应当有可解释的数据");
        assertEquals(20.0, s.tps5s(), 2.0, "5 秒窗口应接近 20 TPS");
        assertTrue(s.tps1s() > 15.0 && s.tps1s() < 25.0,
                "1 秒窗口应接近 20 TPS，实际 " + s.tps1s());
        assertEquals(1.0, s.averageTickMs(), 0.2, "均值应接近 1ms");
        assertEquals(0, s.missedDeadlines(), "1ms 的 tick 不该超时");
    }

    @Test
    @DisplayName("慢 tick 会体现在 p95 / 峰值 / 超时计数上")
    void slowTicksShowUpInPercentiles() {
        PerformanceMonitor monitor = monitor();
        long firstStart = System.nanoTime() - 119 * PERIOD - 100_000_000L;
        for (int i = 0; i < 100; i++) {
            long start = firstStart + i * PERIOD;
            monitor.recordTick(start, start + 2_000_000L);
        }
        for (int i = 100; i < 120; i++) {
            long start = firstStart + i * PERIOD;
            monitor.recordTick(start, start + 100_000_000L);
        }

        PerformanceSnapshot s = monitor.snapshot();
        assertEquals(120, s.tickSamples());
        assertTrue(s.p95TickMs() >= 90.0,
                "20/120 的慢 tick 应把 p95 拉到 90ms 以上，实际 " + s.p95TickMs());
        assertTrue(s.maxTickMs() >= 100.0, "峰值应为 100ms");
        assertTrue(s.missedDeadlines() >= 20, "20 个慢 tick 都应记为超时");
        assertTrue(s.hasTickData(), "最近一次 tick 刚结束，age 应可接受");
    }

    @Test
    @DisplayName("环形缓冲不随采样数增长，容量固定")
    void bufferIsBounded() {
        PerformanceMonitor monitor = monitor();
        long now = System.nanoTime();
        for (int i = 0; i < 5_000; i++) {
            long start = now - (5_000 - i) * 1_000_000L;
            monitor.recordTick(start, start + 500_000L);
        }
        assertEquals(PerformanceMonitor.TICK_CAPACITY, monitor.snapshot().tickSamples(),
                "样本数应封顶在环形缓冲容量");
    }

    @Test
    @DisplayName("帧采样给出最低帧率与帧耗时 p95")
    void frameSamples() {
        PerformanceMonitor monitor = monitor();
        for (int i = 0; i < 200; i++) {
            monitor.recordFrame(i == 42 ? 12 : 60, 16_000_000L);
        }
        PerformanceSnapshot s = monitor.snapshot();
        assertEquals(60, s.fps());
        assertEquals(12, s.minFps5s(), "5 秒窗口应记住那次掉到 12 帧");
        assertEquals(16.0, s.p95FrameMs(), 1.0);
    }

    @Test
    @DisplayName("reset 之后窗口与累计值都归零")
    void resetClearsWindows() {
        PerformanceMonitor monitor = monitor();
        long now = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            monitor.recordTick(now - (20 - i) * PERIOD, now - (20 - i) * PERIOD + 1_000_000L);
        }
        monitor.recordFrame(60, 16_000_000L);
        monitor.reset();

        PerformanceSnapshot s = monitor.snapshot();
        assertEquals(0, s.totalTicks());
        assertEquals(0, s.tickSamples());
        assertEquals(-1, s.fps());
        assertEquals(0, s.frameSamples());
        assertFalse(s.hasTickData());
    }

    @Test
    @DisplayName("recordTick 稳态不产生可测量的堆分配")
    void recordTickDoesNotAllocate() {
        com.sun.management.ThreadMXBean tmx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!tmx.isThreadAllocatedMemorySupported()) {
            return; // 环境不支持 —— 跳过而不是失败
        }
        PerformanceMonitor monitor = monitor();
        long base = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            monitor.recordTick(base + i, base + i + 1);
        }
        long before = tmx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 200_000; i++) {
            monitor.recordTick(base + i, base + i + 1);
        }
        long after = tmx.getCurrentThreadAllocatedBytes();
        long perTick = (after - before) / 200_000L;
        System.out.println("[perf] recordTick 平均分配: " + perTick + " B/次");
        assertTrue(perTick < 8,
                "recordTick 每次分配 " + perTick + " B —— 采样路径被引入了对象分配");
    }
}
