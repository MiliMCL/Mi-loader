package org.loader.runtime.kernel;

/**
 * Listener for Scope lifecycle state changes.
 */
@FunctionalInterface
public interface ScopeListener {

    /**
     * Called when a Scope transitions between states.
     *
     * @param scope the scope that changed
     * @param from  the previous state
     * @param to    the new state
     */
    void onStateChange(Scope scope, LifecycleState from, LifecycleState to);
}
