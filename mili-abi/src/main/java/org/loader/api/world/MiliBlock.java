package org.loader.api.world;

import java.util.Random;

/**
 * Mod 侧实现的方块行为。
 *
 * <p><b>这是「行为接口 + 委托」架构的核心。</b> Fabric Mod 可以
 * {@code extends Block} 并覆写任意方法；Mili 不允许这种做法（ClassLoader
 * 隔离 + 零字节码重写 + 无 Mixin），因此行为通过本接口声明，由平台为每个
 * 注册的方块创建一个 Minecraft 方块实例并把对应方法转调到这里。
 *
 * <p>所有钩子都是默认方法 —— 只实现需要的行为即可。平台保证：
 * <ul>
 *   <li>回调不会在主线程之外触发（除非方法名带 Async）；</li>
 *   <li>回调抛出的异常会被平台捕获并记录，不会中断游戏主循环；</li>
 *   <li>实现实例由 Mod 持有，平台不复制也不缓存状态。</li>
 * </ul>
 */
public interface MiliBlock {

    // ── 身份 ───────────────────────────────────────────────────────────────

    /**
     * 创建行为实例时调用，可用于初始化状态。
     * <p>等价于构造后的初始化钩子，比在字段初始化里做副作用更安全。
     */
    default void onRegister(BlockHandle self) {
    }

    // ── 生命周期钩子 ───────────────────────────────────────────────────────

    /**
     * 每游戏 tick 调用（20 次/秒）。
     * <p>世界未加载或方块被卸载时不会触发。实现应保持轻量：耗时工作交给
     * {@code ctx.scheduler()}。
     */
    default void onTick(WorldView world, BlockPos pos, BlockEntityView blockEntity) {
    }

    /** 相邻方块状态变化时调用。 */
    default void onNeighborChanged(WorldView world, BlockPos pos, BlockHandle from) {
    }

    /** 方块被放置时调用。 */
    default void onPlaced(WorldView world, BlockPos pos) {
    }

    /** 方块即将被破坏时调用；返回 true 表示阻止破坏。 */
    default boolean onBreak(WorldView world, BlockPos pos) {
        return false;
    }

    /** 方块被破坏后调用；可用于掉落处理。 */
    default void onDestroyed(WorldView world, BlockPos pos) {
    }

    // ── 交互钩子 ───────────────────────────────────────────────────────────

    /**
     * 玩家右键方块时调用。
     *
     * @return true 表示已消费该次交互（阻止默认行为）
     */
    default boolean onUse(WorldView world, BlockPos pos, Object player) {
        return false;
    }

    /**
     * 玩家破坏方块前调用（早于 {@link #onBreak}）。
     *
     * @return true 表示完全阻止破坏
     */
    default boolean onAttack(WorldView world, BlockPos pos, Object player) {
        return false;
    }

    // ── 视觉钩子 ───────────────────────────────────────────────────────────

    /**
     * 破坏进度 0..1；用于渲染裂纹贴图。
     */
    default float destroyProgress(WorldView world, BlockPos pos) {
        return 0.0f;
    }

    /**
     * 随机刻（作物生长等）。行为与 tick 相同，但由游戏按随机频率触发。
     */
    default void onRandomTick(WorldView world, BlockPos pos, Random random) {
    }

    /**
     * 方块被随机更新（火焰蔓延、作物生长等）时调用。
     */
    default void onRandomUpdate(WorldView world, BlockPos pos, Random random) {
    }
}
