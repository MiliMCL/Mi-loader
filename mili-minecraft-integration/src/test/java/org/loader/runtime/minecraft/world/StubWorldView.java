package org.loader.runtime.minecraft.world;

import org.loader.api.world.BlockEntityView;
import org.loader.api.world.BlockHandle;
import org.loader.api.world.BlockPos;
import org.loader.api.world.WorldView;

import java.util.Optional;

/**
 * 只实现 {@link WorldView} 必需方法的最小测试替身。
 *
 * <p><b>存在理由</b>：要验证契约的<b>默认实现</b>（如
 * {@link WorldView#dayCount()} 由 {@link #gameTime()} 推导），
 * 就需要��个「只提供一个时间值、其他方法全是契约默认值」的对象。
 * 直接用真实的游戏 Level 验不到这一层 —— 真实实现会覆写全部方法，
 * default 方法的代码根本不会执行。
 *
 * <p>因此这里刻意不覆写时间三个方法，让它们走契约默认逻辑。
 */
abstract class StubWorldView implements WorldView {

    @Override
    public BlockHandle getBlock(BlockPos pos) {
        return null;
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockHandle block) {
        return false;
    }

    @Override
    public Optional<BlockEntityView> getBlockEntity(BlockPos pos) {
        return Optional.empty();
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return false;
    }

    @Override
    public int minY() {
        return -64;
    }

    @Override
    public int maxY() {
        return 320;
    }

    @Override
    public void requestSave() {
        // 测试替身不需要保存
    }
}
