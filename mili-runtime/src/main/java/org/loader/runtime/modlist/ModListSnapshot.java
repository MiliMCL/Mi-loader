package org.loader.runtime.modlist;

import java.util.List;

/**
 * 一次 Mod 列表快照 —— 平台身份、内存状况与全部 Mod 条目。
 *
 * <h2>为什么快照而不是实时查询</h2>
 * 列表界面每帧都会读它（60fps 下每秒 60 次）。如果每次渲染都去问
 * {@code ModClassLoader} 要 {@code loadedClasses()}，那是在渲染线程上
 * 做一次并发集合的拷贝 —— 帧时间会被 Mod 数量线性拉长。
 *
 * <p>因此语义定死为：<b>打开列表时拍一次快照，界面生命周期内不变</b>。
 * 玩家看到的是「我按下按钮那一刻的状态」，这正好也是排障时想要的语义 ——
 * 一个会自己变化的诊断面板比不刷新更让人困惑。
 *
 * @param platformVersion  平台版本
 * @param abiVersion       ABI 契约版本
 * @param minecraftVersion 目标 Minecraft 版本
 * @param javaVersion      运行时 Java 版本
 * @param environment      {@code CLIENT} / {@code DEDICATED_SERVER}
 * @param usedHeapBytes    已用堆
 * @param maxHeapBytes     最大堆
 * @param threadCount      活动线程数
 * @param uptimeMillis     平台启动至今的毫秒数
 * @param mods             全部 Mod（顺序与加载顺序一致）
 */
public record ModListSnapshot(
        String platformVersion,
        int abiVersion,
        String minecraftVersion,
        String javaVersion,
        String environment,
        long usedHeapBytes,
        long maxHeapBytes,
        int threadCount,
        long uptimeMillis,
        List<ModEntry> mods) {

    public ModListSnapshot {
        mods = mods != null ? List.copyOf(mods) : List.of();
        platformVersion = platformVersion != null ? platformVersion : "?";
        minecraftVersion = minecraftVersion != null ? minecraftVersion : "?";
        javaVersion = javaVersion != null ? javaVersion : "?";
        environment = environment != null ? environment : "UNKNOWN";
    }

    /** 空快照 —— 数据源尚未装配时的安全默认值。 */
    public static ModListSnapshot empty() {
        return new ModListSnapshot("?", 0, "?", "?", "UNKNOWN", 0L, 0L, 0, 0L, List.of());
    }

    /** 已用堆占最大堆的百分比（0..100）；最大堆未知时返回 0。 */
    public int heapPercent() {
        if (maxHeapBytes <= 0) {
            return 0;
        }
        long pct = usedHeapBytes * 100L / maxHeapBytes;
        return (int) Math.max(0L, Math.min(100L, pct));
    }

    /** 人类可读的堆描述，如 {@code "182 / 4096 MiB (4%)"}。 */
    public String heapText() {
        return mib(usedHeapBytes) + " / " + mib(maxHeapBytes) + " MiB (" + heapPercent() + "%)";
    }

    /** 人类可读的运行时长，如 {@code "1m 23s"}。 */
    public String uptimeText() {
        long sec = uptimeMillis / 1000L;
        if (sec < 60) {
            return sec + "s";
        }
        long min = sec / 60;
        long rem = sec % 60;
        if (min < 60) {
            return min + "m " + rem + "s";
        }
        return (min / 60) + "h " + (min % 60) + "m";
    }

    private static long mib(long bytes) {
        return bytes <= 0 ? 0 : bytes / (1024L * 1024L);
    }
}
