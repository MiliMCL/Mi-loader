package org.loader.runtime.scheduler;

import org.loader.runtime.kernel.Scope;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

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
     * Returns the <b>live</b> current task state.
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

/**
 * {@link TaskHandle} 的实现。
 *
 * <h2>为什么 state 必须读原子引用，而不是 record 组件</h2>
 * 旧实现把 {@code TaskState} 作为 record 组件存下来 —— record 的访问器
 * 返回的是<b>构造时定格的值</b>，于是一个跑完的任务其 handle 永远报告
 * {@code QUEUED}。表现：
 * <pre>
 *   handle.await();                  // 正常返回
 *   handle.state()                   // 仍是 QUEUED
 *   handle.isFinished()              // false —— 与事实相反
 * </pre>
 * 任何「轮询 handle 状态来决定是否继续」的 Mod 都会陷入死等，
 * 而日志里没有任何异常。现在 {@link #state()} 直接读
 * {@code AtomicReference}，与调度器的真实状态同源。
 *
 * @param stateRef 与 {@link Scheduler.ScheduledTask} 共享的实时状态引用
 */
record TaskHandleImpl(
        String taskId,
        Scope ownerScope,
        TaskPriority priority,
        CompletableFuture<Void> future,
        AtomicReference<TaskState> stateRef,
        Runnable cancellable
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
        // 只允许从「未开始」或「运行中」取消；已终结的任务返回 false，
        // 与 {@link TaskState#canTransitionTo} 的语义一致。
        TaskState current = stateRef.get();
        if (current == TaskState.CREATED || current == TaskState.QUEUED) {
            if (stateRef.compareAndSet(current, TaskState.CANCELLED)) {
                cancellable.run();
                future.cancel(true);
                return true;
            }
            return false;
        }
        if (current == TaskState.RUNNING) {
            // 协作式取消：状态先改，任务体需要自己检查中断标志
            if (stateRef.compareAndSet(TaskState.RUNNING, TaskState.CANCELLED)) {
                cancellable.run();
                future.cancel(true);
                return true;
            }
        }
        return false;
    }

    @Override
    public void await() throws CancellationException, InterruptedException {
        try {
            future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new TaskFailureException(taskId, e.getCause());
        } catch (CancellationException e) {
            throw new CancellationException("Task cancelled: " + taskId);
        }
    }
}
