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
        this.modId = requireValid(modId, path);
        this.path = path;
        this.numericId = numericId;
    }

    /**
     * 由平台实现调用，创建已注册方块的句柄。
     *
     * <p><b>为什么需要这个工厂</b>：句柄的构造器刻意设为包私有 ——
     * Mod 拿到句柄后<b>不应</b>能凭空造出一个指向不存在方块的句柄，
     * 那会让 {@link #numericId()} 与游戏真实状态脱节。
     * 但平台实现位于另一个包（{@code org.loader.runtime.minecraft}），
     * 没有公开出口就根本无法创建句柄，契约在此处是断的。
     *
     * <p>本工厂是那条唯一的出口：Mod 侧无法调用它（不导出给 Mod 的
     * 构造路径），只有持有真实注册结果的平台绑定层能调用。
     *
     * @param modId     注册该方块的 Mod ID（非空非空串）
     * @param path      命名空间内路径（非空，不含 {@code ':'}）
     * @param numericId 游戏分配的数值 ID
     * @throws IllegalArgumentException modId 或 path 不合法
     */
    public static BlockHandle create(String modId, String path, int numericId) {
        return new BlockHandle(modId, path, numericId);
    }

    private static String requireValid(String modId, String path) {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("block path must not be blank");
        }
        if (path.indexOf(':') >= 0) {
            // 命名空间由平台从 Mod ID 推导；path 里再带一个冒号会让
            // "modId + ':' + path" 变成三段，无法解析。
            throw new IllegalArgumentException(
                    "block path must not contain ':' (namespace comes from the mod id): "
                            + path);
        }
        return modId;
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
