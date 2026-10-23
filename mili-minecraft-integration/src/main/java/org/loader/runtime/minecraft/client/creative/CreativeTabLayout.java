package org.loader.runtime.minecraft.client.creative;

/**
 * 创造栏页签布局 —— 原版 7 列之外的「Mod 页」位置计算。
 *
 * <h2>背景：原版没有位置了</h2>
 * 26.2 的 {@code CreativeModeInventoryScreen} 只有 TOP / BOTTOM 两行，
 * 每行按 {@code 27 * column} 排布，列 0..6 已全部被原版页签占用。
 * 平台给每个 Mod 建一个页签后，必须决定它画在哪里。
 *
 * <p>本类把 Mod 页签的列号解释为<b>哨兵值</b>（≥ {@link #MOD_COLUMN_BASE}），
 * 并给出面板右侧的位置：偶数索引 TOP 行、奇数索引 BOTTOM 行，同一行内
 * 从面板右缘开始向右顺延。这样不会与原版页签重叠，也不需要重排原版。
 *
 * <p>真正的排布由 {@code MiliCreativeTabLayoutTransformer} 注入到
 * {@code getTabX} —— 本类只做纯算术，便于单测覆盖。
 */
public final class CreativeTabLayout {

    /** Mod 页签的列号起点。原版列号是 0..6，100 起不会冲突。 */
    public static final int MOD_COLUMN_BASE = 100;

    /** Mod 页签在面板右侧的起始 x（相对面板左缘）。面板宽 195，留 5px 间隔。 */
    public static final int MOD_TAB_X = 200;

    /** 原版页签间距。 */
    public static final int TAB_SPACING = 27;

    /** 原版每行页签数（列 0..6）。 */
    private static final int VANILLA_COLUMNS = 7;

    private CreativeTabLayout() {
    }

    /**
     * {@code CreativeModeInventoryScreen#getTabX} 的替代实现。
     *
     * @param imageWidth   面板宽（原版 195）
     * @param column       页签列号；≥ 100 表示平台创建的 Mod 页
     * @param alignedRight 是否右对齐（原版 HOTBAR / SEARCH / INVENTORY 用）
     * @return 相对面板左缘的 x
     */
    public static int tabX(int imageWidth, int column, boolean alignedRight) {
        if (column >= MOD_COLUMN_BASE) {
            int slot = column - MOD_COLUMN_BASE;
            // 偶数索引在 TOP、奇数在 BOTTOM（见 ModCreativeTabs.registerTab），
            // 因此同一行内每两个索引才前进一格。
            return MOD_TAB_X + (slot / 2) * TAB_SPACING;
        }
        int x = TAB_SPACING * column;
        if (alignedRight) {
            x = imageWidth - TAB_SPACING * (VANILLA_COLUMNS - column) + 1;
        }
        return x;
    }

    /**
     * 修正原版 {@code Mth.clamp(pos, 0, sprites.length)} 的越界。
     *
     * <p>原版这行在列号 0..6 时永远取不到上界，所以 `max = length` 从未
     * 暴露问题；Mod 页签的哨兵列（100）会让它返回 7 并抛
     * {@code ArrayIndexOutOfBoundsException}。这里把上界收到 {@code length - 1}。
     */
    public static int clampSprite(int value, int min, int max) {
        int hi = Math.max(min, max - 1);
        return Math.min(Math.max(value, min), hi);
    }
}
