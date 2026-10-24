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
 *
 * <h2>排队与拒绝契约（可测量，不是「大概有背压」）</h2>
 *
 * <pre>
 *   容量      queueCapacity（默认 {@value #DEFAULT_QUEUE_CAPACITY}，构造时可指定）
 *   排序      TaskPriority 升序；同优先级按 createdAtNanos 先进先出
 *   线程数    corePoolSize 常驻，饱和时最多扩到 corePoolSize * 2
 *   超限      ThreadPoolExecutor 拒绝 → submit() 抛 SchedulerError（显式失败，绝不静默丢弃）
 *   关闭后    submit() 抛 IllegalStateException
 *   取消      未开始的任务从队列移除；已开始的走协作取消
 *   指标      metrics().queueDepth()/remainingCapacity()/rejected()/active()
 * </pre>
 *
 * <p><b>为什么必须是有界队列</b>：无界队列下 {@code ThreadPoolExecutor}
 * 永远不会调用拒绝策略 —— 线程数停在 corePoolSize，队列无限增长，
 * 延迟无上限地变大，最后 OOM。历史上这里配了
 * {@code CallerRunsPolicy} 并注释「队列无界，改用调用者执行以施加背压」，
 * 但无界队列根本不会触发拒绝，那段策略是一行永远不会执行的代码。
 * 有界队列 + 显式拒绝才是真的背压：调用方立刻知道「系统撑不住了」。
 *
 * <p><b>这个 Scheduler 不是并行 Tick 引擎</b>：Minecraft 的 tick 仍由单一
 * 驱动线程串行推进（见 {@code TickEngine}）。Scheduler 负责的是
 * <i>与 tick 解耦的异步工作</i>及其生命周期管理，它不会把一次 tick 拆到
 * 多线程并行执行，也不改变游戏逻辑的线程亲和性。
 */
public class Scheduler implements org.loader.runtime.kernel.Resource {

    /**
     * 默认队列容量。
     *
     * <p>取值远高于正常帧内提交量（每 tick 通常是几十个任务），
     * 因此正常游戏不会碰到上限；它拦的是「持续堆积」这种真正的异常。
     * 单条 {@code ScheduledTask} 约百字节，满队列约 1.6MB，可以接受。
     */
    public static final int DEFAULT_QUEUE_CAPACITY = 16_384;

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
    private final AtomicLong rejectedTasks = new AtomicLong(0);
    /** 正在执行的线程数。不能用 taskMap 大小推算 —— 见 run() 里的清理。 */
    private final AtomicLong activeTasks = new AtomicLong(0);
    private final AtomicLong totalExecutionTime = new AtomicLong(0);

    // Sample collectors for percentile metrics (bounded to prevent memory leaks)
    private final List<Long> waitTimeSamples = new CopyOnWriteArrayList<>();
    private final List<Long> executionTimeSamples = new CopyOnWriteArrayList<>();
    private static final int MAX_SAMPLES = 10_000;

    /** 重复任务的计时器，关闭时一并清理，避免线程泄漏。 */
    private final Set<java.util.concurrent.ScheduledExecutorService> repeatingTimers =
            ConcurrentHashMap.newKeySet();

    public Scheduler(String id, Scope owner, int corePoolSize) {
        this(id, owner, corePoolSize, DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * @param corePoolSize  常驻工作线程数
     * @param queueCapacity 队列容量，必须 &gt; 0；超出后 submit 显式失败
     */
    public Scheduler(String id, Scope owner, int corePoolSize, int queueCapacity) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity 必须 > 0，实际: " + queueCapacity);
        }
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        this.taskQueue = new PriorityWorkQueue(
                // TaskPriority 的公开语义是「值越大优先级越高」（ABI 与 runtime
                // 两个枚举的 javadoc 都这么写），所以必须**降序**排列。
                // 历史实现按升序比较，于是 LOW(0) 先于 CRITICAL(20) 执行 ——
                // 优先级完全反了，且没有任何测试覆盖到执行顺序。
                Comparator.<ScheduledTask>comparingInt((ScheduledTask t) -> t.priority().value())
                        .reversed()
                        // 同优先级按提交顺序（先到先执行），保证公平与可预测。
                        // 注意 thenComparing 链在 reversed() 之后，次级键不受影响。
                        .thenComparingLong(ScheduledTask::createdAtNanos),
                queueCapacity
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
                // 队列有界，因此这里真的会被触发：submit() 把它转成 SchedulerError，
                // 让调用方立刻知道系统饱和，而不是把任务悄悄丢进一个无底洞。
                new ThreadPoolExecutor.AbortPolicy()
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
            rejectedTasks.incrementAndGet();
            throw new org.loader.runtime.error.SchedulerError(
                    "线程池拒绝任务: " + taskId
                            + "（" + (closed.get()
                                    ? "executor 已关闭"
                                    : "队列已满 " + taskQueue.size() + "/" + taskQueue.capacity())
                            + "）",
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
     * <p><b>与队列的关系</b>：周期任务由独立计时器线程触发，<b>不经过</b>
     * 优先级队列，因此不受 queueCapacity 限制，{@code priority} 也只
     * 记录在 handle 上、不参与执行顺序（work 直接跑在计时器线程上）。
     * 这是当前实现的已知边界，写在这里以免被误读成「周期任务也有背压」。
     *
     * <p><b>终止语义</b>：cancel()、scope 停止、scheduler 关闭、计时器被关闭
     * 这四种情况都会让计时器线程被释放，并把 future 正常完成 —— 否则
     * 一个已停止 Scope 的周期任务会永久占住一个线程（无池化，一任务一线程），
     * 等待 future 的调用方也会挂死。
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
        CompletableFuture<Void> future = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<TaskState> stateRef =
                new java.util.concurrent.atomic.AtomicReference<>(TaskState.RUNNING);

        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (cancelled.get() || scope.isStopped() || closed.get()) {
                    terminate();
                    return;
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
                        terminate();
                    }
                } else {
                    terminate();
                }
            }

            /**
             * 释放计时器线程。{@code newSingleThreadScheduledExecutor} 的核心线程
             * 不会自行超时退出，不关就是泄漏。
             */
            private void terminate() {
                timer.shutdownNow();
                synchronized (repeatingTimers) {
                    repeatingTimers.remove(timer);
                }
                stateRef.compareAndSet(TaskState.RUNNING, TaskState.COMPLETED);
                future.complete(null);
            }
        };

        // 必须在 schedule 之前登记：tick 可能在登记前就因 scope 停止而
        // terminate()，那时它会从集合里移除自己，后登记就会留下一个死引用。
        synchronized (repeatingTimers) {
            repeatingTimers.add(timer);
        }

        try {
            long initial = delay > 0 ? delay : period;
            handleRef[0] = timer.schedule(tick, initial, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            timer.shutdownNow();
            synchronized (repeatingTimers) {
                repeatingTimers.remove(timer);
            }
            throw new org.loader.runtime.error.SchedulerError(
                    "周期任务调度失败", 
                    org.loader.runtime.error.ErrorContext.builder("scheduler")
                            .owner(id).operation("submitRepeating").build(), e);
        }

        return new TaskHandleImpl(
                id + "-repeating-" + taskCounter.incrementAndGet(),
                scope, TaskState.RUNNING, priority, future, stateRef, () -> {
                    cancelled.set(true);
                    stateRef.set(TaskState.CANCELLED);
                    java.util.concurrent.ScheduledFuture<?> h = handleRef[0];
                    if (h != null) {
                        h.cancel(false);
                    }
                    timer.shutdownNow();
                    synchronized (repeatingTimers) {
                        repeatingTimers.remove(timer);
                    }
                    // 注意：这里**不能** future.complete(null)。
                    // TaskHandleImpl.cancel() 紧接着会 future.cancel(true)，
                    // 若 future 已被正常完成，那次 cancel 返回 false ——
                    // 于是「取消成功」却报告失败。
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
     *
     * <p>取消语义由句柄自身持有（队列任务走「出队 + 状态 CAS」，
     * 周期任务走「停计时器」）。历史上这里先查 {@code taskMap}，而周期任务
     * 从不进 {@code taskMap} —— 于是 {@code scheduler.cancel(周期任务)} 永远
     * 返回 false，调用方以为没取消成功。
     */
    public boolean cancel(TaskHandle handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        return handle.cancel();
    }

    /**
     * Cancels all tasks owned by a scope.
     *
     * <p>必须真正把任务从队列里摘掉：只把状态标成 CANCELLED 而留在队列中，
     * 会让「已取消」的任务继续占着容量（进而触发队列满拒绝），
     * 等待它们的 future 也会一直挂到被出队为止。
     */
    public void cancelAll(Scope scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        for (ScheduledTask task : taskMap.values()) {
            if (!task.ownerScope().equals(scope)) {
                continue;
            }
            TaskState current = task.state().get();
            if (current == TaskState.QUEUED || current == TaskState.CREATED) {
                if (task.state().compareAndSet(current, TaskState.CANCELLED)) {
                    taskQueue.remove(task);
                    task.future().cancel(true);
                    cancelledTasks.incrementAndGet();
                    taskMap.remove(task.taskId());
                }
            } else if (current == TaskState.RUNNING) {
                // 已开始执行：只能协作取消（中断标志交给任务自己处理）
                task.future().cancel(true);
            }
        }
    }

    /**
     * Returns current scheduler metrics.
     *
     * <p>{@code active} 是「正在执行」的计数，不是 {@code taskMap} 大小推算出来的
     * —— 已完成的任务会从 {@code taskMap} 中移除（否则长跑游戏每秒泄漏若干条），
     * 用集合大小做差会得到负数。
     *
     * <p><b>可观测契约</b>：{@code await()} 返回之后 {@code active} 必然已归零。
     * 递减发生在 future 完成之前（见 {@code ScheduledTask#run()}），
     * 因此「任务完成」与「指标归零」之间不存在可观测的中间态；
     * 调用方不需要 sleep 一轮再来读指标。
     */
    public SchedulerMetrics metrics() {
        return new SchedulerMetrics(
                submittedTasks.get(),
                completedTasks.get(),
                failedTasks.get(),
                cancelledTasks.get(),
                rejectedTasks.get(),
                activeTasks.get(),
                taskQueue.size(),
                taskQueue.capacity(),
                taskQueue.remainingCapacity(),
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
                taskMap.remove(t.taskId());
            }

            // 清理重复任务的计时器线程
            for (var timer : repeatingTimers) {
                timer.shutdownNow();
            }
            repeatingTimers.clear();

            // 正在执行的任务：置终态并取消 future（协作式，只能通知不能强杀）
            for (ScheduledTask t : taskMap.values()) {
                t.future().cancel(true);
            }

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
        /** 容量上限。{@code PriorityBlockingQueue} 本身无界，必须自己把关。 */
        private final int capacity;
        /** size 判定 + 插入必须原子，否则并发下会超出上限。 */
        private final Object capacityLock = new Object();

        PriorityWorkQueue(Comparator<ScheduledTask> comparator, int capacity) {
            this.delegate = new java.util.concurrent.PriorityBlockingQueue<>(256, comparator);
            this.capacity = capacity;
        }

        int capacity() {
            return capacity;
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
         * 入队。返回 {@code false} 表示<b>队列已满</b> —— 这不是错误，
         * 而是让 {@code ThreadPoolExecutor} 有机会扩容或显式拒绝。
         *
         * <p>历史实现无条件返回 {@code true}（并注释「队列应该永远接得住」），
         * 配合无界队列的结果是：线程数永远停在 corePoolSize，队列无限增长，
         * 拒绝策略形同虚设，任务延迟无上限地变大直到 OOM。
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
            synchronized (capacityLock) {
                if (delegate.size() >= capacity) {
                    return false;
                }
                boolean added = delegate.offer(st);
                if (added) {
                    pending.add(st);
                }
                return added;
            }
        }

        // BlockingQueue 的两个额外插入方法。AbstractQueue 只要求 offer(E)，
        // 但 ThreadPoolExecutor 在 prestart/corePool 扩容路径上会调用
        // offer(e, timeout, unit)；不实现就编译不过。
        @Override
        public boolean offer(Runnable r, long timeout, TimeUnit unit) {
            return offer(r);
        }

        /**
         * 满队列时 {@code put} 必须抛异常而不是永久阻塞。
         *
         * <p>{@code ThreadPoolExecutor} 不会调用 {@code put}，但外部若把它当
         * 普通 BlockingQueue 使用，静默阻塞一个游戏线程是不可接受的。
         */
        @Override
        public void put(Runnable r) throws InterruptedException {
            if (!offer(r)) {
                throw new IllegalStateException(
                        "Scheduler 队列已满（容量 " + capacity + "），拒绝阻塞等待: " + r);
            }
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
            return Math.max(0, capacity - delegate.size());
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

            scheduler.activeTasks.incrementAndGet();
            long startTime = System.nanoTime();
            boolean cancelled = false;
            Throwable failure = null;
            try {
                scheduler.addSample(scheduler.waitTimeSamples, startTime - createdAtNanos);

                if (state.get() == TaskState.CANCELLED) {
                    cancelled = true;
                } else if (ownerScope.isStopped() || scheduler.closed.get()) {
                    state.set(TaskState.CANCELLED);
                    cancelled = true;
                } else {
                    state.set(TaskState.RUNNING);
                    work.run();
                    state.set(TaskState.COMPLETED);
                    scheduler.completedTasks.incrementAndGet();
                }
            } catch (Throwable e) {
                state.set(TaskState.FAILED);
                failure = e;
            } finally {
                long execNanos = System.nanoTime() - startTime;
                scheduler.totalExecutionTime.addAndGet(execNanos);
                scheduler.addSample(scheduler.executionTimeSamples, execNanos);
                // 终态任务不再需要句柄索引。不移除的话，每个生命周期执行过的
                // 任务都会常驻 taskMap —— 一个跑几小时的服务端实例会稳定泄漏。
                scheduler.taskMap.remove(taskId);

                // 顺序是关键：先把 active 减回去，**之后**才唤醒 future 的等待方。
                //
                // 历史实现在 try 里 future.complete(null)，在 finally 里才
                // activeTasks.decrementAndGet() —— 于是 await() 已经返回、调用方
                // 立刻读 metrics() 仍能看到 active == 1，「任务已完成」与
                // 「指标归零」之间留下一道可观测的裂缝（CI 上
                // ResourceLeakTest.scheduler_tasksCleanedAfterCompletion 就是这么挂的，
                // 同一形态也潜伏在 SchedulerTest.submit_executesTask 里）。
                //
                // 把 decrement 放在 complete 之前，可以让它 happens-before future
                // 完成（CompletableFuture 的 result 是 volatile 写，get() 读到它即
                // 建立 happens-before），于是「await() 返回后 active == 0」由 JMM
                // 保证，而不是靠调用方 sleep 去赌时序。
                scheduler.activeTasks.decrementAndGet();
            }

            // 通知放最后：等待方一被唤醒，看到的就必须是终态一致的指标。
            if (cancelled) {
                scheduler.cancelledTasks.incrementAndGet();
                future.cancel(true);
            } else if (failure != null) {
                scheduler.failedTasks.incrementAndGet();
                future.completeExceptionally(failure);
            } else {
                future.complete(null);
            }
        }
    }

    /**
     * Snapshot of scheduler metrics.
     *
     * @param submitted           提交总数
     * @param completed           成功完成数
     * @param failed              抛异常数
     * @param cancelled           取消数（含关闭时被清理的）
     * @param rejected            因队列满/关闭被拒绝的提交数
     * @param active              当前正在执行的线程数
     * @param queueDepth          当前队列深度
     * @param queueCapacity       队列容量上限
     * @param remainingCapacity   队列剩余容量
     */
    public record SchedulerMetrics(
            long submitted,
            long completed,
            long failed,
            long cancelled,
            long rejected,
            long active,
            int queueDepth,
            int queueCapacity,
            int remainingCapacity,
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
