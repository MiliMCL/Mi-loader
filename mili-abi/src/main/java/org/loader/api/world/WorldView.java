package org.loader.api.world;

import java.util.Optional;

/**
 * Mod 侧对世界的只读/受限写视图。
 *
 * <p>这是 Mod 与游戏世界之间的<b>唯一</b>通道。它刻意做得比 Minecraft 的
 * {@code Level} 小得多：只暴露方块读写、方块实体访问、少量世界信息与时间。
 * 越界的操作（实体 AI、光照、粒子）目前不在契约内。
 *
 * <p>所有实现都在平台侧，Mod 永远不接触 Minecraft 类型。
 *
 * <p><b>关于向后兼容</b>：新增能力一律以 {@code default} 方法加入，语义是
 * 「平台尚未提供该能力」。这样尚未升级的第三方实现不会因为接口新增方法而
 * 编译中断，Mod 也应当把返回值当作「可能不可用」处理
 * （时间类方法返回 -1，删除方块返回 false）。
 */
public interface WorldView {

    /**
     * 读取指定位置的方块句柄。
     *
     * @param pos 坐标
     * @return 方块句柄；空气返回空气方块句柄而非 empty
     */
    BlockHandle getBlock(BlockPos pos);

    /**
     * 写入指定位置的方块，保留原有方块状态数据。
     *
     * @param pos    坐标
     * @param block  新方块句柄
     * @return 是否成功；越界或世界未加载时返回 false
     */
    boolean setBlock(BlockPos pos, BlockHandle block);

    /**
     * 移除指定位置的方块，换成空气。
     *
     * <p><b>为什么不能靠 {@code setBlock(pos, air())} 代替</b>：
     * 移除方块要一并清掉它的<b>方块实体</b>并通知邻居重算形状，而写空气
     * 状态只换方块、不清方块实体。结果是「位置已是空气，却还挂着一个
     * 看不见也点不着的方块实体」，存档里留下孤儿数据。
     *
     * <p>典型用途：作物因地面失效而枯萎。枯萎的作物必须真正从世界里消失，
     * 否则它会继续占着坐标、继续被 tick，并留下一份无主的方块实体。
     *
     * @param pos 坐标
     * @return 是否成功；越界、该位置本来就没有方块，或世界未加载时返回 false
     */
    default boolean removeBlock(BlockPos pos) {
        // 默认实现不提供删除能力。平台绑定层必须覆写它 ——
        // 没有方块实体清理语义，退化成写空气是不正确的（见上）。
        return false;
    }

    /**
     * 读取指定位置方块的方块实体。
     *
     * @param pos 坐标
     * @return 方块实体视图；该位置无方块实体时返回 empty
     */
    Optional<BlockEntityView> getBlockEntity(BlockPos pos);

    /** 是否在可写区域内。 */
    boolean isLoaded(BlockPos pos);

    /** 世界最低可建造 Y 坐标（含）。 */
    int minY();

    /** 世界最高可建造 Y 坐标（含）。 */
    int maxY();

    /**
     * 触发一次世界保存。
     * <p>与游戏原生保存不同，本方法<b>不</b>保证立即落盘，只请求一次保存。
     */
    void requestSave();

    // ── 世界时间 ────────────────────────────────────────────────────────────

    /**
     * 一个 Minecraft 日的 tick 数：24000。
     *
     * <p>{@link #dayCount()} 与 {@link #dayTime()} 都以它为基准做换算，
     * Mod 想自己从 {@link #gameTime()} 推算时也应使用本常量，
     * 而不要在各处重复硬编码 24000。
     */
    int TICKS_PER_DAY = 24000;

    /**
     * 世界的游戏总 tick 数。
     *
     * <p>这是随世界推进单调递增的连续计数，<b>不受</b> {@code /time set} 影响。
     * 适合做「每 N tick 触发一次」这类节流，不适合做日历。
     *
     * <p>要日历请用 {@link #dayCount()}。
     *
     * @return 游戏总 tick；世界未加载时返回 -1
     */
    default long gameTime() {
        return -1L;
    }

    /**
     * 世界内已经过去的天数。
     *
     * <p>由 {@link #gameTime()} 推导，因此在存档之间稳定：重进游戏仍是同一天。
     * Mod 的季节/日历逻辑应当基于它，而不是从加载时刻起自增 ——
     * 后者会与存档时间逐渐错位。
     *
     * <p><b>与 {@code /time set} 的关系</b>：玩家改时间会改变本方法的返回值
     * （天数就是从总 tick 换算来的）。这通常是想要的行为 ——
     * 想让季节完全不受玩家改时间影响，请自行保存进 Mod 自己的持久化数据。
     *
     * <p>默认实现已按 {@link #TICKS_PER_DAY} 换算，平台实现通常<b>无需覆写</b>。
     *
     * @return 已过去的天数；世界未加载（{@link #gameTime()} 为负）时返回 -1
     */
    default long dayCount() {
        long ticks = gameTime();
        // floorDiv 而非 /：游戏时间可以是负（/time set -1000），
        // 直接用 / 会让 -1 tick 算成第 0 天而不是第 -1 天。
        return ticks < 0 ? -1L : Math.floorDiv(ticks, TICKS_PER_DAY);
    }

    /**
     * 当前的日内时刻，取值 {@code 0 .. 23999}。
     *
     * <p>一个完整的 Minecraft 日是 24000 tick。用于「现在是白天还是夜里」
     * 这类判断。玩家用 {@code /time set} 改时间会直接反映到这里。
     *
     * <p>默认实现已按 {@link #TICKS_PER_DAY} 换算，平台实现通常<b>无需覆写</b>。
     *
     * @return 日内 tick；世界未加载（{@link #gameTime()} 为负）时返回 -1
     */
    default long dayTime() {
        long ticks = gameTime();
        // floorMod 保证返回值落在 0..23999，负时间也不会给出负数时刻。
        return ticks < 0 ? -1L : Math.floorMod(ticks, TICKS_PER_DAY);
    }
}
