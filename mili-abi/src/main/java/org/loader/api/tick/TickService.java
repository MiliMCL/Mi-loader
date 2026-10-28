package org.loader.api.tick;

/**
 * Tick 服务 —— 把「下一 tick 做这件事」从 Mod 自己维护的计数器里解放出来。
 *
 * <h2>为什么需要它</h2>
 * 没有本服务时，Mod 只能订阅 {@code TickEvent} 然后自己写：
 * <pre>{@code
 * private int countdown = 0;
 * private Runnable pending;
 * public void onTick(TickEvent e) {
 *     if (pending != null && --countdown <= 0) { pending.run(); pending = null; }
 * }
 * }</pre>
 * 这段代码每个 Mod 都要写一遍，且每一份都可能写错边界（{@code --countdown <= 0}
 * 在 countdown 为 0 时会先减成 -1）。更关键的是：<b>它没有取消语义</b> ——
 * Mod 卸载时挂起的任务会继续跑一次，访问已释放的资源。
 *
 * <p>本服务提供带句柄的延迟调度，句柄在 Mod 的 Scope 停止时自动失效。
 *
 * <h2>线程约束</h2>
 * 提交可以从任意线程调用；<b>执行</b>永远在游戏主线程、且在该 tick 的
 * {@link org.loader.api.event.TickPhase#START} 之后、世界 tick 之前。
 * 回调里不要做重活。
 */
public interface TickService {

    /**
     * 当前 tick 序号（从 1 开始）。未进入 tick 循环时为 0。
     */
    long currentTick();

    /**
     * 下一 tick 执行。
     *
     * @return 可取消的句柄
     */
    TickTask runNextTick(Runnable work);

    /**
     * N 个 tick 之后执行（N ≥ 1；N = 1 等价于 {@link #runNextTick}）。
     */
    TickTask runAfterTicks(int ticks, Runnable work);

    /**
     * 每 tick 执行一次，直到句柄被取消或 Mod 停止。
     *
     * <p>与 {@code Scheduler.submitRepeating(..., 50, 50)} 的区别：
     * 那个按<b>墙钟</b>计时，本方法按<b>tick</b>计数。游戏卡顿时两者会分叉 ——
     * 「每 20 tick 检查一次」在卡顿时仍然是每 20 个逻辑 tick，
     * 而「每 1000ms」会变成每 20 tick 以上。需要与游戏节奏对齐的逻辑用本方法。
     */
    TickTask runEveryTick(Runnable work);

    /**
     * 平台是否已有 tick 源（客户端进入世界 / 服务端启动完成）。
     *
     * <p>在主菜单阶段为 {@code false}：此时提交的 tick 任务会排队等待
     * 第一个真实 tick，不会丢失。
     */
    boolean isAvailable();

    /**
     * 待执行的 tick 任务句柄。
     */
    interface TickTask {

        /** 唯一 id，便于日志定位。 */
        String taskId();

        /** 是否仍会执行（未被取消且 Mod 仍活跃）。 */
        boolean isActive();

        /**
         * 取消。
         *
         * @return 是否确实取消了（已执行完/已取消返回 false）
         */
        boolean cancel();
    }
}
