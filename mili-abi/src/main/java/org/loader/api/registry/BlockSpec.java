package org.loader.api.registry;

import org.loader.api.world.MiliBlock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 方块注册规格 —— 描述一个待注册方块的全部静态属性与行为。
 *
 * <p>用不可变 Builder 构造。规格本身只承载<b>数据</b>与<b>行为实现引用</b>，
 * 平台在反射桥接层据此构造真正的 Minecraft 方块。
 *
 * <p>例如一个作物方块：
 * <pre>{@code
 * BlockSpec spec = BlockSpec.builder("crops/amaranth")
 *     .behavior(new AmaranthCrop())
 *     .material(Material.PLANT)
 *     .noCollision()
 *     .replaceable()
 *     .hardness(0.0f)
 *     .requiresTool(false)
 *     .build();
 * }</pre>
 */
public final class BlockSpec {

    private final String path;
    private final MiliBlock behavior;
    private final Material material;
    private final Map<String, String> textures;
    private final boolean collision;
    private final boolean replaceable;
    private final boolean noCollision;
    private final float hardness;
    private final boolean requiresTool;
    private final boolean hasBlockEntity;
    private final boolean isAir;
    private final int lightLevel;
    private final boolean redstoneConducts;
    private final boolean solid;

    private BlockSpec(Builder b) {
        this.path = b.path;
        this.behavior = b.behavior;
        this.material = b.material;
        this.textures = Map.copyOf(b.textures);
        this.collision = b.collision;
        this.replaceable = b.replaceable;
        this.noCollision = b.noCollision;
        this.hardness = b.hardness;
        this.requiresTool = b.requiresTool;
        this.hasBlockEntity = b.hasBlockEntity;
        this.isAir = b.isAir;
        this.lightLevel = b.lightLevel;
        this.redstoneConducts = b.redstoneConducts;
        this.solid = b.solid;
    }

    public static Builder builder(String path) {
        return new Builder(path);
    }

    public String path() {
        return path;
    }

    /** 方块行为实现；未提供时平台使用无行为的默认方块。 */
    public Optional<MiliBlock> behavior() {
        return Optional.ofNullable(behavior);
    }

    public Material material() {
        return material;
    }

    /** 命名空间内的材质定义，如 {@code minecraft:cross}。 */
    public Map<String, String> textures() {
        return textures;
    }

    public boolean hasCollision() {
        return collision;
    }

    public boolean isReplaceable() {
        return replaceable;
    }

    public boolean hasNoCollision() {
        return noCollision;
    }

    /** 硬度；负值表示不可破坏。 */
    public float hardness() {
        return hardness;
    }

    public boolean requiresTool() {
        return requiresTool;
    }

    public boolean hasBlockEntity() {
        return hasBlockEntity;
    }

    public boolean isAir() {
        return isAir;
    }

    /** 发光度 0..15。 */
    public int lightLevel() {
        return lightLevel;
    }

    public boolean conductsRedstone() {
        return redstoneConducts;
    }

    public boolean isSolid() {
        return solid;
    }

    /**
     * 材质种类。
     *
     * <p>刻意做成枚举而非直接暴露 Minecraft 的 Material —— 后者在不同
     * Minecraft 版本间是常量池，值会漂移。枚举由平台映射到具体版本。
     */
    public enum Material {
        /** 普通立方体（有完整六面）。 */
        SOLID,
        /** 平面植物（十字贴图）。 */
        PLANT,
        /** 液体。 */
        LIQUID,
        /** 火把等小方块。 */
        TORCH,
        /** 栅栏（连接相邻方块）。 */
        FENCE,
        /** 台阶。 */
        SLAB,
        /** 楼梯。 */
        STAIRS,
        /** 门。 */
        DOOR,
        /** 活板门。 */
        TRAPDOOR,
        /** 按钮。 */
        BUTTON,
        /** 压力板。 */
        PRESSURE_PLATE,
        /** 门帘。 */
        BED,
        /** 不可见但有碰撞。 */
        BARRIER
    }

    /** 方块规格构造器。 */
    public static final class Builder {
        private final String path;
        private MiliBlock behavior;
        private Material material = Material.SOLID;
        private final Map<String, String> textures = new LinkedHashMap<>();
        private boolean collision = true;
        private boolean replaceable;
        private boolean noCollision;
        private float hardness = 3.0f;
        private boolean requiresTool = true;
        private boolean hasBlockEntity;
        private boolean isAir;
        private int lightLevel;
        private boolean redstoneConducts;
        private boolean solid = true;

        private Builder(String path) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("block path must not be blank");
            }
            if (path.contains(" ")) {
                throw new IllegalArgumentException(
                        "block path must not contain spaces: " + path);
            }
            this.path = path;
        }

        public Builder behavior(MiliBlock behavior) {
            this.behavior = behavior;
            return this;
        }

        public Builder material(Material material) {
            this.material = material;
            return this;
        }

        /**
         * 绑定材质。
         *
         * @param key  材质槽名，如 {@code all} / {@code side} / {@code top}
         * @param spec 材质定义，如 {@code minecraft:cross}
         */
        public Builder texture(String key, String spec) {
            this.textures.put(key, spec);
            return this;
        }

        public Builder collision(boolean collision) {
            this.collision = collision;
            return this;
        }

        public Builder noCollision() {
            this.collision = false;
            this.noCollision = true;
            return this;
        }

        public Builder replaceable() {
            this.replaceable = true;
            return this;
        }

        public Builder hardness(float hardness) {
            this.hardness = hardness;
            return this;
        }

        public Builder requiresTool(boolean requiresTool) {
            this.requiresTool = requiresTool;
            return this;
        }

        /** 声明该方块需要方块实体。注册带行为的方块时通常需要。 */
        public Builder blockEntity() {
            this.hasBlockEntity = true;
            return this;
        }

        public Builder air() {
            this.isAir = true;
            this.collision = false;
            this.solid = false;
            this.hardness = 0.0f;
            return this;
        }

        public Builder lightLevel(int lightLevel) {
            if (lightLevel < 0 || lightLevel > 15) {
                throw new IllegalArgumentException("lightLevel must be 0..15, got " + lightLevel);
            }
            this.lightLevel = lightLevel;
            return this;
        }

        public Builder conductsRedstone() {
            this.redstoneConducts = true;
            return this;
        }

        public Builder solid(boolean solid) {
            this.solid = solid;
            return this;
        }

        public BlockSpec build() {
            return new BlockSpec(this);
        }
    }
}
