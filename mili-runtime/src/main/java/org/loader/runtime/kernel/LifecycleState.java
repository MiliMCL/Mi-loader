package org.loader.runtime.kernel;

/**
 * Canonical lifecycle states for all Runtime-managed components.
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
     * Returns whether this state is a terminal state.
     */
    public boolean isTerminal() {
        return this == STOPPED || this == FAILED;
    }

    /**
     * Returns whether this state allows new work to be submitted.
     */
    public boolean allowsNewWork() {
        return this == RUNNING || this == REGISTERED || this == INITIALIZED || this == LOADED;
    }

    /**
     * Checks transition validity.
     */
    public boolean canTransitionTo(LifecycleState target) {
        if (this.isTerminal()) {
            return false;
        }
        return switch (this) {
            case DISCOVERED -> target == RESOLVED;
            case RESOLVED -> target == LOADED;
            case LOADED -> target == INITIALIZED;
            case INITIALIZED -> target == REGISTERED;
            case REGISTERED -> target == RUNNING;
            case RUNNING -> target == STOPPING;
            case STOPPING -> target == STOPPED || target == FAILED;
            default -> false;
        };
    }
}
