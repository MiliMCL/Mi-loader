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
    /** 优先级工作队列：线程池直接从这里取任务，优先级得以保留。 */
    private final PriorityWorkQueue taskQueue;
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

    /** 重复任务的计时器，关闭时一并清理，避免线程泄漏。 */
    private final Set<java.util.concurrent.ScheduledExecutorService> repeatingTimers =
            ConcurrentHashMap.newKeySet();

    public Scheduler(String id, Scope owner, int corePoolSize) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        this.taskQueue = new PriorityWorkQueue(
                // 优先级值越小越优先，因此用升序比较器
                Comparator.comparingInt((ScheduledTask t) -> t.priority().value())
                        // 同优先级按提交顺序（先到先执行），保证公平与可预测
                        .thenComparingLong(ScheduledTask::createdAtNanos)
        );
        this.executor = new ThreadPoolExecutor(
                corePoolSize, corePoolSize * 2,
                60L, TimeUnit.SECONDS,
                this.taskQueue,
                r -> {
                    Thread t = new Thread(r, "runtime-scheduler-" + taskCounter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                // 队列无界，CallerRuns 不会因拒绝而丢弃任务；改为在调用线程执行以施加背压
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
        ScheduledTask scheduledTask = new ScheduledTask(this, taskId, scope, work, priority,
                future, state, nowNanos, 0L);

        taskMap.put(taskId, scheduledTask);
        submittedTasks.incrementAndGet();
        state.set(TaskState.QUEUED);

        // 必须交给 executor，而不是只放进 taskQueue。
        //
        // 历史上这里只有 taskQueue.offer(...)：重构把「优先级队列」直接当成
        // ThreadPoolExecutor 的 workQueue（原 LinkedBlockingQueue 被替换掉），
        // 却忘了 execute() 这一步。后果是任务躺在队列里，永远没有工作线程
        // —— ThreadPoolExecutor 只在自己被调用 execute() 时才会创建线程。
        // 表现为 submit() 正常返回、handle.await() 永久阻塞，
        // 而且没有任何异常或日志，非常难查。
        //
        // 现在 taskQueue 就是 executor 的 workQueue，直接 execute 即可：
        // 未达 corePoolSize 时第一个任务由新线程直接执行，之后从优先级队列取。
        try {
            executor.execute(scheduledTask);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            taskMap.remove(taskId);
            state.set(TaskState.CANCELLED);
            future.cancel(false);
            throw new org.loader.runtime.error.SchedulerError(
                    "线程池拒绝任务: " + taskId
                            + "（executor=" + (closed.get() ? "已关闭" : "饱和") + "）",
                    org.loader.runtime.error.ErrorContext.builder("scheduler")
                            .owner(id)
                            .operation("submit")
                            .detail("taskId", taskId)
                            .build(),
                    rejected);
        }

        return new TaskHandleImpl(taskId, scope, TaskState.QUEUED, priority, future, state,
                () -> removeFromQueue(scheduledTask));
    }

    /** 从优先级队列中移除尚未开始的任务。 */
    private void removeFromQueue(ScheduledTask task) {
        taskQueue.remove(task);
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
        final long period = Math.max(1L, periodMs);
        final long delay = Math.max(0L, delayMs);
        // 重复任务使用独立守护计时器线程做延时触发，工作线程只执行 work，
        // 绝不 sleep 占位 —— 否则 corePoolSize 个线程会被周期任务全部占满，
        // 导致其他任务饥饿（这是历史实现的一个真实缺陷）。
        java.util.concurrent.ScheduledExecutorService timer =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "runtime-scheduler-timer");
                    t.setDaemon(true);
                    return t;
                });

        java.util.concurrent.atomic.AtomicBoolean cancelled = new AtomicBoolean(false);
        java.util.concurrent.ScheduledFuture<?>[] handleRef = new java.util.concurrent.ScheduledFuture<?>[1];

        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (cancelled.get() || scope.isStopped() || closed.get()) {
                    return; // 到此终止，计时器线程随之退出
                }
                try {
                    work.run();
                } catch (Throwable ignored) {
                    // 周期任务的异常不得终止后续周期
                }
                if (!cancelled.get() && !scope.isStopped() && !closed.get()) {
                    try {
                        handleRef[0] = timer.schedule(this, period, TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.RejectedExecutionException ignored) {
                        // 关闭竞态：忽略
                    }
                }
            }
        };

        try {
            long initial = delay > 0 ? delay : period;
            handleRef[0] = timer.schedule(tick, initial, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            timer.shutdownNow();
            throw new org.loader.runtime.error.SchedulerError(
                    "周期任务调度失败", 
                    org.loader.runtime.error.ErrorContext.builder("scheduler")
                            .owner(id).operation("submitRepeating").build(), e);
        }

        // 关闭时一并清理计时器
        synchronized (repeatingTimers) {
            repeatingTimers.add(timer);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        return new TaskHandleImpl(
                id + "-repeating-" + taskCounter.incrementAndGet(),
                scope, TaskState.QUEUED, priority, future,
                new AtomicReference<>(TaskState.RUNNING), () -> {
                    cancelled.set(true);
                    java.util.concurrent.ScheduledFuture<?> h = handleRef[0];
                    if (h != null) {
                        h.cancel(false);
                    }
                    timer.shutdownNow();
                    synchronized (repeatingTimers) {
                        repeatingTimers.remove(timer);
                    }
                    future.complete(null);
                });
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
            // 标记并清空所有待执行任务
            for (ScheduledTask t : taskQueue.drainAll()) {
                t.state().set(TaskState.CANCELLED);
                t.future().cancel(true);
            }

            // 清理重复任务的计时器线程
            for (var timer : repeatingTimers) {
                timer.shutdownNow();
            }
            repeatingTimers.clear();

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

    /**
     * 优先级感知的工作队列。
     *
     * <p><b>为什么不用 LinkedBlockingQueue</b>：dispatcher 从优先级队列取出
     * 任务后若交给 FIFO 线程池，优先级就在执行层被彻底抹平 —— 排序白做。
     * 这里让线程池本身按优先级出队，并保证同优先级 FIFO（稳定性）。
     */
    private static final class PriorityWorkQueue extends
            java.util.AbstractQueue<Runnable>
            implements java.util.concurrent.BlockingQueue<Runnable> {

        private final java.util.concurrent.PriorityBlockingQueue<ScheduledTask> delegate;
        /** 正在执行的任务，用于移除时按 ScheduledTask 定位。 */
        private final java.util.Set<ScheduledTask> pending = ConcurrentHashMap.newKeySet();

        PriorityWorkQueue(Comparator<ScheduledTask> comparator) {
            this.delegate = new java.util.concurrent.PriorityBlockingQueue<>(256, comparator);
        }

        private static ScheduledTask unwrap(Runnable r) {
            return r instanceof ScheduledTask st ? st : null;
        }

        @Override
        public Iterator<Runnable> iterator() {
            List<Runnable> view = new ArrayList<>(delegate);
            return view.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        /**
         * 非平台任务（理论上只有 ThreadPoolExecutor 内部的 FutureTask，
         * 但不应依赖这个假设）用「队尾优先级」包装后接收，而不是拒绝。
         *
         * <p><b>为什么不能返回 false</b>：{@code offer} 返回 false 会让
         * {@code ThreadPoolExecutor} 认为队列已满，触发 {@code CallerRunsPolicy}；
         * 而 {@code put} 返回 false 则是直接抛异常。两种结果都是任务被静默
         * 丢弃或行为异常 —— 队列应该「永远接得住」，排序是次要问题。
         */
        @Override
        public boolean offer(Runnable r) {
            if (r == null) {
                throw new NullPointerException("runnable must not be null");
            }
            ScheduledTask st = unwrap(r);
            if (st == null) {
                st = ScheduledTask.foreign(r);
            }
            pending.add(st);
            return delegate.offer(st);
        }

        // BlockingQueue 的两个额外插入方法。AbstractQueue 只要求 offer(E)，
        // 但 ThreadPoolExecutor 在 prestart/corePool 扩容路径上会调用
        // offer(e, timeout, unit)；不实现就编译不过。
        @Override
        public boolean offer(Runnable r, long timeout, TimeUnit unit) {
            return offer(r);
        }

        @Override
        public void put(Runnable r) throws InterruptedException {
            offer(r);
        }

        @Override
        public Runnable poll() {
            ScheduledTask st = delegate.poll();
            if (st != null) {
                pending.remove(st);
            }
            return st;
        }

        @Override
        public Runnable peek() {
            return delegate.peek();
        }

        @Override
        public Runnable take() throws InterruptedException {
            ScheduledTask st = delegate.take();
            pending.remove(st);
            return st;
        }

        @Override
        public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
            ScheduledTask st = delegate.poll(timeout, unit);
            pending.remove(st);
            return st;
        }

        @Override
        public int drainTo(Collection<? super Runnable> c) {
            int n = delegate.drainTo(c);
            pending.clear();
            return n;
        }

        @Override
        public int drainTo(Collection<? super Runnable> c, int maxElements) {
            int n = delegate.drainTo(c, maxElements);
            pending.clear();
            return n;
        }

        @Override
        public boolean remove(Object o) {
            ScheduledTask st = unwrap(o instanceof Runnable r ? r : null);
            if (st == null) {
                return false;
            }
            pending.remove(st);
            return delegate.remove(st);
        }

        @Override
        public void clear() {
            delegate.clear();
            pending.clear();
        }

        /** 清空并返回全部待执行任务（关闭时用于标记取消）。 */
        List<ScheduledTask> drainAll() {
            List<ScheduledTask> all = new ArrayList<>(delegate);
            delegate.clear();
            pending.clear();
            return all;
        }

        @Override
        public boolean contains(Object o) {
            ScheduledTask st = unwrap(o instanceof Runnable r ? r : null);
            return st != null && delegate.contains(st);
        }

        @Override
        public int remainingCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public Object[] toArray() {
            return delegate.toArray();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return delegate.toArray(a);
        }
    }

    private void startDispatcher() {
        // 优先级队列本身就是工作队列：线程池直接从它取任务执行。
        // 历史上这里有一个额外的 dispatcher 线程把任务从优先级队列
        // 搬到 FIFO 队列，优先级因此在执行层被抹平 —— 已移除。
    }

    private void addSample(List<Long> samples, long value) {
        if (samples.size() < MAX_SAMPLES) {
            samples.add(value);
        }
        // Once we hit MAX_SAMPLES we keep the existing samples for stable percentiles
    }

    /**
     * 一个待执行的任务。
     *
     * <p>实现 {@link Runnable} 是关键设计：线程池直接从优先级队列取出本对象
     * 并在池线程上执行 {@link #run()}，中间没有二次排队，因此优先级得以保留。
     */
    static final class ScheduledTask implements Runnable {

        final String taskId;
        final Scope ownerScope;
        final Runnable work;
        final TaskPriority priority;
        final CompletableFuture<Void> future;
        final AtomicReference<TaskState> state;
        final long createdAtNanos;
        final long deadlineNanos;

        private final Scheduler scheduler;

        ScheduledTask(Scheduler scheduler, String taskId, Scope ownerScope, Runnable work,
                      TaskPriority priority, CompletableFuture<Void> future,
                      AtomicReference<TaskState> state, long createdAtNanos,
                      long deadlineNanos) {
            this.scheduler = scheduler;
            this.taskId = taskId;
            this.ownerScope = ownerScope;
            this.work = work;
            this.priority = priority;
            this.future = future;
            this.state = state;
            this.createdAtNanos = createdAtNanos;
            this.deadlineNanos = deadlineNanos;
        }

        String taskId() {
            return taskId;
        }

        Scope ownerScope() {
            return ownerScope;
        }

        Runnable work() {
            return work;
        }

        TaskPriority priority() {
            return priority;
        }

        CompletableFuture<Void> future() {
            return future;
        }

        AtomicReference<TaskState> state() {
            return state;
        }

        long createdAtNanos() {
            return createdAtNanos;
        }

        long deadlineNanos() {
            return deadlineNanos;
        }

        /**
         * 包装一个不属于平台的 {@link Runnable}。
         *
         * <p>给「ThreadPoolExecutor 内部任务」一条通路：它们没有 priority /
         * future / scope，套一个最低优先级（{@link TaskPriority#LOW}）的壳，
         * 让它们排在平台任务之后执行，而不是被队列拒绝。
         */
        static ScheduledTask foreign(Runnable r) {
            return new ScheduledTask(null, "foreign-" + r.getClass().getName(),
                    null, r, TaskPriority.LOW, null, null,
                    System.nanoTime(), 0L);
        }

        @Override
        public void run() {
            // 外来任务（见 foreign()）没有 scheduler 归属，直接执行即可。
            if (scheduler == null) {
                try {
                    work.run();
                } catch (Throwable ignored) {
                    // 无法归属的异常无处记录；静默保持线程池健康
                }
                return;
            }

            long startTime = System.nanoTime();
            try {
                scheduler.addSample(scheduler.waitTimeSamples, startTime - createdAtNanos);

                if (state.get() == TaskState.CANCELLED) {
                    scheduler.cancelledTasks.incrementAndGet();
                    future.cancel(true);
                    return;
                }
                if (ownerScope.isStopped() || scheduler.closed.get()) {
                    state.set(TaskState.CANCELLED);
                    scheduler.cancelledTasks.incrementAndGet();
                    future.cancel(true);
                    return;
                }

                state.set(TaskState.RUNNING);
                work.run();
                state.set(TaskState.COMPLETED);
                scheduler.completedTasks.incrementAndGet();
                future.complete(null);
            } catch (Throwable e) {
                state.set(TaskState.FAILED);
                scheduler.failedTasks.incrementAndGet();
                future.completeExceptionally(e);
            } finally {
                long execNanos = System.nanoTime() - startTime;
                scheduler.totalExecutionTime.addAndGet(execNanos);
                scheduler.addSample(scheduler.executionTimeSamples, execNanos);
            }
        }
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
