package org.loader.runtime.tick;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.event.TickEvent;
import org.loader.api.event.TickPhase;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.service.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tick 事件桥接 —— 「引擎 tick → 平台总线 → Mod 监听器」这条链是否真的通。
 *
 * <p>这是全平台最容易断的一环：引擎在 runtime 模块、总线在另一个组件，
 * 中间没有任何编译期约束要求它们相连。断了的表现是「Mod 订阅了 tick
 * 但永远收不到」，日志里零条记录。
 */
class TickEventBridgeTest {

    @Test
    @DisplayName("每个 tick 发布 START 与 END 两条事件，序号递增")
    void publishesStartAndEndPerTick() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        EventBus bus = new EventBus("bus", root);
        TickEngine engine = new TickEngine("engine", root);
        TickEventBridge.install(engine, root, bus);

        List<TickEvent> events = new ArrayList<>();
        bus.addListener(TickEvent.class, events::add);

        for (int i = 0; i < 3; i++) {
            var contract = engine.beginTick();
            engine.endTick(contract);
        }

        assertEquals(6, events.size(), "3 个 tick × (START + END)");
        assertEquals(List.of(1L, 1L, 2L, 2L, 3L, 3L),
                events.stream().map(TickEvent::tick).toList());
        assertEquals(TickPhase.START, events.get(0).phase());
        assertEquals(TickPhase.END, events.get(1).phase());
    }

    @Test
    @DisplayName("没人订阅时不构造事件对象 —— 零订阅零成本")
    void noListenersNoAllocation() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        EventBus bus = new EventBus("bus", root);
        TickEngine engine = new TickEngine("engine", root);
        TickEventBridge bridge = TickEventBridge.install(engine, root, bus);

        var contract = engine.beginTick();
        engine.endTick(contract);

        assertEquals(0, bridge.publishedCount(),
                "没有监听器时不应发布（连对象都不该分配）");
    }

    @Test
    @DisplayName("END 事件带上本 tick 的实际耗时")
    void endEventCarriesDuration() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        EventBus bus = new EventBus("bus", root);
        TickEngine engine = new TickEngine("engine", root);
        TickEventBridge.install(engine, root, bus);

        List<TickEvent> events = new ArrayList<>();
        bus.addListener(TickEvent.class, events::add);

        var contract = engine.beginTick();
        engine.endTick(contract);

        TickEvent end = events.stream().filter(TickEvent::isEnd).findFirst().orElseThrow();
        assertTrue(end.durationNanos() >= 0);
        assertEquals(0L, events.stream().filter(e -> e.phase() == TickPhase.START)
                .findFirst().orElseThrow().durationNanos(),
                "START 时本 tick 还没跑，耗时必须为 0");
    }

    @Test
    @DisplayName("关闭桥接后不再发布")
    void closedBridgeStopsPublishing() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        EventBus bus = new EventBus("bus", root);
        TickEngine engine = new TickEngine("engine", root);
        TickEventBridge bridge = TickEventBridge.install(engine, root, bus);

        AtomicInteger hits = new AtomicInteger();
        bus.addListener(TickEvent.class, e -> hits.incrementAndGet());

        var c1 = engine.beginTick();
        engine.endTick(c1);
        int before = hits.get();

        bridge.close();
        var c2 = engine.beginTick();
        engine.endTick(c2);

        assertEquals(before, hits.get(), "桥接关闭后不得继续发布");
    }

    @Test
    @DisplayName("引擎关闭时观测者被清空 —— 不留 Mod 引用")
    void engineCloseClearsObservers() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        EventBus bus = new EventBus("bus", root);
        TickEngine engine = new TickEngine("engine", root);
        TickEventBridge.install(engine, root, bus);

        assertTrue(engine.listenerCount() > 0);
        engine.close();
        assertEquals(0, engine.listenerCount(),
                "关闭后监听器/观测者必须清空，否则会把 Mod 的类加载器钉在堆上");
    }
}
