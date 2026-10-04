package org.loader.runtime.scheduler;

/**
 * Task lifecycle states.
 */
public enum TaskState {

    CREATED,
    QUEUED,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED;

    /**
     * Returns whether this state indicates the task has finished.
     */
    public boolean isFinished() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }

    /**
     * Returns whether this state indicates the task is actively executing.
     */
    public boolean isActive() {
        return this == RUNNING || this == QUEUED;
    }

    /**
     * Validates transition between states.
     */
    public boolean canTransitionTo(TaskState target) {
        if (this.isFinished()) {
            return false;
        }
        return switch (this) {
            case CREATED -> target == QUEUED || target == CANCELLED;
            case QUEUED -> target == RUNNING || target == CANCELLED;
            case RUNNING -> target == COMPLETED || target == FAILED || target == CANCELLED;
            default -> false;
        };
    }
}
