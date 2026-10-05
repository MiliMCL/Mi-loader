package org.loader.api.event;

/**
 * A listener for events of a specific type on the {@link EventBus}.
 *
 * @param <T> the event type this listener handles
 */
@FunctionalInterface
public interface EventListener<T> {

    /**
     * Called when an event of type {@code T} is posted.
     * <p>
     * Listeners must not throw exceptions. Exceptions thrown from a listener
     * are caught and do not interrupt event dispatch to other listeners.
     *
     * @param event the event that was posted
     */
    void onEvent(T event);
}
