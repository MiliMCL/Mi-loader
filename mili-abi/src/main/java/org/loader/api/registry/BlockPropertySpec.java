package org.loader.api.registry;

import java.util.Objects;

/**
 * 方块状态属性声明 —— 描述方块携带的一个状态属性（如作物的 {@code age}）。
 *
 * <p>纯契约数据类：只承载<b>名字与取值域</b>，不引用任何 Minecraft 类型。
 * 平台在注册方块时把它映射为目标版本真实的 {@code Property} 实例
 * （整数 → {@code IntegerProperty}，布尔 → {@code BooleanProperty}）。
 *
 * <h2>为什么需要显式声明</h2>
 *
 * <p>平台此前只能从 Mod JAR 内的 blockstate JSON 反推属性（变体键
 * {@code "age=0"}.."age=6" → {@code age: 0..6}）。反推对「已有资源文件」
 * 的 Mod 有效，但它有三个结构性缺口：
 * <ol>
 *   <li>没有 blockstate 的方块永远得不到属性 —— 而 "Missing model for
 *       variant" 只是症状之一，随机刻生长、比较器读数等玩法同样依赖属性；</li>
 *   <li>枚举属性（朝向、轴向）无法从字符串值合成，推断直接跳过；</li>
 *   <li>属性语义藏在资源文件里 —— 改名一个 JSON 键就是行为变更，
 *       编译期完全不可见。</li>
 * </ol>
 * 显式声明把属性从「资源文件的隐含约定」提升为「代码里的显式契约」，
 * 与 Mili「显式优于隐式，编译错误优于运行时错误」的哲学一致。
 *
 * <h2>与推断的关系</h2>
 *
 * <p>两条来源<b>共存且互补</b>：显式声明的属性优先；推断补齐其余
 * （同名时推断让位）。已有 Mod 只带 blockstate 也能继续工作 ——
 * 零迁移成本。推荐新代码显式声明，推断仅作为兜底。
 *
 * <p>用法：
 * <pre>{@code
 * BlockSpec spec = BlockSpec.builder("crops/amaranth")
 *     .behavior(new AmaranthCrop())
 *     .material(BlockSpec.Material.PLANT)
 *     .property(BlockPropertySpec.integer("age", 0, 6))
 *     .property(BlockPropertySpec.booleanProperty("hydrated"))
 *     .build();
 * }</pre>
 */
public final class BlockPropertySpec {

    /** 属性取值种类。 */
    public enum Kind {
        /** 整数属性，闭区间 {@code [min, max]}。 */
        INTEGER,
        /** 布尔属性，取值 {@code true/false}。 */
        BOOLEAN
    }

    private final String name;
    private final Kind kind;
    private final int min;
    private final int max;

    private BlockPropertySpec(String name, Kind kind, int min, int max) {
        this.name = name;
        this.kind = kind;
        this.min = min;
        this.max = max;
    }

    /**
     * 声明一个整数属性。
     *
     * @param name 属性名，如 {@code "age"}；不得为空、不得含空格、
     *             {@code '='} 或 {@code ','}（后两者会破坏 blockstate
     *             变体键 {@code name=value} 的格式）
     * @param min  最小值（含）
     * @param max  最大值（含）；必须 {@code >= min}
     */
    public static BlockPropertySpec integer(String name, int min, int max) {
        validateName(name);
        if (min > max) {
            throw new IllegalArgumentException(
                    "property '" + name + "': min (" + min + ") must be <= max (" + max + ")");
        }
        return new BlockPropertySpec(name, Kind.INTEGER, min, max);
    }

    /**
     * 声明一个布尔属性。
     *
     * @param name 属性名，如 {@code "hydrated"}；约束同 {@link #integer}
     */
    public static BlockPropertySpec booleanProperty(String name) {
        validateName(name);
        return new BlockPropertySpec(name, Kind.BOOLEAN, 0, 1);
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("property name must not be blank");
        }
        if (name.chars().anyMatch(c -> Character.isWhitespace(c)
                || c == '=' || c == ',')) {
            throw new IllegalArgumentException(
                    "property name must not contain whitespace, '=' or ',': " + name);
        }
    }

    /** 属性名。 */
    public String name() {
        return name;
    }

    /** 取值种类。 */
    public Kind kind() {
        return kind;
    }

    /** 整数属性的最小值（含）；布尔属性恒为 0。 */
    public int min() {
        return min;
    }

    /** 整数属性的最大值（含）；布尔属性恒为 1。 */
    public int max() {
        return max;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockPropertySpec other)) {
            return false;
        }
        return min == other.min && max == other.max
                && kind == other.kind && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, kind, min, max);
    }

    @Override
    public String toString() {
        if (kind == Kind.BOOLEAN) {
            return name + ":boolean";
        }
        return name + ":" + min + ".." + max;
    }
}
