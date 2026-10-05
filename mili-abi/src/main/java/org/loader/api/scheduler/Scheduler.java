package org.loader.api.scheduler;

/**
 * Scheduler facade for submitting scoped, async tasks.
 * <p>
 * Mods must not create unmanaged background execution for ordinary Runtime work.
 * All async tasks must pass through the Scheduler.
 */
public interface Scheduler {

    /**
     * Submits a task for execution with default priority.
     *
     * @param work the work to execute
     * @return a handle to monitor or cancel the task
     */
    TaskHandle submit(Runnable work);

    /**
     * Submits a task for execution with the specified priority.
     *
     * @param work     the work to execute
     * @param priority the execution priority
     * @return a handle to monitor or cancel the task
     */
    TaskHandle submit(Runnable work, TaskPriority priority);

    /**
     * Submits a task that repeats at a fixed interval.
     * <p>
     * The task re-schedules itself after each run until cancelled.
     *
     * @param work     the work to execute each interval
     * @param delayMs  initial delay in ms before first execution
     * @param periodMs period between executions in ms
     * @return a handle that cancels all future executions
     */
    default TaskHandle submitRepeating(Runnable work, long delayMs, long periodMs) {
        return submitRepeating(work, delayMs, periodMs, TaskPriority.NORMAL);
    }

    /**
     * Submits a task that repeats at a fixed interval with the specified priority.
     *
     * @param work     the work to execute each interval
     * @param delayMs  initial delay in ms before first execution
     * @param periodMs period between executions in ms
     * @param priority the execution priority
     * @return a handle that cancels all future executions
     */
    TaskHandle submitRepeating(Runnable work, long delayMs, long periodMs, TaskPriority priority);

    /**
     * Cancels a task by handle.
     *
     * @param handle the task handle
     * @return {@code true} if cancellation was successful
     */
    boolean cancel(TaskHandle handle);

    /**
     * Shuts down the scheduler, cancelling all pending tasks.
     */
    void close();
}
