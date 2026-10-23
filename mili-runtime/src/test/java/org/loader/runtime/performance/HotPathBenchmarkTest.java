package org.loader.runtime.performance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.service.EventBus;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件总线热路径的吞吐与分配测量。
 *
 * <h2>这不是 benchmark，是「回归护栏」</h2>
 * 真正的性能基准需要 JMH（fork、预热、统计置信区间），代价是引入一个
 * 完整框架。这里只需要回答一个二值问题：<b>分发路径还有没有额外分配？</b>
 *
 * <p>第一版用 {@code CopyOnWriteArrayList}，每次 {@code post()} 分配一个
 * {@code COWIterator}。改成不可变数组后，单次分发的分配应当降到接近 0。
 * 这条断言的意义在于：如果哪天有人把实现改回「每次 new 一个列表副本」
 * 或者在里面加了个 {@code stream()}，分配量会立刻从 0 跳到几十字节，
 * 测试变红 —— 而不是等到玩家报告「玩久了会卡」。
 *
 * <h2>阈值为什么这么松</h2>
 * CI 机器负载不可控，绝对耗时阈值会随机失败。这里只断言
 * <b>分配量</b>（确定性）和一个非常宽松的吞吐下限（1 亿次/秒量级
 * 的机器上，100 万次应该远低于 2 秒）。
 */
class HotPathBenchmarkTest {

    private static final int WARMUP = 200_000;
    private static final int MEASURED = 1_000_000;

    private static EventBus newBus() {
        Runtime runtime = Runtime.create("bench");
        Scope scope = runtime.rootScope().createChild("bench");
        return new EventBus("bench", scope);
    }

    @Test
    @DisplayName("post 在热路径上不产生可测量的堆分配")
    void postDoesNotAllocate() {
        com.sun.management.ThreadMXBean tmx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!tmx.isThreadAllocatedMemorySupported()) {
            // 非 HotSpot 或未开启 —— 跳过而不是失败，这是环境的限制
            return;
        }

        EventBus bus = newBus();
        bus.addListener(String.class, s -> { });
        bus.addListener(String.class, s -> { });

        String event = "tick";
        for (int i = 0; i < WARMUP; i++) {
            bus.post(event);
        }

        long before = tmx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < MEASURED; i++) {
            bus.post(event);
        }
        long after = tmx.getCurrentThreadAllocatedBytes();

        long perPost = (after - before) / MEASURED;
        System.out.println("[bench] post 平均分配: " + perPost + " B/次 ("
                + (after - before) + " B / " + MEASURED + " 次)");

        // 允许极小的噪声（JIT 退优化、安全点记录）。真正的分配型实现
        // 每次至少 16~40 B（迭代器对象 + 可能的 lambda 捕获）。
        assertTrue(perPost < 8,
                "post() 每次分配 " + perPost + " B —— 分发路径被引入了对象分配，"
                        + "这会让 GC 压力随事件频率线性增长");
    }

    @Test
    @DisplayName("100 万次分发的耗时在可接受范围内")
    void postThroughput() {
        EventBus bus = newBus();
        bus.addListener(String.class, s -> { });

        for (int i = 0; i < WARMUP; i++) {
            bus.post("t");
        }

        long start = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) {
            bus.post("t");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        System.out.println("[bench] " + MEASURED + " 次 post 耗时: " + elapsedMs + " ms ("
                + (MEASURED * 1000L / Math.max(1, elapsedMs)) + " ops/s)");

        // 极宽松：按 60fps、每帧 50 个事件算，一天也只有 2.6 亿次。
        // 这里 100 万次超过 2 秒就说明实现退化到了不可用的量级。
        assertTrue(elapsedMs < 2000,
                "100 万次 post 耗时 " + elapsedMs + " ms —— 分发路径异常缓慢");
    }

    @Test
    @DisplayName("无监听器时 post 的代价接近空方法")
    void postWithoutListenersIsNearlyFree() {
        EventBus bus = newBus();
        for (int i = 0; i < WARMUP; i++) {
            bus.post("t");
        }

        long start = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) {
            bus.post("t");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        System.out.println("[bench] 空总线 " + MEASURED + " 次 post: " + elapsedMs + " ms");
        assertTrue(elapsedMs < 1000,
                "空总线的 post 不该有可见开销，实测 " + elapsedMs + " ms");
    }
}
