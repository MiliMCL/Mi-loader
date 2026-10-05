package org.loader.api.world;

/**
 * 已注册方块的句柄。
 *
 * <p>Mod 无法持有 Minecraft 的 {@code Block} 对象（ClassLoader 隔离 +
 * ABI 零游戏依赖），因此注册后拿到的是这个不可变标识符。所有针对该方块的
 * 操作都以此传递。
 *
 * <p>句柄按值语义：同一 {@link Registry#block(BlockSpec) 注册}的返回实例
 * 在生命周期内稳定，可安全用作 Map 键。
 */
public final class BlockHandle {

    private final String modId;
    private final String path;
    private final int numericId;

    BlockHandle(String modId, String path, int numericId) {
        this.modId = modId;
        this.path = path;
        this.numericId = numericId;
    }

    /** 注册该方块的 Mod ID。 */
    public String modId() {
        return modId;
    }

    /** 命名空间内的路径，如 {@code crops/amaranth}。 */
    public String path() {
        return path;
    }

    /** 完整资源标识，如 {@code stardewvalley:crops/amaranth}。 */
    public String id() {
        return modId + ":" + path;
    }

    /**
     * 游戏内分配的数值 ID。
     * <p>注意：这不是稳定的持久化标识，存档应使用 {@link #id()}。
     */
    public int numericId() {
        return numericId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockHandle other)) {
            return false;
        }
        return modId.equals(other.modId) && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return 31 * modId.hashCode() + path.hashCode();
    }

    @Override
    public String toString() {
        return "BlockHandle(" + id() + ")";
    }
}
