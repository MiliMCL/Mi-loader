package org.loader.runtime.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.kernel.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EventBus} 的分发语义测试。
 *
 * <h2>为什么这组测试值得单独存在</h2>
 * 事件总线被改成了「不可变数组 + 写时复制」。这类改写最容易在
 * 三处翻车，而且三处都不会在正常点测里暴露：
 * <ol>
 *   <li>删除监听器后数组下标错位 —— 表现为「删了还在收」或「删错人」；</li>
 *   <li>遍历线程与增删线程并发 —— 表现为偶发 {@code ArrayIndexOutOfBounds}
 *       或者少收一次事件；</li>
 *   <li>监听器抛异常打断整条链 —— 一个坏 Mod 拖垮所有 Mod 的事件。</li>
 * </ol>
 * 下面三条分别对着这三点。
 */
class EventBusDispatchTest {

    private static EventBus newBus(String id) {
        Runtime runtime = Runtime.create("test-" + id);
        Scope scope = runtime.rootScope().createChild("bus:" + id);
        return new EventBus(id, scope);
    }

    @Test
    @DisplayName("按类型精确分发：只调用匹配类型的监听器")
    void dispatchesOnlyMatchingType() {
        EventBus bus = newBus("typed");
        List<String> got = new ArrayList<>();

        bus.addListener(String.class, s -> got.add("str:" + s));
        bus.addListener(Integer.class, i -> got.add("int:" + i));

        bus.post("a");
        bus.post(42);
        bus.post("b");

        assertEquals(List.of("str:a", "int:42", "str:b"), got);
    }

    @Test
    @DisplayName("删除后不再收到；删中间那个不影响其余")
    void removeKeepsRemainingListenersIntact() {
        EventBus bus = newBus("remove");
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        AtomicInteger c = new AtomicInteger();

        EventBus.EventListener<String> la = s -> a.incrementAndGet();
        EventBus.EventListener<String> lb = s -> b.incrementAndGet();
        EventBus.EventListener<String> lc = s -> c.incrementAndGet();

        bus.addListener(String.class, la);
        bus.addListener(String.class, lb);
        bus.addListener(String.class, lc);

        bus.removeListener(String.class, lb);
        bus.post("x");

        assertEquals(1, a.get(), "第一个监听器应仍被调用");
        assertEquals(0, b.get(), "被删除的监听器不应被调用");
        assertEquals(1, c.get(), "第三个监听器应仍被调用 —— 删中间那个不能让后面的错位");
    }

    @Test
    @DisplayName("删光后再加，仍然只调用新加的")
    void removeAllThenAddAgain() {
        EventBus bus = newBus("readd");
        AtomicInteger old = new AtomicInteger();
        AtomicInteger fresh = new AtomicInteger();

        EventBus.EventListener<String> oldListener = s -> old.incrementAndGet();
        bus.addListener(String.class, oldListener);
        bus.removeListener(String.class, oldListener);
        bus.addListener(String.class, s -> fresh.incrementAndGet());

        bus.post("x");

        assertEquals(0, old.get());
        assertEquals(1, fresh.get());
    }

    @Test
    @DisplayName("单个监听器抛异常不影响后续监听器")
    void listenerExceptionDoesNotBreakChain() {
        EventBus bus = newBus("boom");
        List<String> got = new ArrayList<>();

        bus.addListener(String.class, s -> {
            throw new IllegalStateException("故意抛出的异常");
        });
        bus.addListener(String.class, s -> got.add(s));

        assertDoesNotThrow(() -> bus.post("survived"));
        assertEquals(List.of("survived"), got);
    }

    @Test
    @DisplayName("没有监听器时 post 是零成本空操作")
    void postWithoutListenersIsNoOp() {
        EventBus bus = newBus("empty");
        assertDoesNotThrow(() -> bus.post(new Object()));
        assertDoesNotThrow(() -> bus.post(null));
        assertEquals(0, bus.listenerCount());
    }

    @Test
    @DisplayName("诊断计数反映真实的监听器数量")
    void diagnosticsCountsListeners() {
        EventBus bus = newBus("count");
        bus.addListener(String.class, s -> {});
        bus.addListener(String.class, s -> {});
        bus.addListener(Integer.class, i -> {});

        assertEquals(2, bus.eventTypeCount());
        assertEquals(3, bus.listenerCount());
    }

    @Test
    @DisplayName("关闭后 post 静默返回，addListener 显式失败")
    void closedBusIsExplicit() {
        EventBus bus = newBus("closed");
        AtomicInteger calls = new AtomicInteger();
        bus.addListener(String.class, s -> calls.incrementAndGet());

        bus.close();

        bus.post("x");
        assertEquals(0, calls.get(), "关闭后不应再分发");

        assertThrows(IllegalStateException.class,
                () -> bus.addListener(String.class, s -> {}),
                "关闭后再注册监听器必须显式失败，不能静默无效");
    }

    @Test
    @DisplayName("并发增删与分发不会抛出结构性异常")
    void concurrentMutationDoesNotCorruptDispatch() throws Exception {
        EventBus bus = newBus("concurrent");
        bus.addListener(String.class, s -> {});

        // 写线程：反复增删
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 5000; i++) {
                EventBus.EventListener<String> l = s -> {};
                bus.addListener(String.class, l);
                bus.removeListener(String.class, l);
            }
        });
        // 读线程：反复发布
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 5000; i++) {
                bus.post("x");
            }
        });

        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);

        assertFalse(writer.isAlive(), "写线程超时 —— 可能是死锁");
        assertFalse(reader.isAlive(), "读线程超时 —— 可能是死锁");
    }
}
