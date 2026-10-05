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
        this.modId = modId;
        this.path = path;
        this.numericId = numericId;
        this.spec = spec;
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
