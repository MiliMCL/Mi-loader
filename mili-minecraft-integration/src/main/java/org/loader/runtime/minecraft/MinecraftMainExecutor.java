package org.loader.runtime.minecraft;

import org.loader.runtime.kernel.Scope;
import org.loader.runtime.scheduler.TaskHandle;
import org.loader.runtime.scheduler.TaskPriority;
import org.loader.runtime.scheduler.TaskState;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Minecraft Main Thread Bridge per spec section 13.
 * <p>
 * Enables two-way hand-off:
 * <pre>
 * Runtime Worker → Minecraft Main Thread (for thread-unsafe operations)
 * Minecraft Main Thread → Runtime Scheduler (for async work)
 * </pre>
 * <p>
 * The bridge maintains a submission queue. The Minecraft main thread must poll
 * the queue (via {@link #processQueue()}) to execute tasks that require main-thread
 * access. Worker threads use {@link #submitToMain(Runnable)} to enqueue.
 * <p>
 * Deadlock protection: tasks submitted to main thread NEVER block-wait on
 * another main-thread task. They use {@link #submitToMainAsync(Runnable)} which
 * returns a CompletableFuture.
 */
public class MinecraftMainExecutor {

    private final BlockingQueue<MainTask> mainQueue = new LinkedBlockingQueue<>();
    private final AtomicLong taskCounter = new AtomicLong(0);
    private volatile boolean closed = false;

    /**
     * A task destined for the Minecraft main thread.
     */
    private record MainTask(long id, Runnable work, CompletableFuture<Void> future) {
    }

    /**
     * Submits work to the Minecraft main thread.
     * Returns a future that completes when the work is done.
     *
     * @param work thread-unsafe work that MUST execute on the main thread
     * @return future that completes on execution or fails with rejection
     */
    public CompletableFuture<Void> submitToMainAsync(Runnable work) {
        if (closed) {
            CompletableFuture<Void> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new RejectedExecutionException("MinecraftMainExecutor is closed"));
            return rejected;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        mainQueue.offer(new MainTask(taskCounter.incrementAndGet(), work, future));
        return future;
    }

    /**
     * Submits work to the main thread and blocks until it completes.
     * <p>
     * Deadlock warning: must NOT be called from the main thread itself.
     *
     * @param work thread-unsafe work for the main thread
     * @throws InterruptedException  if interrupted while waiting
     * @throws ExecutionException    if the work throws
     */
    public void submitToMain(Runnable work) throws InterruptedException, ExecutionException {
        submitToMainAsync(work).get();
    }

    /**
     * Submits a computation to the main thread and returns its result.
     */
    public <T> CompletableFuture<T> supplyToMainAsync(Supplier<T> supplier) {
        CompletableFuture<T> result = new CompletableFuture<>();
        submitToMainAsync(() -> {
            try {
                result.complete(supplier.get());
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /**
     * Submits async work from the main thread to the Runtime scheduler.
     */
    public TaskHandle submitToScheduler(Scope scope, org.loader.runtime.scheduler.Scheduler scheduler,
                                        Runnable work, TaskPriority priority) {
        if (closed) {
            throw new RejectedExecutionException("MinecraftMainExecutor is closed");
        }
        return scheduler.submit(scope, work, priority);
    }

    /**
     * Drains and executes all pending main-thread tasks.
     * MUST be called from the Minecraft main thread.
     *
     * @return number of tasks executed
     */
    public int processQueue() {
        int count = 0;
        MainTask task;
        while ((task = mainQueue.poll()) != null) {
            try {
                task.work().run();
                task.future().complete(null);
            } catch (Exception e) {
                task.future().completeExceptionally(e);
            }
            count++;
        }
        return count;
    }

    /**
     * Returns the number of pending main-thread tasks.
     */
    public int pendingCount() {
        return mainQueue.size();
    }

    /**
     * Closes the executor, rejecting all future submissions
     * and failing all pending tasks.
     */
    public void close() {
        closed = true;
        MainTask task;
        while ((task = mainQueue.poll()) != null) {
            task.future().completeExceptionally(
                    new RejectedExecutionException("Executor closed before task executed"));
        }
    }

    public boolean isClosed() {
        return closed;
    }
}
