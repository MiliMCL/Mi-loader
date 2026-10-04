package org.loader.api.lifecycle;

/**
 * Canonical lifecycle states for all Runtime-managed components.
 * <p>
 * This is a simplified re-export of the states used in the runtime layer.
 * See {@link #allowsNewWork()} and {@link #isTerminal()} for mod-relevant predicates.
 */
public enum LifecycleState {

    DISCOVERED,
    RESOLVED,
    LOADED,
    INITIALIZED,
    REGISTERED,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED;

    /**
     * Returns {@code true} if this state is a terminal state (no further transitions expected).
     */
    public boolean isTerminal() {
        return this == STOPPED || this == FAILED;
    }

    /**
     * Returns {@code true} if this state allows new work to be submitted.
     */
    public boolean allowsNewWork() {
        return this == RUNNING || this == REGISTERED || this == INITIALIZED || this == LOADED;
    }
}
