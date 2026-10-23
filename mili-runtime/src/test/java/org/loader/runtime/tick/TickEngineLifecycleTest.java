package org.loader.runtime.tick;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.diagnostics.PerformanceMonitor;
import org.loader.runtime.kernel.Scope;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TickEngine} 的关闭语义 —— 关闭必须「断引用」，而不只是「不再 tick」。
 *
 * <p>引擎是 Scope 的资源，Mod 通过 {@code onPreTick(...)} 把 lambda 注册进来。
 * 如果 {@code close()} 只把 {@code closed} 置位，这些 lambda 仍然挂在
 * 引擎的列表里，整条 Mod 对象图（以及它的 ClassLoader）都不可回收。
 */
class TickEngineLifecycleTest {

    @Test
    @DisplayName("关闭后监听器被清空，且不再接受新 tick")
    void closeReleasesListeners() {
        Scope scope = new Scope("tick-root", null);
        TickEngine engine = new TickEngine("engine", scope);
        AtomicInteger calls = new AtomicInteger();

        engine.onPreTick((contract, phase) -> calls.incrementAndGet());
        engine.onTickEnd((contract, phase) -> calls.incrementAndGet());
        assertEquals(2, engine.listenerCount());

        TickContract contract = engine.beginTick();
        engine.endTick(contract);
        assertEquals(2, calls.get());

        engine.close();

        assertEquals(0, engine.listenerCount(), "关闭后监听器必须被清空");
        assertNull(engine.currentContract());
        assertThrows(IllegalStateException.class, engine::beginTick);
    }

    @Test
    @DisplayName("关闭会取消未完成的 tick 契约")
    void closeCancelsActiveContract() {
        Scope scope = new Scope("tick-root", null);
        TickEngine engine = new TickEngine("engine", scope);
        TickContract contract = engine.beginTick();

        engine.close();

        assertTrue(contract.isCancelled(), "未完成的契约应被取消");
        assertNull(engine.currentContract());
    }

    @Test
    @DisplayName("接入采样器后，每个 tick 都进入窗口")
    void recordsTicksIntoMonitor() {
        Scope scope = new Scope("tick-root", null);
        PerformanceMonitor monitor = new PerformanceMonitor("perf", scope);
        TickEngine engine = new TickEngine("engine", scope, monitor);

        for (int i = 0; i < 5; i++) {
            TickContract contract = engine.beginTick();
            engine.endTick(contract);
        }

        assertEquals(5, monitor.tickCount());
        assertEquals(5, monitor.snapshot().tickSamples());
    }
}
