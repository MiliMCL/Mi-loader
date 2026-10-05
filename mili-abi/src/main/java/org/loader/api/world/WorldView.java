package org.loader.api.world;

import java.util.Optional;

/**
 * Mod 侧对世界的只读/受限写视图。
 *
 * <p>这是 Mod 与游戏世界之间的<b>唯一</b>通道。它刻意做得比 Minecraft 的
 * {@code Level} 小得多：只暴露方块读写、方块实体访问与少量世界信息。
 * 越界的操作（实体 AI、光照、粒子）目前不在契约内。
 *
 * <p>所有实现都在平台侧，Mod 永远不接触 Minecraft 类型。
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
}
