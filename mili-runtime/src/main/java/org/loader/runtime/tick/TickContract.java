package org.loader.runtime.tick;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tick 执行契约 —— Mili TickEngine 的<b>可实例化</b>协议。
 *
 * <p><b>为什么必须是实例</b>：TickContract 不是数据结构，而是一次 tick 的
 * <i>执行信封</i>。它必须承载：谁在推进、推进到哪个阶段、提交了哪些任务、
 * 何时算完成、失败了什么、是否被取消。单靠 record 无法表达"状态推进"这一
 * 核心语义，因此这里是可变对象 + 显式阶段机。
 *
 * <p><b>责任划分（明确到类）</b>：
 * <pre>
 *   创建者          : TickEngine / MinecraftTickSource（每 tick 新建一个）
 *   推进 Stage      : TickEngine（唯一的 advance() 调用方）
 *   提交 Task       : 任意线程（Mod / Scheduler），线程安全
 *   等待 Task 完成  : TickEngine 在阶段屏障处 awaitAllTasks()
 *   处理异常        : TickEngine 捕获 → contract.fail() → 决定传播或降级
 *   完成 Tick       : TickEngine.complete()
 *   deadline 责任   : TickEngine 巡检 deadline → isPastDeadline()
 *   cancellation    : 任意线程 cancel() → 已提交任务被取消，阶段推进中止
 * </pre>
 *
 * <p><b>实例是单线程专属的</b>：一个 TickContract 只属于一次 tick，只应由
 * 驱动它的那个线程推进阶段。任务提交是线程安全的。
 */
public final class TickContract {

    /** 默认目标 TPS。 */
    public static final int DEFAULT_TPS = 20;
    /** 默认 tick 周期（毫秒）。 */
    public static final long DEFAULT_TICK_PERIOD_MS = 1000L / DEFAULT_TPS;

    private static final AtomicLong TICK_ID_SEQUENCE = new AtomicLong(0);

    private final long tickId;
    private final long startTimeNanos;
    private final long deadlineNanos;
    private final String worldName;

    private final AtomicReference<TickPhase> phase = new AtomicReference<>(TickPhase.TICK_START);
    private final List<TickTask> tasks = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    private TickContract(long tickId, long startTimeNanos, long deadlineNanos, String worldName) {
        this.tickId = tickId;
        this.startTimeNanos = startTimeNanos;
        this.deadlineNanos = deadlineNanos;
        this.worldName = worldName != null ? worldName : "unknown";
        this.phase.set(TickPhase.TICK_START);
    }

    /** 用下一个全局递增 tickId 创建（推荐）。 */
    public static TickContract create(long tickPeriodMs) {
        return create(tickPeriodMs, null);
    }

    public static TickContract create(long tickPeriodMs, String worldName) {
        long id = TICK_ID_SEQUENCE.incrementAndGet();
        long now = System.nanoTime();
        return new TickContract(id, now, now + tickPeriodMs * 1_000_000L, worldName);
    }

    /** 用于测试：以固定 tickId 构造。 */
    public static TickContract createWithId(long tickId, long tickPeriodMs) {
        long now = System.nanoTime();
        return new TickContract(tickId, now, now + tickPeriodMs * 1_000_000L, null);
    }

    // ── 阶段推进（仅驱动线程调用） ─────────────────────────────────────────

    /**
     * 推进到下一阶段。
     *
     * @return 新的阶段
     * @throws IllegalStateException 若已取消/完成，或阶段顺序非法
     */
    public TickPhase advance() {
        if (cancelled.get()) {
            throw new TickCancelledException(tickId, "tick 已取消，无法推进阶段");
        }
        if (completed.get()) {
            throw new IllegalStateException("tick " + tickId + " 已完成，无法推进阶段");
        }
        TickPhase current = phase.get();
        TickPhase next = current.next();
        if (next == null) {
            throw new IllegalStateException(
                    "tick " + tickId + " 已处于终阶段 " + current + "，无法继续推进");
        }
        phase.set(next);
        return next;
    }

    /**
     * 推进直到指定阶段（含）。
     *
     * @throws IllegalStateException 若目标阶段在当前阶段之前
     */
    public TickPhase advanceTo(TickPhase target) {
        if (target.ordinal() < phase.get().ordinal()) {
            throw new IllegalStateException(
                    "不能回退阶段: 当前 " + phase.get() + "，目标 " + target);
        }
        while (phase.get() != target) {
            advance();
        }
        return phase.get();
    }

    /** 当前阶段。 */
    public TickPhase phase() {
        return phase.get();
    }

    public long tickId() {
        return tickId;
    }

    public long startTimeNanos() {
        return startTimeNanos;
    }

    /** deadline 绝对时间（纳秒）。 */
    public long deadlineNanos() {
        return deadlineNanos;
    }

    public String worldName() {
        return worldName;
    }

    // ── 任务提交（线程安全） ────────────────────────────────────────────────

    /**
     * 提交一个 tick 内任务。
     *
     * <p>任务必须在 tick 完成前结束，否则 {@link #awaitAllTasks(long)} 会超时。
     *
     * @throws IllegalStateException 若 tick 已完成/取消，或已进入末端阶段
     */
    public TickTask submitTask(String id, Runnable work, TaskPriority priority) {
        if (completed.get()) {
            throw new IllegalStateException("tick " + tickId + " 已完成，拒绝提交任务");
        }
        if (cancelled.get()) {
            throw new TickCancelledException(tickId, "tick 已取消，拒绝提交任务");
        }
        TickPhase p = phase.get();
        if (p == TickPhase.POST_TICK || p == TickPhase.TICK_END) {
            throw new IllegalStateException(
                    "tick " + tickId + " 已进入 " + p + "，拒绝提交任务");
        }
        TickTask task = new TickTask(id, work, priority != null ? priority : TaskPriority.NORMAL);
        tasks.add(task);
        return task;
    }

    /** 已提交任务（快照）。 */
    public List<TickTask> tasks() {
        return Collections.unmodifiableList(new ArrayList<>(tasks));
    }

    public int taskCount() {
        return tasks.size();
    }

    /** 尚未结束的任务数。 */
    public int pendingTaskCount() {
        return (int) tasks.stream().filter(t -> !t.isFinished()).count();
    }

    /**
     * 在阶段屏障处等待所有任务结束。
     *
     * @param timeoutMs 最长等待毫秒
     * @return 若全部完成返回 true；超时返回 false
     */
    public boolean awaitAllTasks(long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            if (pendingTaskCount() == 0) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(0, 200_000); // 0.2ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    // ── 异常 / 取消 / 完成 ──────────────────────────────────────────────────

    /** 记录首个异常；后续异常作为 suppressed 附加。 */
    public void fail(Throwable t) {
        if (t == null) {
            return;
        }
        Throwable prev = error.get();
        if (prev == null) {
            error.compareAndSet(null, t);
            prev = error.get();
        }
        if (prev != null && prev != t) {
            prev.addSuppressed(t);
        }
    }

    public Throwable error() {
        return error.get();
    }

    public boolean hasError() {
        return error.get() != null;
    }

    /** 取消 tick。已提交但未开始的任务被标记取消。 */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            tasks.forEach(TickTask::requestCancel);
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 完成 tick。
     *
     * @return 完成时刻的指标快照
     */
    public TickMetrics complete() {
        completed.set(true);
        long now = System.nanoTime();
        int done = (int) tasks.stream().filter(TickTask::isFinished).count();
        boolean missed = now > deadlineNanos;
        return new TickMetrics(
                tickId,
                startTimeNanos,
                now - startTimeNanos,
                phase.get(),
                tasks.size(),
                done,
                missed,
                error.get());
    }

    public boolean isCompleted() {
        return completed.get();
    }

    /** 已耗时（毫秒）。 */
    public long elapsedMs() {
        return (System.nanoTime() - startTimeNanos) / 1_000_000L;
    }

    /** 是否已超期。 */
    public boolean isPastDeadline() {
        return System.nanoTime() > deadlineNanos;
    }

    /** 剩余预算（毫秒，不为负）。 */
    public long remainingMs() {
        return Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }

    // ── 嵌套类型 ───────────────────────────────────────────────────────────

    /** Tick 生命周期阶段（严格有序）。 */
    public enum TickPhase {
        TICK_START,
        PRE_TICK,
        SCHEDULE,
        CORE_TICK,
        ASYNC_COMPLETION,
        POST_TICK,
        TICK_END;

        /** 下一阶段；已处于最后阶段返回 null。 */
        public TickPhase next() {
            int i = ordinal() + 1;
            return i < values().length ? values()[i] : null;
        }

        /** 是否为终阶段。 */
        public boolean isTerminal() {
            return this == TICK_END;
        }
    }

    /** 任务优先级。 */
    public enum TaskPriority {
        HIGH(0),
        NORMAL(1),
        LOW(2);

        private final int value;

        TaskPriority(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }

    /** 任务状态机。 */
    public enum TaskState {
        CREATED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        LEAKED
    }

    /**
     * 一个 tick 内任务。
     *
     * <p>由 tick 引擎在阶段屏障处执行或交由 Scheduler 执行；
     * 异常不会逃逸出 {@link #run(TickContract)}，而是记录到自身状态
     * 并传播给所属 TickContract。
     */
    public static final class TickTask {
        private final String id;
        private final Runnable work;
        private final TaskPriority priority;
        private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.CREATED);
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private final long submittedNanos = System.nanoTime();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile long startNanos;
        private volatile long endNanos;

        TickTask(String id, Runnable work, TaskPriority priority) {
            this.id = id;
            this.work = work;
            this.priority = priority;
        }

        public String id() {
            return id;
        }

        public TaskPriority priority() {
            return priority;
        }

        public TaskState state() {
            return state.get();
        }

        public Throwable failure() {
            return failure.get();
        }

        public boolean isFinished() {
            TaskState s = state.get();
            return s == TaskState.COMPLETED || s == TaskState.FAILED
                    || s == TaskState.CANCELLED || s == TaskState.LEAKED;
        }

        void requestCancel() {
            cancelRequested.set(true);
            state.compareAndSet(TaskState.CREATED, TaskState.CANCELLED);
        }

        /**
         * 执行任务。异常被捕获并记录，不逃逸。
         *
         * @param contract 所属 tick（用于传播异常），可为 null
         * @return 执行后的状态
         */
        public TaskState run(TickContract contract) {
            if (cancelRequested.get()) {
                state.set(TaskState.CANCELLED);
                return TaskState.CANCELLED;
            }
            state.set(TaskState.RUNNING);
            startNanos = System.nanoTime();
            try {
                work.run();
                state.set(TaskState.COMPLETED);
            } catch (Throwable t) {
                failure.set(t);
                state.set(TaskState.FAILED);
                if (contract != null) {
                    contract.fail(t);
                }
            } finally {
                endNanos = System.nanoTime();
            }
            return state.get();
        }

        /** 执行耗时（毫秒）；未执行返回 0。 */
        public long durationMs() {
            return (endNanos > 0 && startNanos > 0)
                    ? (endNanos - startNanos) / 1_000_000L : 0;
        }

        /** 排队耗时（毫秒）。 */
        public long queueMs() {
            long start = startNanos > 0 ? startNanos : System.nanoTime();
            return Math.max(0, (start - submittedNanos) / 1_000_000L);
        }
    }

    /** 一次 tick 的指标快照。 */
    public record TickMetrics(
            long tickId,
            long startTimeNanos,
            long durationNanos,
            TickPhase finalPhase,
            int tasksScheduled,
            int tasksCompleted,
            boolean missedDeadline,
            Throwable error
    ) {
        public long durationMs() {
            return durationNanos / 1_000_000;
        }

        public boolean metDeadline(long deadlineMs) {
            return durationMs() <= deadlineMs;
        }
    }

    /** Tick 被取消。 */
    public static final class TickCancelledException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public TickCancelledException(long tickId, String message) {
            super("tick " + tickId + ": " + message);
        }
    }
}