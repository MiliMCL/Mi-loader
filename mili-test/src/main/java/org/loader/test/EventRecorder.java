package org.loader.test;

import org.loader.runtime.service.EventBus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件记录器 —— 订阅真实 {@link EventBus} 并把收到的事件存下来供断言。
 *
 * <p>线程安全：事件可能在派发线程到达，断言在测试线程发生，
 * 因此存储用 {@link CopyOnWriteArrayList}。{@link #awaitCount(int, long)}
 * 的语义是"至少收到 n 个"——比 sleep-then-assert 稳定，比逐个轮询简单。
 *
 * @param <T> 事件类型
 */
public final class EventRecorder<T> implements AutoCloseable {

    private final EventBus bus;
    private final Class<T> type;
    private final EventBus.EventListener<T> listener;
    private final List<T> events = new CopyOnWriteArrayList<>();

    private EventRecorder(EventBus bus, Class<T> type) {
        this.bus = bus;
        this.type = type;
        this.listener = events::add;
        bus.addListener(type, listener);
    }

    /** 订阅并开始记录。 */
    public static <T> EventRecorder<T> on(EventBus bus, Class<T> type) {
        return new EventRecorder<>(bus, type);
    }

    /** 已收到的事件（只读快照，按到达顺序）。 */
    public List<T> events() {
        return List.copyOf(events);
    }

    /** 已收到的数量。 */
    public int count() {
        return events.size();
    }

    /** 最近收到的事件；没有则为 null。 */
    public T last() {
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    /** 至少收到 n 个事件前轮询等待，最多等 timeoutMs；返回是否达成。 */
    public boolean awaitCount(int n, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (events.size() < n) {
            long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainMs <= 0) return events.size() >= n;
            Thread.sleep(Math.min(5, remainMs));
        }
        return true;
    }

    /** 清空记录（不影响订阅）。 */
    public void clear() {
        events.clear();
    }

    /** 退订。幂等由 EventBus.removeListener 保证。 */
    @Override
    public void close() {
        bus.removeListener(type, listener);
    }
}
