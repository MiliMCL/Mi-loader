package org.loader.runtime.scheduler;

import org.loader.runtime.kernel.Scope;
import org.loader.runtime.kernel.ScopeShutdownException;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The central execution authority of the Runtime.
 * <p>
 * Mods must not create unmanaged background execution for ordinary Runtime work.
 * All async tasks must pass through the Scheduler.
 */
public class Scheduler implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ThreadPoolExecutor executor;
    private final PriorityBlockingQueue<ScheduledTask> taskQueue;
    private final Map<String, ScheduledTask> taskMap = new ConcurrentHashMap<>();
    private final AtomicLong taskCounter = new AtomicLong(0);

    // Metrics
    private final AtomicLong submittedTasks = new AtomicLong(0);
    private final AtomicLong completedTasks = new AtomicLong(0);
    private final AtomicLong failedTasks = new AtomicLong(0);
    private final AtomicLong cancelledTasks = new AtomicLong(0);
    private final AtomicLong totalExecutionTime = new AtomicLong(0);

    // Sample collectors for percentile metrics (bounded to prevent memory leaks)
    private final List<Long> waitTimeSamples = new CopyOnWriteArrayList<>();
    private final List<Long> executionTimeSamples = new CopyOnWriteArrayList<>();
    private static final int MAX_SAMPLES = 10_000;

    public Scheduler(String id, Scope owner, int corePoolSize) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        this.taskQueue = new PriorityBlockingQueue<>(
                256, Comparator.comparingInt((ScheduledTask t) -> t.priority.value()).reversed()
        );
        this.executor = new ThreadPoolExecutor(
                corePoolSize, corePoolSize * 2,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1024),
                r -> {
                    Thread t = new Thread(r, "runtime-scheduler-" + taskCounter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        startDispatcher();
    }

    public Scheduler(String id, Scope owner) {
        this(id, owner, Runtime.getRuntime().availableProcessors());
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Scope owner() {
        return owner;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Submits a task for execution.
     */
    public TaskHandle submit(Scope scope, Runnable work, TaskPriority priority) {
        if (closed.get()) {
            throw new IllegalStateException("Scheduler is closed");
        }
        if (scope.isStopped()) {
            throw new IllegalStateException("Cannot submit to stopped scope: " + scope.id());
        }

        String taskId = id + "-task-" + taskCounter.incrementAndGet();
        CompletableFuture<Void> future = new CompletableFuture<>();
        AtomicReference<TaskState> state = new AtomicReference<>(TaskState.CREATED);
        long nowNanos = System.nanoTime();
        ScheduledTask scheduledTask = new ScheduledTask(taskId, scope, work, priority, future, state, nowNanos, 0L);

        taskMap.put(taskId, scheduledTask);
        submittedTasks.incrementAndGet();
        state.set(TaskState.QUEUED);
        taskQueue.offer(scheduledTask);

        return new TaskHandleImpl(taskId, scope, TaskState.CREATED, priority, future, state, () -> {});
    }

    /**
     * Submits a task with default priority.
     */
    public TaskHandle submit(Scope scope, Runnable work) {
        return submit(scope, work, TaskPriority.NORMAL);
    }

    /**
     * Submits a task that repeats at a fixed interval.
     * The task re-schedules itself after each run until cancelled.
     *
     * @param scope    the scope that owns the task
     * @param work     the work to execute each tick
     * @param delayMs  initial delay in ms before first execution
     * @param periodMs period between executions in ms
     * @param priority execution priority
     * @return handle that cancels all future executions
     */
    public TaskHandle submitRepeating(Scope scope, Runnable work, long delayMs, long periodMs, TaskPriority priority) {
        if (closed.get()) {
            throw new IllegalStateException("Scheduler is closed");
        }
        AtomicBoolean cancelled = new AtomicBoolean(false);

        Runnable repeatingWork = new Runnable() {
            @Override
            public void run() {
                if (cancelled.get() || scope.isStopped() || closed.get()) return;
                try {
                    work.run();
                } catch (Exception e) {
                    // Don't let exceptions kill the repeating task
                }
                // Reschedule via sleep + re-submit
                if (!cancelled.get() && !scope.isStopped() && !closed.get()) {
                    try {
                        Thread.sleep(periodMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!cancelled.get() && !scope.isStopped()) {
                        submit(scope, this, priority);
                    }
                }
            }
        };

        // Initial delay
        if (delayMs > 0) {
            Runnable delayedStart = () -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                repeatingWork.run();
            };
            return submit(scope, delayedStart, priority);
        }
        return submit(scope, repeatingWork, priority);
    }

    /**
     * Creates a child task within a parent scope.
     * Implements structured concurrency: when parent stops, children stop.
     */
    public TaskHandle submitChild(Scope scope, Scope parentScope, Runnable work, TaskPriority priority) {
        TaskHandle handle = submit(scope, work, priority);
        // Register child with parent scope
        parentScope.addListener((s, from, to) -> {
            if (to == org.loader.runtime.kernel.LifecycleState.STOPPING) {
                handle.cancel();
            }
        });
        return handle;
    }

    /**
     * Cancels a task by handle.
     */
    public boolean cancel(TaskHandle handle) {
        ScheduledTask task = taskMap.get(handle.taskId());
        if (task == null) {
            return false;
        }
        return handle.cancel();
    }

    /**
     * Cancels all tasks owned by a scope.
     */
    public void cancelAll(Scope scope) {
        taskMap.values().stream()
                .filter(t -> t.ownerScope().equals(scope))
                .forEach(t -> new TaskHandleImpl(
                        t.taskId(), t.ownerScope(), t.state().get(),
                        t.priority(), t.future(), t.state(), () -> {}
                ).cancel());
    }

    /**
     * Returns current scheduler metrics.
     */
    public SchedulerMetrics metrics() {
        return new SchedulerMetrics(
                submittedTasks.get(),
                completedTasks.get(),
                failedTasks.get(),
                cancelledTasks.get(),
                taskMap.size() - completedTasks.get() - failedTasks.get() - cancelledTasks.get(),
                totalExecutionTime.get(),
                percentile(waitTimeSamples, 50) / 1_000_000.0,
                percentile(waitTimeSamples, 95) / 1_000_000.0,
                percentile(waitTimeSamples, 99) / 1_000_000.0,
                percentile(executionTimeSamples, 50) / 1_000_000.0,
                percentile(executionTimeSamples, 95) / 1_000_000.0,
                percentile(executionTimeSamples, 99) / 1_000_000.0
        );
    }

    private static double percentile(List<Long> samples, int p) {
        if (samples.isEmpty()) return 0.0;
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    /**
     * Gracefully shuts down the scheduler.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Cancel all pending tasks
            taskQueue.forEach(t -> t.state().set(TaskState.CANCELLED));
            taskQueue.clear();

            // Try to cancel running tasks
            taskMap.values().stream()
                    .filter(t -> t.state().get() == TaskState.RUNNING)
                    .forEach(t -> new TaskHandleImpl(
                            t.taskId(), t.ownerScope(), t.state().get(),
                            t.priority(), t.future(), t.state(), () -> {}
                    ).cancel());

            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void startDispatcher() {
        Thread dispatcher = new Thread(this::dispatchLoop, "runtime-dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    private void dispatchLoop() {
        while (!closed.get()) {
            try {
                ScheduledTask task = taskQueue.poll(100, TimeUnit.MILLISECONDS);
                if (task == null) continue;

                TaskState current = task.state().get();
                if (current == TaskState.CANCELLED) {
                    continue;
                }
                task.state().set(TaskState.RUNNING);
                executeTask(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void executeTask(ScheduledTask task) {
        executor.submit(() -> {
            long startTime = System.nanoTime();
            try {
                // Record wait time sample (queued until execution start)
                long waitNanos = startTime - task.createdAtNanos();
                addSample(waitTimeSamples, waitNanos);

                if (task.ownerScope().isStopped()) {
                    task.state().set(TaskState.CANCELLED);
                    task.future().cancel(true);
                    cancelledTasks.incrementAndGet();
                    return;
                }
                task.work().run();
                task.state().set(TaskState.COMPLETED);
                task.future().complete(null);
                completedTasks.incrementAndGet();
            } catch (Exception e) {
                task.state().set(TaskState.FAILED);
                task.future().completeExceptionally(e);
                failedTasks.incrementAndGet();
            } finally {
                long execNanos = System.nanoTime() - startTime;
                totalExecutionTime.addAndGet(execNanos);
                addSample(executionTimeSamples, execNanos);
            }
        });
    }

    private void addSample(List<Long> samples, long value) {
        if (samples.size() < MAX_SAMPLES) {
            samples.add(value);
        }
        // Once we hit MAX_SAMPLES we keep the existing samples for stable percentiles
    }

    /**
     * Represents a task waiting to be dispatched.
     */
    record ScheduledTask(
            String taskId,
            Scope ownerScope,
            Runnable work,
            TaskPriority priority,
            CompletableFuture<Void> future,
            AtomicReference<TaskState> state,
            long createdAtNanos,
            long deadlineNanos
    ) {
    }

    /**
     * Snapshot of scheduler metrics.
     */
    public record SchedulerMetrics(
            long submitted,
            long completed,
            long failed,
            long cancelled,
            long active,
            long totalExecutionTimeNanos,
            double p50WaitMs,
            double p95WaitMs,
            double p99WaitMs,
            double p50ExecutionMs,
            double p95ExecutionMs,
            double p99ExecutionMs
    ) {
        public double averageExecutionTimeMs() {
            return completed > 0 ? (totalExecutionTimeNanos / 1_000_000.0) / completed : 0;
        }
    }
}
