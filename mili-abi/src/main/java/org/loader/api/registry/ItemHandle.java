package org.loader.api.registry;

import java.util.Map;
import java.util.Optional;

/**
 * 已注册物品的句柄。
 *
 * <p>与 {@link org.loader.api.world.BlockHandle} 同构：不可变、可作 Map 键。
 */
public final class ItemHandle {

    private final String modId;
    private final String path;
    private final int numericId;
    private final ItemSpec spec;

    ItemHandle(String modId, String path, int numericId, ItemSpec spec) {
        this.modId = requireValidModId(modId, path);
        this.path = path;
        this.numericId = numericId;
        this.spec = spec;
    }

    /**
     * 由平台实现调用，创建已注册物品的句柄。
     *
     * <p>与 {@link org.loader.api.world.BlockHandle#create} 同理：构造器包私有
     * 以防 Mod 凭空造句柄，但平台绑定层在另一个包里，必须有公开出口，
     * 否则契约无法实现。
     *
     * @param modId     注册该物品的 Mod ID（非空非空串）
     * @param path      命名空间内路径（非空，不含 {@code ':'}）
     * @param numericId 游戏分配的数值 ID
     * @param spec      注册时使用的规格
     * @throws IllegalArgumentException modId 或 path 不合法
     */
    public static ItemHandle create(String modId, String path, int numericId, ItemSpec spec) {
        return new ItemHandle(modId, path, numericId, spec);
    }

    private static String requireValidModId(String modId, String path) {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("item path must not be blank");
        }
        if (path.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "item path must not contain ':' (namespace comes from the mod id): "
                            + path);
        }
        return modId;
    }

    public String modId() {
        return modId;
    }

    public String path() {
        return path;
    }

    /** 完整资源标识，如 {@code stardewvalley:seeds/amaranth}。 */
    public String id() {
        return modId + ":" + path;
    }

    /**
     * 游戏内分配的数值 ID。
     * <p>不是稳定标识，存档请使用 {@link #id()}。
     */
    public int numericId() {
        return numericId;
    }

    /** 注册时使用的规格。 */
    public ItemSpec spec() {
        return spec;
    }

    /**
     * 若该物品是某个方块的物品形式，返回对应方块。
     */
    public Optional<org.loader.api.world.BlockHandle> asBlock() {
        return spec.blockItem().map(b -> b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ItemHandle other)) {
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
        return "ItemHandle(" + id() + ")";
    }
}
