package org.loader.api.registry;

import org.loader.api.world.BlockHandle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 物品注册规格。
 *
 * <p>与 {@link BlockSpec} 同构：不可变 Builder，只含数据与行为引用。
 */
public final class ItemSpec {

    private final String path;
    private final int maxStackSize;
    private final int maxDamage;
    private final boolean hasSubtypes;
    private final BlockHandle blockItem;
    private final String modelTemplate;
    private final Map<String, String> textures;
    private final String group;
    private final int rarity;
    private final boolean fireResistant;
    private final boolean needsGlint;

    private ItemSpec(Builder b) {
        this.path = b.path;
        this.maxStackSize = b.maxStackSize;
        this.maxDamage = b.maxDamage;
        this.hasSubtypes = b.hasSubtypes;
        this.blockItem = b.blockItem;
        this.modelTemplate = b.modelTemplate;
        this.textures = Map.copyOf(b.textures);
        this.group = b.group;
        this.rarity = b.rarity;
        this.fireResistant = b.fireResistant;
        this.needsGlint = b.needsGlint;
    }

    public static Builder builder(String path) {
        return new Builder(path);
    }

    public String path() {
        return path;
    }

    /** 最大堆叠数；不可堆叠物品为 1。 */
    public int maxStackSize() {
        return maxStackSize;
    }

    /** 最大耐久；0 表示无耐久。 */
    public int maxDamage() {
        return maxDamage;
    }

    /** 是否通过 metadata 区分子类型（如不同品质的工具）。 */
    public boolean hasSubtypes() {
        return hasSubtypes;
    }

    /**
     * 若这是某个方块的物品形式，返回该方块句柄。
     * <p>方块物品必须走这个入口，否则游戏不会正确渲染与放置。
     */
    public Optional<BlockHandle> blockItem() {
        return Optional.ofNullable(blockItem);
    }

    /** 模型模板路径，如 {@code item/generated}。 */
    public String modelTemplate() {
        return modelTemplate;
    }

    public Map<String, String> textures() {
        return textures;
    }

    /** 创造模式分类组标识。 */
    public String group() {
        return group;
    }

    /** 稀有度：0 普通 / 1 少见 / 2 罕见 / 3 史诗。 */
    public int rarity() {
        return rarity;
    }

    public boolean isFireResistant() {
        return fireResistant;
    }

    /** 是否带附魔光效。 */
    public boolean needsGlint() {
        return needsGlint;
    }

    /** 物品规格构造器。 */
    public static final class Builder {
        private final String path;
        private int maxStackSize = 64;
        private int maxDamage;
        private boolean hasSubtypes;
        private BlockHandle blockItem;
        private String modelTemplate = "item/generated";
        private final Map<String, String> textures = new LinkedHashMap<>();
        private String group = "misc";
        private int rarity;
        private boolean fireResistant;
        private boolean needsGlint;

        private Builder(String path) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("item path must not be blank");
            }
            if (path.contains(" ")) {
                throw new IllegalArgumentException(
                        "item path must not contain spaces: " + path);
            }
            this.path = path;
        }

        public Builder maxStackSize(int maxStackSize) {
            if (maxStackSize < 1 || maxStackSize > 99) {
                throw new IllegalArgumentException(
                        "maxStackSize must be 1..99, got " + maxStackSize);
            }
            this.maxStackSize = maxStackSize;
            if (maxStackSize == 1) {
                this.maxDamage = Math.max(this.maxDamage, 1);
            }
            return this;
        }

        public Builder maxDamage(int maxDamage) {
            this.maxDamage = maxDamage;
            if (maxDamage > 0) {
                this.maxStackSize = 1;
            }
            return this;
        }

        public Builder hasSubtypes() {
            this.hasSubtypes = true;
            return this;
        }

        /** 声明这是某方块的物品形式。 */
        public Builder blockItem(BlockHandle block) {
            this.blockItem = block;
            return this;
        }

        public Builder modelTemplate(String modelTemplate) {
            this.modelTemplate = modelTemplate;
            return this;
        }

        public Builder texture(String key, String spec) {
            this.textures.put(key, spec);
            return this;
        }

        /** 绑定单纹理（多数物品只需 layer0）。 */
        public Builder texture(String spec) {
            this.textures.put("layer0", spec);
            return this;
        }

        public Builder group(String group) {
            this.group = group;
            return this;
        }

        public Builder rarity(int rarity) {
            if (rarity < 0 || rarity > 3) {
                throw new IllegalArgumentException("rarity must be 0..3, got " + rarity);
            }
            this.rarity = rarity;
            return this;
        }

        public Builder fireResistant() {
            this.fireResistant = true;
            return this;
        }

        public Builder glint() {
            this.needsGlint = true;
            return this;
        }

        public ItemSpec build() {
            return new ItemSpec(this);
        }
    }
}
