package org.loader.api.registry;

import org.loader.api.world.BlockHandle;

import java.util.Collection;
import java.util.Optional;

/**
 * Minecraft 注册表的 Mod 侧入口。
 *
 * <p>通过 {@code ModContext.registry()} 取得。注册在 Mod 初始化阶段完成，
 * 在此之前游戏世界尚未就绪。
 *
 * <p>命名空间由平台自动取自 Mod ID，因此 {@link BlockSpec#path()} 中不应
 * 包含冒号。
 */
public interface MinecraftRegistry {

    /**
     * 注册一个方块。
     *
     * @param spec 方块规格
     * @return 方块句柄
     * @throws IllegalStateException 重复注册同一路径，或注册时机已过
     */
    BlockHandle block(BlockSpec spec);

    /**
     * 注册物品。
     *
     * @param spec 物品规格
     * @return 物品句柄
     */
    ItemHandle item(ItemSpec spec);

    /**
     * 取已注册的方块。
     *
     * @param path 命名空间内路径，如 {@code crops/amaranth}
     */
    Optional<BlockHandle> findBlock(String path);

    /** 取已注册的物品。 */
    Optional<ItemHandle> findItem(String path);

    /** 列出该 Mod 已注册的全部方块。 */
    Collection<BlockHandle> blocks();

    /** 列出该 Mod 已注册的全部物品。 */
    Collection<ItemHandle> items();

    /**
     * 是否仍处于可注册阶段。
     * <p>Mod 在 {@code initialize} 中应完成全部注册；此方法返回 false 时
     * 任何注册调用都会失败。
     */
    boolean isOpen();
}
