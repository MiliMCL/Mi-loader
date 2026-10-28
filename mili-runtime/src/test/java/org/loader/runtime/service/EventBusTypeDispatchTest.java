package org.loader.runtime.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.kernel.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件总线的类型匹配与错误可见性。
 *
 * <p>核心断言：<b>订阅一个超类型，必须收到子类型事件</b>。
 * 旧实现只按 {@code event.getClass()} 精确匹配，于是
 * {@code subscribe(Object.class, ...)} 订阅成功、编译通过、运行无异常 ——
 * 然后永远收不到任何事件。这是本平台最讨厌的失效形态。
 */
class EventBusTypeDispatchTest {

    interface BaseEvent {
    }

    record SubEvent(int n) implements BaseEvent {
    }

    record OtherEvent(String s) {
    }

    @Test
    @DisplayName("超类型监听器能收到子类型事件，且先收到精确类型")
    void supertypeListenerReceivesSubtype() {
        Scope scope = new Scope("s", null);
        EventBus bus = new EventBus("bus", scope);
        List<String> order = new ArrayList<>();

        bus.addListener(BaseEvent.class, e -> order.add("base"));
        bus.addListener(SubEvent.class, e -> order.add("exact"));

        bus.post(new SubEvent(1));

        assertEquals(List.of("exact", "base"), order,
                "精确类型必须先于超类型 —— 具体处理器先于兜底处理器");
    }

    @Test
    @DisplayName("不相关的类型不会被误派发")
    void unrelatedTypeNotDispatched() {
        Scope scope = new Scope("s", null);
        EventBus bus = new EventBus("bus", scope);
        AtomicInteger hits = new AtomicInteger();

        bus.addListener(BaseEvent.class, e -> hits.incrementAndGet());
        bus.post(new OtherEvent("x"));

        assertEquals(0, hits.get());
    }

    @Test
    @DisplayName("hasListeners 计入超类型监听器")
    void hasListenersIncludesSupertypes() {
        Scope scope = new Scope("s", null);
        EventBus bus = new EventBus("bus", scope);

        assertFalse(bus.hasListeners(SubEvent.class));
        bus.addListener(BaseEvent.class, e -> {
        });
        assertTrue(bus.hasListeners(SubEvent.class),
                "订阅了基类就等于订阅了子类 —— hasListeners 必须一致");
    }

    @Test
    @DisplayName("监听器异常被送到 errorSink，且不中断后续监听器")
    void listenerFailureGoesToSink() {
        Scope scope = new Scope("s", null);
        EventBus bus = new EventBus("bus", scope);
        List<String> errors = new ArrayList<>();
        AtomicInteger survived = new AtomicInteger();

        bus.setErrorSink((busId, listener, event, error) ->
                errors.add(event.getClass().getSimpleName() + ":" + error.getMessage()));
        bus.addListener(String.class, e -> {
            throw new IllegalStateException("boom");
        });
        bus.addListener(String.class, e -> survived.incrementAndGet());

        bus.post("hello");

        assertEquals(1, errors.size(), "异常必须被记录，不能静默吞掉");
        assertEquals("String:boom", errors.get(0));
        assertEquals(1, survived.get(), "一个监听器抛错不得影响其他监听器");
    }

    @Test
    @DisplayName("同一监听器注册到两个类型时，退订互不干扰")
    void unsubscribeIsPerType() {
        Scope scope = new Scope("s", null);
        EventBus bus = new EventBus("bus", scope);
        AtomicInteger hits = new AtomicInteger();
        EventBus.EventListener<Object> listener = e -> hits.incrementAndGet();
        @SuppressWarnings("unchecked")
        EventBus.EventListener<String> asString =
                (EventBus.EventListener<String>) (EventBus.EventListener<?>) listener;

        bus.addListener(String.class, asString);
        bus.addListener(Object.class, listener);

        bus.removeListener(String.class, asString);

        bus.post("a");
        // Object 上的那一份仍然存在
        assertEquals(1, hits.get());
    }

    @Test
    @DisplayName("关闭后不再派发，且拒绝新订阅")
    void closedBusRejectsNewListeners() {
        Scope scope = new Scope("s", null);
        EventBus bus = new EventBus("bus", scope);
        AtomicInteger hits = new AtomicInteger();
        bus.addListener(String.class, e -> hits.incrementAndGet());

        bus.close();
        bus.post("x");

        assertEquals(0, hits.get());
        assertThrows(IllegalStateException.class,
                () -> bus.addListener(String.class, (String e) -> {
                }));
    }
}
