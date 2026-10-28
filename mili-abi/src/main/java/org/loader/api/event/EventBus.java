package org.loader.api.event;

import java.util.concurrent.CompletableFuture;

/**
 * Typed event bus for inter-mod and mod-to-runtime communication.
 * <p>
 * Facade over the runtime {@code EventBus}. Events are dispatched asynchronously
 * through the Runtime Scheduler.
 */
public interface EventBus {

    /**
     * Registers a listener for a specific event type.
     * <p>
     * Use {@link #subscribe(Class, EventListener)} for a {@link Subscription}-based
     * API that allows easy unsubscription.
     *
     * @param eventType the event type class
     * @param listener  the listener to register
     * @param <T>       the event type
     */
    <T> void addListener(Class<T> eventType, EventListener<T> listener);

    /**
     * Removes a previously registered listener.
     *
     * @param eventType the event type class
     * @param listener  the listener to remove
     * @param <T>       the event type
     */
    <T> void removeListener(Class<T> eventType, EventListener<T> listener);

    /**
     * Subscribes a listener for a specific event type and returns a
     * {@link Subscription} handle that can be used to unsubscribe.
     *
     * @param eventType the event type class
     * @param listener  the listener to register
     * @param <T>       the event type
     * @return a subscription handle
     */
    <T> Subscription subscribe(Class<T> eventType, EventListener<T> listener);

    /**
     * Posts an event synchronously, notifying all registered listeners.
     *
     * @param event the event to post
     * @param <T>   the event type
     */
    <T> void post(T event);

    /**
     * Posts an event asynchronously.
     * <p>
     * Listeners will be notified on the dispatch thread. The returned
     * future completes when all listeners have been invoked.
     *
     * @param event the event to post
     * @param <T>   the event type
     * @return a future that completes when dispatch is finished
     */
    <T> CompletableFuture<Void> postAsync(T event);

    /**
     * 是否至少有一个监听器会收到该类型的事件。
     *
     * <p>匹配包含超类型监听器：订阅 {@code Object.class} 的监听器
     * 会让 {@code hasListeners(String.class)} 返回 true。
     *
     * <p>存在的理由：热路径上的发布方（每 tick 的事件、渲染钩子）
     * 需要在不影响语义的前提下跳过「没人订阅」的整段工作 ——
     * 包括事件对象的构造。注意本方法只是<b>瞬时</b>判断：
     * 返回 true 之后监听器可能立刻被注销，返回 false 之后可能立刻被注册，
     * 因此它只能用于优化，不能用于正确性判断。
     */
    default boolean hasListeners(Class<?> eventType) {
        return true;
    }

    /**
     * 当前已注册的监听器总数（诊断用）。
     */
    default int listenerCount() {
        return -1;
    }

    /**
     * Closes the event bus, removing all listeners.
     * <p>
     * This is typically called automatically when the owning mod's scope
     * shuts down. Mods generally do not need to call this directly.
     */
    void close();
}
