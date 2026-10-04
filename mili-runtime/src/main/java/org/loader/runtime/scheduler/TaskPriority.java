package org.loader.runtime.scheduler;

/**
 * Task priority levels.
 * Higher value means higher priority.
 */
public enum TaskPriority {

    LOW(0),
    NORMAL(5),
    HIGH(10),
    CRITICAL(20);

    private final int value;

    TaskPriority(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
