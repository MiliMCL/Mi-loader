package org.loader.api.transform;

/**
 * 转换阶段 —— 决定 Transformer 在流水线中的执行次序。
 *
 * <p><b>为什么阶段必须存在，而不只是 priority 数字</b>：priority 只能表达
 * 「谁先跑」，无法表达「谁在谁的能力范围内跑」。而后者是安全边界：
 * Mili Core 的 tick 接线必须发生在 Mod 的任何修改<i>之前</i>，
 * 否则 Mod 可能拿到一个半接线的 TickEngine。
 *
 * <p><b>执行顺序严格由 {@link #ordinal()} 决定</b>，同一阶段内才按
 * {@link org.loader.api.transform.MiliTransformer#priority()} 排序。
 *
 * <p>阶段与能力的对应关系（见 ADR-0011）：
 * <ul>
 *   <li>{@link #EARLY} — 基础设施改写，必须先于一切</li>
 *   <li>{@link #CORE} — Mili Core：Tick / Lifecycle / Scheduler 接线</li>
 *   <li>{@link #MINECRAFT} — 平台自身的 Minecraft 适配</li>
 *   <li>{@link #MOD} — Mod 转换器</li>
 *   <li>{@link #LATE} — 兜底改写，会看到前面所有结果</li>
 * </ul>
 *
 * <p><b>Core 与 Mod 冲突时 Core 胜出</b>：Mod 若试图覆盖一个
 * {@code CORE} 阶段已被占用的目标，Pipeline 抛
 * {@link org.loader.api.transform.TransformationConflictException}，
 * 绝不静默覆盖。
 */
public enum TransformationPhase {

    /**
     * 早期 —— 类加载基础设施改写。
     *
     * <p>典型用途：需要在其他一切之前修正类本身的元数据
     * （例如给缺失的 record component 补齐 accessor）。
     */
    EARLY(0),

    /**
     * Mili Core —— 平台核心能力的接线。
     *
     * <p>典型用途：把 Minecraft 的真实 tick 入口接到
     * {@code TickBridge}。
     *
     * <p><b>此阶段的目标受保护</b>：任何 Mod 阶段的 transformer 都不能
     * 覆盖本阶段已占用的注入位置，需要 {@code OVERWRITE_METHOD} 能力
     * 且仍需显式 opt-in。
     */
    CORE(1),

    /**
     * Minecraft 适配 —— 平台针对具体游戏版本的改写。
     *
     * <p>典型用途：游戏版本差异的兼容垫片。
     */
    MINECRAFT(2),

    /**
     * Mod —— 模组自己的转换。
     *
     * <p>需要 {@code TRANSFORM_CLASS} 或 {@code TRANSFORM_MINECRAFT}
     * 能力；普通 Mod 默认不具备。
     */
    MOD(3),

    /**
     * 晚期 —— 兜底。
     *
     * <p>能看到前面所有阶段的结果，用于最终校验或补充。
     */
    LATE(4);

    private final int weight;

    TransformationPhase(int weight) {
        this.weight = weight;
    }

    /** 排序权重，数值越小越先执行。 */
    public int weight() {
        return weight;
    }

    /**
     * 是否为平台受保护阶段 —— 受保护目标不可被 Mod 阶段的 transformer 覆盖。
     *
     * <p>只有 {@link #CORE} 受保护：它承载 Tick / Lifecycle 的正确性，
     * 一个被 Mod 静默改写的 TickEngine 会让整条 tick 链失效，且这种失效
     * 极难定位（游戏照常运行，只是 tick 数不对）。
     */
    public boolean isProtected() {
        return this == CORE;
    }
}