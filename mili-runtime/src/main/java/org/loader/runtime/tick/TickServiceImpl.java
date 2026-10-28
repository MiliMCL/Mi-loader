package org.loader.runtime.tick;

import org.loader.api.tick.TickService;
import org.loader.runtime.kernel.Scope;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link TickService} 的运行时实现 —— 每个 Mod 一个视图，共享同一个 {@link TickEngine}。
 *
 * <h2>为什么是「视图」而不是「服务」</h2>
 * tick 引擎是平台级的（一个进程只有一个 tick 循环），但任务归属是
 * <b>每个 Mod 各自的</b>：Mod 停止时必须只取消它自己的任务。
 * 因此本类持有 {@code scope}，每次提交都检查它是否还活着。
 *
 * <h2>提交时机不受限</h2>
 * Mod 在 {@code initialize()} 里提交（此时还没有 tick）是<b>正常且预期</b>的。
 * 任务进入引擎的跨 tick 队列，第一个真实 tick 到来时执行 —— 不会丢失，
 * 也不会因为「还没 tick」而抛异常。这一点与 {@code Scheduler} 不同：
 * 后者依赖线程池，随时可以跑；tick 任务必须等 tick。
 */
public final class TickServiceImpl implements TickService {

    private final TickEngineHolder holder;
    private final Scope scope;
    private final AtomicLong sequence = new AtomicLong();

    public TickServiceImpl(TickEngine engine, Scope scope) {
        this(holderOf(engine), scope);
    }

    public TickServiceImpl(TickEngineHolder holder, Scope scope) {
        this.holder = holder;
        this.scope = scope;
    }

    private static TickEngineHolder holderOf(TickEngine engine) {
        TickEngineHolder h = new TickEngineHolder();
        h.set(engine);
        return h;
    }

    private TickEngine engine() {
        return holder.get();
    }

    @Override
    public long currentTick() {
        TickEngine e = engine();
        return e == null ? 0L : e.currentTick();
    }

    @Override
    public TickTask runNextTick(Runnable work) {
        return schedule(1, work, false);
    }

    @Override
    public TickTask runAfterTicks(int ticks, Runnable work) {
        return schedule(Math.max(1, ticks), work, false);
    }

    @Override
    public TickTask runEveryTick(Runnable work) {
        return schedule(0, work, true);
    }

    @Override
    public boolean isAvailable() {
        TickEngine e = engine();
        return e != null && !e.isClosed() && !scope.isStopped();
    }

    private TickTask schedule(int ticks, Runnable work, boolean repeating) {
        if (work == null) {
            throw new IllegalArgumentException("work 不能为 null");
        }
        TickEngine engine = engine();
        if (engine == null || engine.isClosed() || scope.isStopped()) {
            throw new IllegalStateException(
                    "TickService 不可用："
                            + (engine == null ? "tick 引擎尚未装配（游戏还没启动）" : "引擎已关闭或 Mod 已停止")
                            + "（scope=" + scope.id() + "）");
        }
        String taskId = scope.id() + "-tick-" + sequence.incrementAndGet();
        java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        if (repeating) {
            TickEngine.EveryTickWork handle = engine.runEveryTick(() -> {
                if (!cancelled.get() && !scope.isStopped()) {
                    work.run();
                }
            });
            return new Handle(taskId, handle, cancelled);
        }

        // 包一层：执行前再检查一次 Scope 与取消标志 —— 提交与执行之间
        // 可能隔了几百个 tick，期间 Mod 可能已卸载、任务可能已取消。
        Runnable guarded = () -> {
            if (!cancelled.get() && !scope.isStopped()) {
                work.run();
            }
        };
        if (ticks <= 1) {
            engine.runOnNextTick(guarded);
        } else {
            engine.runAfterTicks(ticks, guarded);
        }
        return new Handle(taskId, null, cancelled);
    }

    /**
     * 任务句柄。
     *
     * <p>一次性任务在引擎侧没有独立的取消入口（它只是队列里的一条记录），
     * 因此取消通过「置标志 + 执行时跳过」实现 —— 队列项本身仍会到期，
     * 但工作不会执行。这比从队列里摘除更简单，也没有「摘除时刚好在跑」
     * 的竞态。
     */
    private static final class Handle implements TickTask {
        private final String taskId;
        private final TickEngine.EveryTickWork repeating;
        private final java.util.concurrent.atomic.AtomicBoolean cancelled;

        Handle(String taskId, TickEngine.EveryTickWork repeating,
               java.util.concurrent.atomic.AtomicBoolean cancelled) {
            this.taskId = taskId;
            this.repeating = repeating;
            this.cancelled = cancelled;
        }

        @Override
        public String taskId() {
            return taskId;
        }

        @Override
        public boolean isActive() {
            return !cancelled.get() && (repeating == null || repeating.isActive());
        }

        @Override
        public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return false;
            }
            if (repeating != null) {
                repeating.cancel();
            }
            return true;
        }
    }
}
