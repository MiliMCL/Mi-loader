package org.loader.api.scheduler;

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
     * Returns {@code true} if this state indicates the task has finished.
     */
    public boolean isFinished() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }

    /**
     * Returns {@code true} if this state indicates the task is actively executing or waiting.
     */
    public boolean isActive() {
        return this == RUNNING || this == QUEUED;
    }
}
