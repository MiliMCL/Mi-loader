package org.loader.runtime.tick;

import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.Scope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
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

    private final List<StageListener> preTickListeners = new ArrayList<>();
    private final List<StageListener> scheduleListeners = new ArrayList<>();
    private final List<StageListener> coreTickListeners = new ArrayList<>();
    private final List<StageListener> asyncCompletionListeners = new ArrayList<>();
    private final List<StageListener> postTickListeners = new ArrayList<>();
    private final List<StageListener> tickEndListeners = new ArrayList<>();

    /** 跨 tick 队列：runOnNextTick / runAfterTicks 的落点。 */
    private final Deque<ScheduledTickWork> nextTickQueue = new ArrayDeque<>();
    private final Deque<ScheduledTickWork> delayedQueue = new ArrayDeque<>();

    // ── 运行时状态 ─────────────────────────────────────────────────────────

    private final AtomicReference<TickContract> currentContract = new AtomicReference<>();
    private final AtomicLong tickCounter = new AtomicLong(0);
    private final AtomicLong missedDeadlineCount = new AtomicLong(0);
    private final AtomicLong failedTickCount = new AtomicLong(0);
    private final AtomicLong maxTickDurationMs = new AtomicLong(0);
    private volatile long totalTickDurationMs = 0;

    /** 最近若干 tick 的指标环形缓冲，供诊断使用。 */
    private final List<TickContract.TickMetrics> recentMetrics =
            Collections.synchronizedList(new ArrayList<>());
    private static final int METRICS_HISTORY = 200;

    /** 驱动线程（= Minecraft 主线程），用于校验阶段推进的线程归属。 */
    private volatile Thread driverThread;
    /** 引擎启动墙钟时间，用于计算真实 TPS。 */
    private final long startedAtNanos = System.nanoTime();

    public TickEngine(String id, Scope owner) {
        this(id, owner, TickContract.DEFAULT_TICK_PERIOD_MS);
    }

    public TickEngine(String id, Scope owner, long tickPeriodMs) {
        this.id = id;
        this.owner = owner;
        this.tickPeriodMs = tickPeriodMs;
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
            synchronized (nextTickQueue) {
                nextTickQueue.clear();
                delayedQueue.clear();
            }
            TickContract c = currentContract.get();
            if (c != null && !c.isCompleted()) {
                c.cancel();
            }
        }
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

        TickContract contract = TickContract.create(tickPeriodMs, "minecraft");
        currentContract.set(contract);
        tickCounter.incrementAndGet();

        // TICK_START → PRE_TICK
        contract.advanceTo(TickContract.TickPhase.PRE_TICK);
        fire(preTickListeners, contract, TickContract.TickPhase.PRE_TICK);

        return contract;
    }

    /**
     * 结束当前 tick 并产出指标。由 Minecraft 真实 tick 入口在 tick 尾部调用。
     *
     * @return 本 tick 指标
     */
    public TickContract.TickMetrics endTick(TickContract contract) {
        if (contract == null) {
            throw new IllegalArgumentException("contract 不能为 null");
        }
        // 推进到 TICK_END，依次执行末端阶段
        if (!contract.isCancelled() && !contract.isCompleted()) {
            if (contract.phase() == TickContract.TickPhase.CORE_TICK
                    || contract.phase().ordinal() < TickContract.TickPhase.ASYNC_COMPLETION.ordinal()) {
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
        return metrics;
    }

    /**
     * 推进到 SCHEDULE 阶段并触发调度回调。
     * Mod 在此阶段提交本 tick 的任务。
     */
    public void advanceToSchedule(TickContract contract) {
        requireCurrent(contract);
        contract.advanceTo(TickContract.TickPhase.SCHEDULE);
        fire(scheduleListeners, contract, TickContract.TickPhase.SCHEDULE);
    }

    /**
     * 推进到 CORE_TICK 阶段 —— Minecraft 真实世界/实体 tick 在此执行。
     */
    public void advanceToCoreTick(TickContract contract) {
        requireCurrent(contract);
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
            task.run(contract);
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
            try {
                r.run();
            } catch (Throwable ignored) {
                // 排队工作异常不破坏 tick
            }
        }
    }

    // ── 指标与诊断 ─────────────────────────────────────────────────────────

    private void record(TickContract.TickMetrics m) {
        synchronized (recentMetrics) {
            recentMetrics.add(m);
            while (recentMetrics.size() > METRICS_HISTORY) {
                recentMetrics.remove(0);
            }
        }
        totalTickDurationMs += m.durationMs();
        maxTickDurationMs.updateAndGet(prev -> Math.max(prev, m.durationMs()));
        if (m.missedDeadline()) {
            missedDeadlineCount.incrementAndGet();
        }
        if (m.error() != null) {
            failedTickCount.incrementAndGet();
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
        return count == 0 ? 0.0 : (double) totalTickDurationMs / count;
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
        synchronized (recentMetrics) {
            return List.copyOf(recentMetrics);
        }
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
        sb.append("  queuedNext=").append(nextTickQueue.size());
        sb.append("  queuedDelayed=").append(delayedQueue.size()).append('\n');
        return sb.toString();
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