package org.loader.runtime.scheduler;

import org.loader.runtime.kernel.Scope;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Handle to a scheduled task.
 * <p>
 * Provides methods to monitor and cancel task execution.
 */
public sealed interface TaskHandle permits TaskHandleImpl {

    /**
     * Returns the unique task ID.
     */
    String taskId();

    /**
     * Returns the owner scope.
     */
    Scope ownerScope();

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
     * @return true if cancellation was successful
     */
    boolean cancel();

    /**
     * Returns a future that completes when the task finishes.
     */
    CompletableFuture<Void> future();

    /**
     * Waits for the task to complete, throwing CancellationException if cancelled.
     */
    void await() throws CancellationException, InterruptedException;
}

record TaskHandleImpl(
        String taskId,
        Scope ownerScope,
        TaskState state,
        TaskPriority priority,
        CompletableFuture<Void> future,
        java.util.concurrent.atomic.AtomicReference<TaskState> stateRef,
        java.lang.Runnable cancellable
) implements TaskHandle {

    /**
     * 返回<b>实时</b>状态。
     *
     * <p>record 的自动访问器只会返回构造时传入的快照（永远是 QUEUED），
     * 于是 {@code handle.state()} 在任务跑完、被取消之后仍然报 QUEUED ——
     * 与「TaskState 提供等待/运行/完成/取消的状态查询」这条公开契约矛盾。
     * 状态的真实来源是 {@link #stateRef}（执行线程会更新它）。
     */
    @Override
    public TaskState state() {
        return stateRef.get();
    }

    @Override
    public boolean cancel() {
        TaskState current = stateRef.get();
        if (current == TaskState.CREATED || current == TaskState.QUEUED) {
            if (stateRef.compareAndSet(current, TaskState.CANCELLED)) {
                cancellable.run();
                future.cancel(true);
                return true;
            }
        } else if (current == TaskState.RUNNING) {
            // Attempt cooperative cancellation
            cancellable.run();
            return future.cancel(true);
        }
        return false;
    }

    @Override
    public void await() throws CancellationException, InterruptedException {
        try {
            future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (java.util.concurrent.CancellationException e) {
            throw new CancellationException("Task cancelled: " + taskId);
        }
    }
}
