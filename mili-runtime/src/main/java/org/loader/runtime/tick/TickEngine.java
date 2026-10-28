package org.loader.runtime.tick;

import org.loader.runtime.diagnostics.ModAttribution;
import org.loader.runtime.diagnostics.ModOwners;
import org.loader.runtime.diagnostics.PerformanceMonitor;
import org.loader.runtime.diagnostics.TickWatchdog;
import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.Scope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TickEngine —— 驱动 {@link TickContract} 真实执行的引擎。
 *
 * <p><b>这是 Tick 集成的核心</b>。它取代了旧的"轮询推测 tick"：
 * 由 Minecraft 真实 tick 入口调用 {@link #beginTick()} 与 {@link #endTick(TickContract)}，
 * 中间 Mod 通过 {@link #onStage} / {@link #submit} 参与阶段执行。
 *
 * <p><b>执行模型（当前为正确的单线程）</b>：
 * <pre>
 *   beginTick()                    → 新建 TickContract，阶段=TICK_START
 *   ├─ advanceTo(PRE_TICK)         → 执行 PRE_TICK 回调
 *   ├─ advanceTo(SCHEDULE)         → 执行 SCHEDULE 回调（可提交 task）
 *   ├─ advanceTo(CORE_TICK)        → 执行 Minecraft 真实世界/实体 tick
 *   │     └─ 执行本阶段提交的任务
 *   ├─ advanceTo(ASYNC_COMPLETION) → 等待异步任务收敛（awaitAllTasks）
 *   ├─ advanceTo(POST_TICK)        → 执行 POST_TICK 回调
 *   ├─ advanceTo(TICK_END)         → 执行 TICK_END 回调
 *   endTick(contract)              → complete()，产出 TickMetrics，累加指标
 * </pre>
 *
 * <p><b>正确性优先</b>：所有阶段回调与任务都在调用 {@link #beginTick()} 的
 * 那个线程（= Minecraft 主线程）上执行，保证 happens-before 链完整。
 * 并行化是后续阶段的事，当前 <b>绝不</b> 引入跨线程执行。
 */
public final class TickEngine implements Resource {

    private final String id;
    private final Scope owner;
    private final long tickPeriodMs;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ── 阶段回调注册表 ─────────────────────────────────────────────────────
    //
    // 用 CopyOnWriteArrayList 而不是 ArrayList：注册发生在 Mod 的 initialize()
    // （任意线程），遍历发生在 tick 线程。普通 ArrayList 在这种组合下会抛
    // ConcurrentModificationException，而抛出点在 tick 循环里 ——
    // 表现为「游戏偶尔崩一次」，且堆栈指向 Minecraft。
    // 注册频率是「一次启动几十次」，遍历频率是「每秒 20 次」，
    // 写时复制的成本可以忽略。

    private final List<StageListener> preTickListeners = new CopyOnWriteArrayList<>();
    private final List<StageListener> scheduleListeners = new CopyOnWriteArrayList<>();
    private final List<StageListener> coreTickListeners = new CopyOnWriteArrayList<>();
    private final List<StageListener> asyncCompletionListeners = new CopyOnWriteArrayList<>();
    private final List<StageListener> postTickListeners = new CopyOnWriteArrayList<>();
    private final List<StageListener> tickEndListeners = new CopyOnWriteArrayList<>();

    /**
     * tick 边界观测者 —— 平台总线的事件桥接点。
     *
     * <p>与 {@link StageListener} 分开：阶段监听器是<b>参与</b> tick 的
     * （可以提交任务、读契约），观测者只<b>看</b>结果。把两者混在一起
     * 会让事件发布方有能力改 tick 契约 —— 那是把观测者变成了参与者。
     */
    private final List<TickObserver> observers = new CopyOnWriteArrayList<>();

    /** 跨 tick 队列：runOnNextTick / runAfterTicks 的落点。 */
    private final Deque<ScheduledTickWork> nextTickQueue = new ArrayDeque<>();
    private final Deque<ScheduledTickWork> delayedQueue = new ArrayDeque<>();

    /**
     * 每 tick 执行的常驻工作（{@code TickService.runEveryTick} 的落点）。
     *
     * <p>用 {@link CopyOnWriteArrayList}：注册发生在 Mod 初始化（任意线程），
     * 执行发生在 tick 线程。
     */
    private final List<EveryTickWork> everyTickWork = new CopyOnWriteArrayList<>();

    // ── 运行时状态 ─────────────────────────────────────────────────────────

    private final AtomicReference<TickContract> currentContract = new AtomicReference<>();
    private final AtomicLong tickCounter = new AtomicLong(0);
    private final AtomicLong missedDeadlineCount = new AtomicLong(0);
    private final AtomicLong failedTickCount = new AtomicLong(0);
    private final AtomicLong maxTickDurationMs = new AtomicLong(0);
    /** 累计 tick 耗时（纳秒）。用原子量而不是裸 long：读取方可能在别的线程。 */
    private final AtomicLong totalTickDurationNanos = new AtomicLong(0);

    /** 最近若干 tick 的指标环形缓冲，供诊断使用。 */
    private static final int METRICS_HISTORY = 200;
    private final TickContract.TickMetrics[] metricRing =
            new TickContract.TickMetrics[METRICS_HISTORY];
    private int metricWrite;
    private int metricFilled;

    /**
     * 性能采样器 —— 可选。
     *
     * <p>为空时引擎照常工作：TPS 是诊断能力，不是 tick 正确性的前提。
     * 由 {@code MinecraftBootstrap} 注入 Runtime 持有的同一个实例，
     * 这样 tick 与帧率进入同一份窗口数据。
     */
    private final PerformanceMonitor performance;

    /**
     * 按 Mod 归因的 tick 成本。
     *
     * <p>归因的钥匙是任务的<b>实现类 → 类加载器 → ModClassLoader</b>
     * （见 {@link ModOwners}），因此 Mod 不需要做任何事就能被归因 ——
     * 这与"观测不拦截"的决策一致：不强制 Mod 走 Scheduler，只是
     * 走了就能被看见。
     */
    private final ModAttribution attribution = new ModAttribution();

    /**
     * 停滞看门狗。首个 tick 才启动 —— 没有 tick 的进程不该多一个线程，
     * 否则"空转的加载器"在本就脆弱的容器环境里又多一个待排查对象。
     */
    private final TickWatchdog watchdog = new TickWatchdog("tick");
    private final AtomicBoolean watchdogStarted = new AtomicBoolean(false);

    /** 驱动线程（= Minecraft 主线程），用于校验阶段推进的线程归属。 */
    private volatile Thread driverThread;
    /** 引擎启动墙钟时间，用于计算真实 TPS。 */
    private final long startedAtNanos = System.nanoTime();

    public TickEngine(String id, Scope owner) {
        this(id, owner, TickContract.DEFAULT_TICK_PERIOD_MS, null);
    }

    public TickEngine(String id, Scope owner, long tickPeriodMs) {
        this(id, owner, tickPeriodMs, null);
    }

    public TickEngine(String id, Scope owner, PerformanceMonitor performance) {
        this(id, owner, TickContract.DEFAULT_TICK_PERIOD_MS, performance);
    }

    public TickEngine(String id, Scope owner, long tickPeriodMs,
                      PerformanceMonitor performance) {
        this.id = id;
        this.owner = owner;
        this.tickPeriodMs = tickPeriodMs;
        this.performance = performance;
    }

    // ── Resource ───────────────────────────────────────────────────────────

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

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // 看门狗先停：它观测的对象（当前任务上下文）马上要被清空，
            // 先关观测者再拆被观测者，避免关闭期产生一条假的"停滞"报告。
            watchdog.close();
            synchronized (nextTickQueue) {
                nextTickQueue.clear();
                delayedQueue.clear();
            }
            TickContract c = currentContract.getAndSet(null);
            if (c != null && !c.isCompleted()) {
                c.cancel();
            }
            // 监听器是 Mod 传进来的 lambda，持有它的类加载器与对象图。
            // 不清空的话，引擎（作为 Scope 资源）关闭后仍可通过这些引用
            // 把整个 Mod 留在堆上 —— 这正是 ClassLoaderLeakTest 守的那类泄漏，
            // 只是入口从「类引用」换成了「事件回调」。
            clearListeners();
            synchronized (this) {
                Arrays.fill(metricRing, null);
                metricWrite = 0;
                metricFilled = 0;
            }
        }
    }

    private void clearListeners() {
        preTickListeners.clear();
        scheduleListeners.clear();
        coreTickListeners.clear();
        asyncCompletionListeners.clear();
        postTickListeners.clear();
        tickEndListeners.clear();
        observers.clear();
        everyTickWork.clear();
    }

    // ── 核心：tick 生命周期 ────────────────────────────────────────────────

    /**
     * 开始一个新 tick。由 Minecraft 真实 tick 入口调用。
     *
     * <p>会先执行上一 tick 遗留的延迟队列到期工作。
     *
     * @return 本 tick 的执行契约
     */
    public TickContract beginTick() {
        if (closed.get()) {
            throw new IllegalStateException("TickEngine 已关闭");
        }
        if (driverThread == null) {
            driverThread = Thread.currentThread();
        } else if (driverThread != Thread.currentThread()) {
            // 并行化前的守卫：阶段推进必须绑定同一线程。
            // 未来 TickPool 会放宽此约束，届时需引入 ExecutionContext 校验。
            throw new IllegalStateException(
                    "tick 必须由同一线程推进。当前 " + Thread.currentThread().getName()
                            + "，驱动线程 " + driverThread.getName());
        }

        drainElapsedDelayedWork();

        // 首个 tick 才起看门狗线程：见字段注释。
        if (watchdogStarted.compareAndSet(false, true)) {
            watchdog.start();
        }

        TickContract contract = TickContract.create(tickPeriodMs, "minecraft");
        currentContract.set(contract);
        long tick = tickCounter.incrementAndGet();

        // TICK_START → PRE_TICK
        contract.advanceTo(TickContract.TickPhase.PRE_TICK);
        fire(preTickListeners, contract, TickContract.TickPhase.PRE_TICK);

        // 常驻的「每 tick」工作：在 PRE_TICK 之后、世界 tick 之前执行。
        runEveryTickWork();

        notifyTickStart(contract, tick);

        return contract;
    }

    /**
     * 结束当前 tick 并产出指标。由 Minecraft 真实 tick 入口在 tick 尾部调用。
     *
     * <p><b>执行模型</b>：注入点只能包住整个 {@code tickServer}（方法头与
     * 每个 return 之前），无法精确切到「世界 tick 之间」。因此本 tick 提交的
     * 任务在这里执行 —— 也就是<b>世界逻辑之后、POST_TICK 之前</b>。
     * 这与「任务在 CORE_TICK 期间执行」的旧文档略有差异，但它是注入模型下
     * 唯一诚实的语义：声称能在世界 tick 中间插进去，实际做不到，
     * 比晚一点执行要糟糕得多。
     *
     * @return 本 tick 指标
     */
    public TickContract.TickMetrics endTick(TickContract contract) {
        if (contract == null) {
            throw new IllegalArgumentException("contract 不能为 null");
        }
        if (!contract.isCancelled() && !contract.isCompleted()) {
            // 执行本 tick 提交的任务（仍在 CORE_TICK 阶段内）
            try {
                runPendingTasks(contract);
            } catch (RuntimeException e) {
                contract.fail(e);
            }
            if (contract.phase().ordinal() < TickContract.TickPhase.ASYNC_COMPLETION.ordinal()) {
                contract.advanceTo(TickContract.TickPhase.ASYNC_COMPLETION);
                fire(asyncCompletionListeners, contract, TickContract.TickPhase.ASYNC_COMPLETION);
                contract.advanceTo(TickContract.TickPhase.POST_TICK);
                fire(postTickListeners, contract, TickContract.TickPhase.POST_TICK);
                contract.advanceTo(TickContract.TickPhase.TICK_END);
                fire(tickEndListeners, contract, TickContract.TickPhase.TICK_END);
            }
        }

        TickContract.TickMetrics metrics = contract.complete();
        record(metrics);
        currentContract.set(null);
        notifyTickEnd(tickCounter.get(), metrics);
        return metrics;
    }

    // ── 观测者（平台总线的事件桥接点） ─────────────────────────────────────

    /**
     * 注册 tick 边界观测者。
     *
     * <p>观测者<b>只读</b>：它拿到契约与指标，但无法推进阶段、无法提交任务。
     * 平台用它把 tick 事件发布到总线；Mod 不应该直接用本方法
     * （应该订阅 {@code TickEvent}）。
     */
    public void addObserver(TickObserver observer) {
        if (observer != null) {
            observers.add(observer);
        }
    }

    /** 注销观测者。 */
    public void removeObserver(TickObserver observer) {
        if (observer != null) {
            observers.remove(observer);
        }
    }

    private void notifyTickStart(TickContract contract, long tick) {
        if (observers.isEmpty()) {
            return;
        }
        for (TickObserver o : observers) {
            try {
                o.onTickStart(tick, contract);
            } catch (Throwable t) {
                contract.fail(t);
            }
        }
    }

    private void notifyTickEnd(long tick, TickContract.TickMetrics metrics) {
        if (observers.isEmpty()) {
            return;
        }
        for (TickObserver o : observers) {
            try {
                o.onTickEnd(tick, metrics);
            } catch (Throwable ignored) {
                // tick 已结束，观测者异常无处归属；不得影响下一个 tick
            }
        }
    }

    /**
     * tick 边界观测者。
     */
    public interface TickObserver {
        /** 一个 tick 刚开始（世界尚未被本 tick 修改）。 */
        void onTickStart(long tick, TickContract contract);

        /** 一个 tick 已结束。 */
        void onTickEnd(long tick, TickContract.TickMetrics metrics);
    }

    /**
     * 推进到 SCHEDULE 阶段并触发调度回调。
     * Mod 在此阶段提交本 tick 的任务。
     *
     * <p><b>幂等</b>：若契约已处于或越过 SCHEDULE，本方法为空操作。
     * 生产路径（{@code TickBridge}）与手工推进（测试、自定义 tick 源）
     * 会同时调用它，不做幂等就会在第二次调用时抛「不能回退阶段」。
     */
    public void advanceToSchedule(TickContract contract) {
        requireCurrent(contract);
        if (contract.phase().ordinal() >= TickContract.TickPhase.SCHEDULE.ordinal()) {
            return;
        }
        contract.advanceTo(TickContract.TickPhase.SCHEDULE);
        fire(scheduleListeners, contract, TickContract.TickPhase.SCHEDULE);
    }

    /**
     * 推进到 CORE_TICK 阶段 —— Minecraft 真实世界/实体 tick 在此执行。
     *
     * <p>幂等：见 {@link #advanceToSchedule}。
     */
    public void advanceToCoreTick(TickContract contract) {
        requireCurrent(contract);
        if (contract.phase().ordinal() >= TickContract.TickPhase.CORE_TICK.ordinal()) {
            return;
        }
        contract.advanceTo(TickContract.TickPhase.CORE_TICK);
        fire(coreTickListeners, contract, TickContract.TickPhase.CORE_TICK);
    }

    /**
     * 在当前阶段同步执行本 tick 已提交的全部任务。
     *
     * <p>这是"单线程正确性"的关键：任务在驱动线程上按提交顺序执行，
     * 异常被记录到 contract 而非逃逸破坏 tick 循环。
     *
     * @return 执行的任务数
     */
    public int runPendingTasks(TickContract contract) {
        requireCurrent(contract);
        int executed = 0;
        for (TickContract.TickTask task : contract.tasks()) {
            if (task.isFinished()) {
                continue;
            }
            // 归因放在执行两侧，而不是塞进 TickTask.run()：任务不知道自己
            // 属于谁，也不该知道 —— 归属由类加载器决定，平台在这里读一次即可。
            String owner = ModOwners.idOf(task.workType());
            long start = System.nanoTime();
            watchdog.enter(owner, task.id());
            try {
                task.run(contract);
            } finally {
                watchdog.exit();
                attribution.record(owner, System.nanoTime() - start);
            }
            executed++;
        }
        return executed;
    }

    /**
     * 推进到 ASYNC_COMPLETION 并等待异步任务收敛。
     *
     * @param timeoutMs 等待超时
     * @return 是否在超时前全部完成
     */
    public boolean advanceToAsyncCompletion(TickContract contract, long timeoutMs) {
        requireCurrent(contract);
        if (contract.phase().ordinal() >= TickContract.TickPhase.ASYNC_COMPLETION.ordinal()) {
            // 已越过 —— 只等任务收敛，不重复触发监听器
            return contract.awaitAllTasks(timeoutMs);
        }
        contract.advanceTo(TickContract.TickPhase.ASYNC_COMPLETION);
        boolean drained = contract.awaitAllTasks(timeoutMs);
        fire(asyncCompletionListeners, contract, TickContract.TickPhase.ASYNC_COMPLETION);
        return drained;
    }

    // ── 阶段回调注册 ───────────────────────────────────────────────────────

    public void onPreTick(StageListener l) {
        preTickListeners.add(l);
    }

    public void onSchedule(StageListener l) {
        scheduleListeners.add(l);
    }

    public void onCoreTick(StageListener l) {
        coreTickListeners.add(l);
    }

    public void onAsyncCompletion(StageListener l) {
        asyncCompletionListeners.add(l);
    }

    public void onPostTick(StageListener l) {
        postTickListeners.add(l);
    }

    public void onTickEnd(StageListener l) {
        tickEndListeners.add(l);
    }

    private void fire(List<StageListener> listeners, TickContract contract,
                      TickContract.TickPhase phase) {
        for (StageListener l : listeners) {
            try {
                l.onStage(contract, phase);
            } catch (Throwable t) {
                // 阶段监听器异常不得破坏 tick 循环 —— 记录并继续
                contract.fail(t);
            }
        }
    }

    // ── 任务提交 ───────────────────────────────────────────────────────────

    /** 向当前 tick 提交任务。必须在 beginTick 与 endTick 之间调用。 */
    public TickContract.TickTask submit(String id, Runnable work,
                                         TickContract.TaskPriority priority) {
        TickContract c = currentContract.get();
        if (c == null) {
            throw new IllegalStateException("当前不在 tick 内：需先调用 beginTick()");
        }
        return c.submitTask(id, work, priority);
    }

    /**
     * 下一 tick 执行。
     */
    public void runOnNextTick(Runnable work) {
        synchronized (nextTickQueue) {
            nextTickQueue.add(new ScheduledTickWork(work, 1));
        }
    }

    /**
     * N 个 tick 之后执行。
     */
    public void runAfterTicks(int ticks, Runnable work) {
        int delay = Math.max(1, ticks);
        synchronized (nextTickQueue) {
            if (delay == 1) {
                nextTickQueue.add(new ScheduledTickWork(work, 1));
            } else {
                delayedQueue.add(new ScheduledTickWork(work, delay));
            }
        }
    }

    /**
     * 注册「每 tick 执行一次」的常驻工作。
     *
     * <p>与 {@link #runOnNextTick} 的区别：那个只跑一次，本方法一直跑
     * 直到 {@link EveryTickWork#cancel()} 或引擎关闭。
     *
     * @return 句柄，用于取消
     */
    public EveryTickWork runEveryTick(Runnable work) {
        EveryTickWork handle = new EveryTickWork(work);
        if (!closed.get()) {
            everyTickWork.add(handle);
        }
        return handle;
    }

    private void runEveryTickWork() {
        if (everyTickWork.isEmpty()) {
            return;
        }
        for (EveryTickWork work : everyTickWork) {
            if (work.cancelled) {
                everyTickWork.remove(work);
                continue;
            }
            String owner = ModOwners.idOf(work.work.getClass());
            long start = System.nanoTime();
            watchdog.enter(owner, "every-tick");
            try {
                work.work.run();
            } catch (Throwable ignored) {
                // 常驻工作异常不得终止后续 tick；归因数据仍然记下来
            } finally {
                watchdog.exit();
                attribution.record(owner, System.nanoTime() - start);
            }
        }
    }

    /**
     * 「每 tick」工作句柄。
     */
    public final class EveryTickWork {
        private final Runnable work;
        private volatile boolean cancelled;

        EveryTickWork(Runnable work) {
            this.work = java.util.Objects.requireNonNull(work, "work");
        }

        /** 是否仍会执行。 */
        public boolean isActive() {
            return !cancelled && !closed.get();
        }

        /** 取消。 */
        public boolean cancel() {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            everyTickWork.remove(this);
            return true;
        }
    }

    /**
     * 在 tick 开始时执行到期的排队工作。由 {@link #beginTick()} 调用。
     */
    private void drainElapsedDelayedWork() {
        List<Runnable> due = new ArrayList<>();
        synchronized (nextTickQueue) {
            // nextTickQueue 全部到期
            while (!nextTickQueue.isEmpty()) {
                ScheduledTickWork w = nextTickQueue.poll();
                if (w != null) {
                    w.remainingTicks--;
                    if (w.remainingTicks <= 0) {
                        due.add(w.work);
                    } else {
                        nextTickQueue.add(w);
                    }
                }
            }
            // delayedQueue 递减
            var it = delayedQueue.iterator();
            while (it.hasNext()) {
                ScheduledTickWork w = it.next();
                w.remainingTicks--;
                if (w.remainingTicks <= 0) {
                    due.add(w.work);
                    it.remove();
                }
            }
        }
        for (Runnable r : due) {
            // 跨 tick 排队的工作同样归因：它由 Mod 提交，却不在任何
            // TickTask 里，漏掉这一处会让"某些 Mod 的耗时凭空消失"。
            String owner = ModOwners.idOf(r.getClass());
            long start = System.nanoTime();
            watchdog.enter(owner, "next-tick");
            try {
                r.run();
            } catch (Throwable ignored) {
                // 排队工作异常不破坏 tick
            } finally {
                watchdog.exit();
                attribution.record(owner, System.nanoTime() - start);
            }
        }
    }

    // ── 指标与诊断 ─────────────────────────────────────────────────────────

    private void record(TickContract.TickMetrics m) {
        synchronized (this) {
            metricRing[metricWrite] = m;
            metricWrite = (metricWrite + 1) % METRICS_HISTORY;
            if (metricFilled < METRICS_HISTORY) {
                metricFilled++;
            }
        }
        totalTickDurationNanos.addAndGet(m.durationNanos());
        maxTickDurationMs.updateAndGet(prev -> Math.max(prev, m.durationMs()));
        if (m.missedDeadline()) {
            missedDeadlineCount.incrementAndGet();
        }
        if (m.error() != null) {
            failedTickCount.incrementAndGet();
        }
        if (performance != null) {
            performance.recordTick(m.startTimeNanos(),
                    m.startTimeNanos() + m.durationNanos());
        }
    }

    public long currentTick() {
        return tickCounter.get();
    }

    public TickContract currentContract() {
        return currentContract.get();
    }

    public long tickPeriodMs() {
        return tickPeriodMs;
    }

    /** 平均 tick 耗时（毫秒）。 */
    public double averageTickMs() {
        long count = tickCounter.get();
        return count == 0 ? 0.0 : totalTickDurationNanos.get() / (double) count / 1_000_000.0;
    }

    /** 实际 TPS（基于 tick 计数与真实墙钟耗时）。 */
    public double currentTps() {
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        if (elapsedNanos <= 0) {
            return 0.0;
        }
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        return tickCounter.get() / elapsedSeconds;
    }

    public long missedDeadlineCount() {
        return missedDeadlineCount.get();
    }

    public long failedTickCount() {
        return failedTickCount.get();
    }

    public long maxTickDurationMs() {
        return maxTickDurationMs.get();
    }

    /** 最近指标快照（按时间正序）。 */
    public List<TickContract.TickMetrics> recentMetrics() {
        synchronized (this) {
            if (metricFilled == 0) {
                return List.of();
            }
            List<TickContract.TickMetrics> copy = new ArrayList<>(metricFilled);
            int start = metricFilled == METRICS_HISTORY ? metricWrite : 0;
            for (int i = 0; i < metricFilled; i++) {
                copy.add(metricRing[(start + i) % METRICS_HISTORY]);
            }
            return List.copyOf(copy);
        }
    }

    /** 性能采样器；未接入时为 {@code null}。 */
    public PerformanceMonitor performance() {
        return performance;
    }

    /** 已注册的阶段监听器总数（诊断 / 测试用）。 */
    public int listenerCount() {
        return preTickListeners.size() + scheduleListeners.size()
                + coreTickListeners.size() + asyncCompletionListeners.size()
                + postTickListeners.size() + tickEndListeners.size()
                + observers.size() + everyTickWork.size();
    }

    /** 人类可读的诊断摘要。 */
    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("TickEngine[").append(id).append("]\n");
        sb.append("  tickCount=").append(tickCounter.get());
        sb.append("  period=").append(tickPeriodMs).append("ms");
        sb.append("  avg=").append(String.format("%.2f", averageTickMs())).append("ms");
        sb.append("  max=").append(maxTickDurationMs.get()).append("ms\n");
        sb.append("  missedDeadline=").append(missedDeadlineCount.get());
        sb.append("  failed=").append(failedTickCount.get()).append('\n');
        synchronized (nextTickQueue) {
            sb.append("  queuedNext=").append(nextTickQueue.size());
            sb.append("  queuedDelayed=").append(delayedQueue.size()).append('\n');
        }
        sb.append("  ").append(watchdog.describe()).append('\n');
        sb.append(attribution.render(6));
        return sb.toString();
    }

    /** 按 Mod 归因的 tick 成本；供诊断面板与报告读取。 */
    public ModAttribution attribution() {
        return attribution;
    }

    /** 停滞看门狗；未接入 tick 时它不会启动。 */
    public TickWatchdog watchdog() {
        return watchdog;
    }

    private void requireCurrent(TickContract contract) {
        if (contract == null) {
            throw new IllegalArgumentException("contract 不能为 null");
        }
        if (contract != currentContract.get()) {
            throw new IllegalStateException("contract 不属于当前 tick");
        }
    }

    /** 阶段回调。 */
    @FunctionalInterface
    public interface StageListener {
        void onStage(TickContract contract, TickContract.TickPhase phase);
    }

    /** 跨 tick 排队工作。 */
    private static final class ScheduledTickWork {
        final Runnable work;
        int remainingTicks;

        ScheduledTickWork(Runnable work, int remainingTicks) {
            this.work = work;
            this.remainingTicks = remainingTicks;
        }
    }
}