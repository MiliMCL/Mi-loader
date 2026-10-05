package org.loader.runtime.minecraft;

import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.tick.TickContract;
import org.loader.runtime.tick.TickEngine;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TickBridge —— 把 Minecraft 的<b>真实</b> tick 循环接到 Mili TickEngine。
 *
 * <p><b>与历史实现的根本区别</b>：旧版靠 10Hz 轮询 {@code level.getGameTime()}
 * "推测" tick 是否发生。那在 20 TPS 下不成立：轮询粒度与 tick 不同步、
 * 轮询线程不是主线程、单人世界下 {@code level} 为 null 时 tick 直接停摆。
 *
 * <p><b>现在的契约</b>：Minecraft 集成层在<b>真实</b> tick 入口调用
 * {@link #beginTick()} 与 {@link #endTick()}。二者必须在 Minecraft 主线程上成对调用。
 * 桥接层不猜测 tick 是否发生 —— 它只在被真实调用时推进状态机。
 *
 * <p><b>调用方</b>：{@link MinecraftTickSource} 的实现（字节码注入或官方启动路径）。
 */
public final class TickBridge implements Resource {

    private final String id;
    private final Scope owner;
    private final TickEngine engine;

    /** 驱动 tick 的线程（= Minecraft 主线程）。 */
    private volatile Thread tickThread;
    /** 当前 tick 契约；tick 之间为 null。 */
    private volatile TickContract activeContract;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TickBridge(Scope owner, TickEngine engine) {
        this.id = "minecraft-tick-bridge";
        this.owner = owner;
        this.engine = engine;
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

    public TickEngine engine() {
        return engine;
    }

    /** 当前 tick 契约；不在 tick 内返回 null。 */
    public TickContract activeContract() {
        return activeContract;
    }

    /** 当前 tick 序号。 */
    public long currentTick() {
        return engine.currentTick();
    }

    // ── 真实 tick 入口（Minecraft 主线程调用） ──────────────────────────────

    /**
     * 标记一个真实 Minecraft tick 的开始。
     *
     * <p>必须在 Minecraft 主线程、且确实进入 tick 循环时调用。
     *
     * @return 本 tick 的执行契约
     */
    public TickContract beginTick() {
        if (closed.get()) {
            throw new IllegalStateException("TickBridge 已关闭");
        }
        Thread current = Thread.currentThread();
        if (tickThread == null) {
            tickThread = current;
        } else if (tickThread != current) {
            throw new IllegalStateException(
                    "tick 必须在 Minecraft 主线程推进。当前 " + current.getName()
                            + "，tick 线程 " + tickThread.getName());
        }
        TickContract contract = engine.beginTick();
        activeContract = contract;
        return contract;
    }

    /**
     * 标记真实 tick 结束，产出指标。
     *
     * @return 本 tick 指标；不在 tick 内返回 null
     */
    public TickContract.TickMetrics endTick() {
        TickContract contract = activeContract;
        if (contract == null) {
            return null;
        }
        try {
            return engine.endTick(contract);
        } finally {
            activeContract = null;
        }
    }

    /**
     * 便捷方法：在真实 tick 内执行一段工作，自动包裹 SCHEDULE→CORE_TICK→执行。
     *
     * <p>这是 Mod 参与 tick 的推荐入口。
     */
    public void onTick(Runnable work) {
        TickContract c = activeContract;
        if (c == null || work == null) {
            return;
        }
        engine.submit("mod-task-" + c.tickId(), work, TickContract.TaskPriority.NORMAL);
    }

    // ── 诊断 ───────────────────────────────────────────────────────────────

    /** 最近一次 tick 指标。 */
    public TickContract.TickMetrics lastMetrics() {
        var all = engine.recentMetrics();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    public String diagnostics() {
        return engine.diagnostics();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            TickContract c = activeContract;
            if (c != null && !c.isCompleted()) {
                c.cancel();
            }
            activeContract = null;
        }
    }

    /** 简化 close（供 try-with-resources）。 */
    public void closeQuietly() {
        close();
    }

    /** 仅供测试：重置线程归属。 */
    void resetThreadBindingForTesting() {
        tickThread = null;
    }
}