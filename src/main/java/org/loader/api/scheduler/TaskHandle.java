package org.loader.api.scheduler;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Handle to a scheduled task.
 * <p>
 * Provides methods to monitor and cancel task execution.
 */
public interface TaskHandle {

    /**
     * Returns the unique task identifier.
     */
    String taskId();

    /**
     * Returns the current task state.
     */
    TaskState state();

    /**
     * Returns the task priority.
     */
    TaskPriority priority();

    /**
     * Attempts to cancel the task.
     *
     * @return {@code true} if cancellation was successful
     */
    boolean cancel();

    /**
     * Returns a future that completes when the task finishes.
     */
    CompletableFuture<Void> future();

    /**
     * Waits for the task to complete.
     *
     * @throws CancellationException if the task was cancelled
     * @throws InterruptedException   if the current thread is interrupted
     */
    void await() throws CancellationException, InterruptedException;

    /**
     * Returns {@code true} if this task is still active (not finished).
     */
    default boolean isActive() {
        return state().isActive();
    }

    /**
     * Returns {@code true} if this task has finished (completed, failed, or cancelled).
     */
    default boolean isFinished() {
        return state().isFinished();
    }
}
