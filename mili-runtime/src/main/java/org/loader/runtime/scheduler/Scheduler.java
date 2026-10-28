package org.loader.runtime.scheduler;

import org.loader.runtime.kernel.Scope;

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
 *
 * <h2>本类修掉的历史缺陷</h2>
 * <ul>
 *   <li><b>任务表只增不减</b>：旧实现把每个任务放进 {@code taskMap} 后
 *       永不移除，跑一局游戏就是几十万条记录的常驻内存，而且
 *       {@code metrics().active} 会算成负数。现在任务终结时（完成/失败/取消）
 *       一律从表里摘除。</li>
 *   <li><b>周期任务一个任务一条线程</b>：旧实现给每个
 *       {@code submitRepeating} 新建一个 {@code ScheduledExecutorService}。
 *       10 个 Mod 各挂 3 个周期任务就是 30 条常驻线程。现在全调度器
 *       共用一个计时器线程。</li>
 *   <li><b>周期任务的计时器不回收</b>：Scope 停止后周期任务自然终止，
 *       但计时器线程没人关 —— 线程泄漏。现在任务终止时自动摘除计时器。</li>
 *   <li><b>{@code submitChild} 的监听器泄漏</b>：旧实现每提交一个子任务
 *       就往父 Scope 上永久挂一个监听器。现在子任务终结时摘掉。</li>
 *   <li><b>取消被完成覆盖</b>：旧实现先 {@code set(RUNNING)} 再跑，
 *       取消在跑的过程中到达会被随后的 {@code set(COMPLETED)} 抹掉。
 *       现在终结状态一律走 CAS，先到先得。</li>
 * </ul>
 */
public class Scheduler implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ThreadPoolExecutor executor;
    /** 优先级工作队列：线程池直接从这里取任务，优先级得以保留。 */
    private final PriorityWorkQueue taskQueue;

    /**
     * 未终结任务表。
     *
     * <p>只装 CREATED / QUEUED / RUNNING 的任务；终结时立即摘除。
     * 因此 {@code taskMap.size()} 就是「当前活跃任务数」，
     * 不需要拿提交数去减完成数（那个减法在有任务被拒、被跳过时必然算错）。
     */
    private final Map<String, ScheduledTask> taskMap = new ConcurrentHashMap<>();
    private final AtomicLong taskCounter = new AtomicLong(0);

    // Metrics
    private final AtomicLong submittedTasks = new AtomicLong(0);
    private final AtomicLong completedTasks = new AtomicLong(0);
    private final AtomicLong failedTasks = new AtomicLong(0);
    private final AtomicLong cancelledTasks = new AtomicLong(0);
    private final AtomicLong totalExecutionTime = new AtomicLong(0);

    // 采样器（定长环形缓冲）
    private final LatencySamples waitTimeSamples = new LatencySamples(1024);
    private final LatencySamples executionTimeSamples = new LatencySamples(1024);

    /**
     * 全调度器共享的延时计时器。
     *
     * <p>惰性创建：没有任何周期任务时不占线程。关闭时统一 shutdownNow。
     */
    private volatile ScheduledExecutorService timer;

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

        return new TaskHandleImpl(taskId, scope, priority, future, state,
                () -> removeFromQueue(scheduledTask));
    }

    /**
     * 取消一个尚未开始的任务：从优先级队列与活跃任务表里一并摘除。
     *
     * <p>只从队列移除是不够的 —— {@code taskMap} 是「活跃任务」的唯一来源，
     * 漏掉它会让一个永远不会执行的任务永久占据 active 计数。
     */
    private void removeFromQueue(ScheduledTask task) {
        taskQueue.remove(task);
        taskMap.remove(task.taskId());
    }

    /**
     * Submits a task with default priority.
     */
    public TaskHandle submit(Scope scope, Runnable work) {
        return submit(scope, work, TaskPriority.NORMAL);
    }

    /**
     * Submits a task that repeats at a fixed interval.
     *
     * <p>周期任务的全部执行都在共享计时器上完成，不占用线程池工作线程 ——
     * 旧实现让工作线程 {@code sleep} 等下一个周期，corePoolSize 个周期任务
     * 就能把整个池占满，其余任务全部饥饿。
     *
     * @param scope    the scope that owns the task
     * @param work     the work to execute each interval
     * @param delayMs  initial delay in ms before first execution
     * @param periodMs period between executions in ms
     * @param priority execution priority
     * @return handle that cancels all future executions
     */
    public TaskHandle submitRepeating(Scope scope, Runnable work, long delayMs, long periodMs,
                                      TaskPriority priority) {
        if (closed.get()) {
            throw new IllegalStateException("Scheduler is closed");
        }
        if (scope.isStopped()) {
            throw new IllegalStateException("Cannot submit to stopped scope: " + scope.id());
        }
        final long period = Math.max(1L, periodMs);
        final long delay = Math.max(0L, delayMs);

        String taskId = id + "-repeating-" + taskCounter.incrementAndGet();
        CompletableFuture<Void> future = new CompletableFuture<>();
        AtomicReference<TaskState> state = new AtomicReference<>(TaskState.QUEUED);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> handleRef = new AtomicReference<>();
        AtomicBoolean scheduledOnce = new AtomicBoolean(false);

        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (cancelled.get() || scope.isStopped() || closed.get()) {
                    finish();
                    return;
                }
                long start = System.nanoTime();
                state.set(TaskState.RUNNING);
                try {
                    work.run();
                } catch (Throwable t) {
                    // 周期任务的异常不得终止后续周期，但必须计入失败数
                    failedTasks.incrementAndGet();
                } finally {
                    long exec = System.nanoTime() - start;
                    totalExecutionTime.addAndGet(exec);
                    executionTimeSamples.add(exec);
                }
                if (cancelled.get() || scope.isStopped() || closed.get()) {
                    finish();
                    return;
                }
                state.compareAndSet(TaskState.RUNNING, TaskState.QUEUED);
                try {
                    handleRef.set(timer().schedule(this, period, TimeUnit.MILLISECONDS));
                } catch (RejectedExecutionException | IllegalStateException e) {
                    // 关闭竞态：计时器已停，按取消处理
                    finish();
                }
            }

            /** 周期结束（取消 / Scope 停止 / 调度器关闭）——只执行一次。 */
            private void finish() {
                if (!scheduledOnce.compareAndSet(false, true)) {
                    return;
                }
                state.set(TaskState.CANCELLED);
                cancelledTasks.incrementAndGet();
                taskMap.remove(taskId);
                future.cancel(false);
            }
        };

        taskMap.put(taskId, new ScheduledTask(this, taskId, scope, work, priority,
                future, state, System.nanoTime(), 0L));

        try {
            handleRef.set(timer().schedule(tick, delay > 0 ? delay : period, TimeUnit.MILLISECONDS));
        } catch (RejectedExecutionException e) {
            taskMap.remove(taskId);
            state.set(TaskState.CANCELLED);
            future.cancel(false);
            throw new org.loader.runtime.error.SchedulerError(
                    "周期任务调度失败",
                    org.loader.runtime.error.ErrorContext.builder("scheduler")
                            .owner(id).operation("submitRepeating").build(), e);
        }

        return new TaskHandleImpl(taskId, scope, priority, future, state, () -> {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> h = handleRef.get();
            if (h != null) {
                h.cancel(false);
            }
            state.set(TaskState.CANCELLED);
            cancelledTasks.incrementAndGet();
            taskMap.remove(taskId);
            future.cancel(false);
        });
    }

    /**
     * Creates a child task within a parent scope.
     * Implements structured concurrency: when parent stops, children stop.
     *
     * <p>父 Scope 上的监听器在任务终结时<b>会被摘掉</b> —— 旧实现永久挂载，
     * 一个频繁提交子任务的 Mod 会让父 Scope 的监听器列表无限增长，
     * 且每次状态迁移都要遍历这些死监听器。
     */
    public TaskHandle submitChild(Scope scope, Scope parentScope, Runnable work, TaskPriority priority) {
        TaskHandle handle = submit(scope, work, priority);
        org.loader.runtime.kernel.ScopeListener cancelOnStop = (s, from, to) -> {
            if (to == org.loader.runtime.kernel.LifecycleState.STOPPING) {
                handle.cancel();
            }
        };
        parentScope.addListener(cancelOnStop);
        handle.future().whenComplete((v, err) -> parentScope.removeListener(cancelOnStop));
        return handle;
    }

    /**
     * Cancels a task by handle.
     */
    public boolean cancel(TaskHandle handle) {
        if (handle == null) {
            return false;
        }
        return handle.cancel();
    }

    /**
     * Cancels all tasks owned by a scope.
     */
    public void cancelAll(Scope scope) {
        for (ScheduledTask t : taskMap.values()) {
            if (t.ownerScope() != null && t.ownerScope().equals(scope)) {
                t.state().compareAndSet(TaskState.QUEUED, TaskState.CANCELLED);
                t.state().compareAndSet(TaskState.RUNNING, TaskState.CANCELLED);
                taskQueue.remove(t);
                t.future().cancel(true);
            }
        }
    }

    /**
     * 当前活跃（未终结）任务数。
     */
    public int activeTaskCount() {
        return taskMap.size();
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
                taskMap.size(),
                totalExecutionTime.get(),
                waitTimeSamples.percentileMs(50),
                waitTimeSamples.percentileMs(95),
                waitTimeSamples.percentileMs(99),
                executionTimeSamples.percentileMs(50),
                executionTimeSamples.percentileMs(95),
                executionTimeSamples.percentileMs(99)
        );
    }

    private ScheduledExecutorService timer() {
        ScheduledExecutorService current = timer;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (timer == null) {
                timer = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "runtime-scheduler-timer-" + id);
                    t.setDaemon(true);
                    return t;
                });
            }
            return timer;
        }
    }

    /**
     * Gracefully shuts down the scheduler.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 标记并清空所有待执行任务
        for (ScheduledTask t : taskQueue.drainAll()) {
            t.state().set(TaskState.CANCELLED);
            t.future().cancel(true);
            taskMap.remove(t.taskId());
        }

        ScheduledExecutorService t = timer;
        if (t != null) {
            t.shutdownNow();
        }

        // Try to cancel running tasks
        for (ScheduledTask task : taskMap.values()) {
            task.state().compareAndSet(TaskState.RUNNING, TaskState.CANCELLED);
            task.state().compareAndSet(TaskState.QUEUED, TaskState.CANCELLED);
            task.future().cancel(true);
        }
        taskMap.clear();

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

    /**
     * 定长环形延迟采样器。
     *
     * <p>旧实现用 {@code CopyOnWriteArrayList} 存样本：每次 {@code add}
     * 都要复制整个底层数组，在 10,000 个样本时是每次任务提交复制 10,000 个引用 ——
     * 这条路径在每次 {@code submit()} 上，等于给热路径加了一次 O(n) 复制。
     *
     * <p>环形缓冲写入是 O(1)、零分配，代价是分位数基于最近 N 个样本
     * （对「当前延迟水平」这个用途来说，这比历史全量更合适）。
     */
    private static final class LatencySamples {
        private final long[] ring;
        private int write;
        private int filled;
        private final Object lock = new Object();

        LatencySamples(int capacity) {
            this.ring = new long[capacity];
        }

        void add(long nanos) {
            synchronized (lock) {
                ring[write] = nanos;
                write = (write + 1) % ring.length;
                if (filled < ring.length) {
                    filled++;
                }
            }
        }

        double percentileMs(int p) {
            long[] copy;
            synchronized (lock) {
                if (filled == 0) {
                    return 0.0;
                }
                copy = new long[filled];
                System.arraycopy(ring, 0, copy, 0, filled);
            }
            Arrays.sort(copy);
            int index = (int) Math.ceil(p / 100.0 * copy.length) - 1;
            index = Math.max(0, Math.min(index, copy.length - 1));
            return copy[index] / 1_000_000.0;
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
        public void put(Runnable r) {
            offer(r);
        }

        @Override
        public Runnable poll() {
            return delegate.poll();
        }

        @Override
        public Runnable peek() {
            return delegate.peek();
        }

        @Override
        public Runnable take() throws InterruptedException {
            return delegate.take();
        }

        @Override
        public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.poll(timeout, unit);
        }

        @Override
        public int drainTo(Collection<? super Runnable> c) {
            return delegate.drainTo(c);
        }

        @Override
        public int drainTo(Collection<? super Runnable> c, int maxElements) {
            return delegate.drainTo(c, maxElements);
        }

        @Override
        public boolean remove(Object o) {
            ScheduledTask st = unwrap(o instanceof Runnable r ? r : null);
            if (st == null) {
                return false;
            }
            return delegate.remove(st);
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        /** 清空并返回全部待执行任务（关闭时用于标记取消）。 */
        List<ScheduledTask> drainAll() {
            List<ScheduledTask> all = new ArrayList<>(delegate);
            delegate.clear();
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
            scheduler.waitTimeSamples.add(startTime - createdAtNanos);
            try {
                // 取消与开始之间是竞态：用 CAS 抢 RUNNING，抢不到说明
                // 在排队期间已经被取消 —— 绝不能把它改回 RUNNING。
                if (!state.compareAndSet(TaskState.QUEUED, TaskState.RUNNING)) {
                    scheduler.cancelledTasks.incrementAndGet();
                    scheduler.taskMap.remove(taskId);
                    future.cancel(true);
                    return;
                }
                if (ownerScope.isStopped() || scheduler.closed.get()) {
                    state.set(TaskState.CANCELLED);
                    scheduler.cancelledTasks.incrementAndGet();
                    scheduler.taskMap.remove(taskId);
                    future.cancel(true);
                    return;
                }

                work.run();

                // 执行期间被取消（协作式取消），不得把 CANCELLED 覆盖成 COMPLETED
                if (state.compareAndSet(TaskState.RUNNING, TaskState.COMPLETED)) {
                    scheduler.completedTasks.incrementAndGet();
                    // 先摘表再完成 future：否则调用方 future().join() 返回时
                    // 任务还挂在活跃表里，activeTaskCount() 会短暂给出错误值 ——
                    // 测试里是偶发失败，生产里是监控面板的假峰值。
                    scheduler.taskMap.remove(taskId);
                    future.complete(null);
                } else {
                    scheduler.cancelledTasks.incrementAndGet();
                    scheduler.taskMap.remove(taskId);
                    future.cancel(true);
                }
            } catch (Throwable e) {
                if (state.compareAndSet(TaskState.RUNNING, TaskState.FAILED)) {
                    scheduler.failedTasks.incrementAndGet();
                    scheduler.taskMap.remove(taskId);
                    future.completeExceptionally(e);
                } else {
                    scheduler.cancelledTasks.incrementAndGet();
                    scheduler.taskMap.remove(taskId);
                    future.cancel(true);
                }
            } finally {
                long execNanos = System.nanoTime() - startTime;
                scheduler.totalExecutionTime.addAndGet(execNanos);
                scheduler.executionTimeSamples.add(execNanos);
                // 兜底：任何未覆盖的路径都不能让任务永久留在活跃表里
                scheduler.taskMap.remove(taskId);
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
