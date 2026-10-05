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
     * Closes the event bus, removing all listeners.
     * <p>
     * This is typically called automatically when the owning mod's scope
     * shuts down. Mods generally do not need to call this directly.
     */
    void close();
}
