package org.loader.api.event;

/**
 * Handle representing an active event subscription.
 * <p>
 * Returned from {@link EventBus#subscribe(Class, EventListener)} and can be
 * used to unsubscribe. A subscription that has been closed will no longer
 * receive events.
 */
public interface Subscription {

    /**
     * Removes the subscribed listener from the event bus.
     * <p>
     * After calling this method, the listener will stop receiving events
     * of the subscribed type. This method is idempotent — calling it
     * multiple times has no additional effect.
     */
    void unsubscribe();

    /**
     * Returns {@code true} if this subscription is still active
     * (i.e. not yet unsubscribed).
     */
    boolean isActive();
}
