package org.loader.api.lifecycle;

/**
 * Observable lifecycle view for a mod or component.
 * <p>
 * This interface is read-only — mods can observe state transitions but
 * must not modify the lifecycle directly. State transitions are managed
 * by the loader and runtime.
 */
public interface Lifecycle {

    /**
     * Returns the current lifecycle state.
     */
    LifecycleState state();

    /**
     * Returns {@code true} if the lifecycle is in a terminal state.
     */
    default boolean isTerminal() {
        return state().isTerminal();
    }

    /**
     * Returns {@code true} if new work can still be submitted.
     */
    default boolean allowsNewWork() {
        return state().allowsNewWork();
    }

    /**
     * Registers a listener that will be called on state transitions.
     * <p>
     * The listener receives the old and new states whenever a transition occurs.
     * Listeners must not throw exceptions.
     *
     * @param listener the state change listener to register
     */
    void addListener(LifecycleListener listener);

    /**
     * Removes a previously registered listener.
     *
     * @param listener the listener to remove
     */
    void removeListener(LifecycleListener listener);

    /**
     * Functional interface for lifecycle state change notifications.
     */
    @FunctionalInterface
    interface LifecycleListener {

        /**
         * Called when a lifecycle transitions between states.
         *
         * @param from the previous state
         * @param to   the new state
         */
        void onStateChange(LifecycleState from, LifecycleState to);
    }
}
