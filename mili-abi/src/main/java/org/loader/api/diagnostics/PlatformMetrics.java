package org.loader.api.diagnostics;

import java.util.List;

/**
 * 平台运行时指标快照 —— 一次采样，不可变。
 *
 * <h2>为什么是快照而不是实时查询对象</h2>
 * 诊断界面（Mod 列表、性能面板）会在渲染循环里读这些值。如果每个字段
 * 都做成「读的时候现场问 JVM」，一次渲染就会触发几十次 MXBean 调用，
 * 帧时间被诊断代码本身吃掉 —— 观测者效应在性能工具里格外讽刺。
 *
 * <p>因此语义定死：<b>采样一次，消费方随便读</b>。需要新数据就再采一次。
 *
 * <h2>字段的取舍</h2>
 * 只保留「能指导行动」的指标：
 * <ul>
 *   <li>{@code heapUsed / heapMax} —— 决定要不要提示玩家调大 {@code -Xmx}</li>
 *   <li>{@code gcCollections / gcTimeMillis} —— 判断卡顿是否来自 GC</li>
 *   <li>{@code liveThreads} —— 找出泄漏的线程（Scope 未关闭的典型症状）</li>
 *   <li>{@code loadedClasses} —— Mod ClassLoader 泄漏的最直接信号</li>
 *   <li>{@code perModHeapEstimate} —— 粗粒度归因，不精确但能指出方向</li>
 * </ul>
 * 刻意<b>不</b>包含 CPU 使用率：{@code OperatingSystemMXBean} 的 CPU 值
 * 在不同平台语义不一致（有的返回进程、有的返回整机），放进来只会误导。
 *
 * @param heapUsedBytes       已用堆
 * @param heapCommittedBytes  已提交堆
 * @param heapMaxBytes        最大堆（{@code -Xmx}）
 * @param nonHeapUsedBytes    非堆已用（元空间等）
 * @param liveThreads         当前活动线程数
 * @param peakThreads         峰值线程数 —— 与 liveThreads 的差值就是「曾经泄过」
 * @param gcCollections       累计 GC 次数
 * @param gcTimeMillis        累计 GC 耗时
 * @param uptimeMillis        平台启动至今
 * @param modCount            已加载 Mod 数量
 * @param loadedClasses       JVM 已加载类总数
 * @param tickCount           已处理 tick 数；未接入 tick 引擎时为 0
 * @param averageTickMs       平均 tick 耗时；未接入时为 0
 * @param perModHeapEstimate  各 Mod 的堆占用估算（字节），按占用降序
 */
public record PlatformMetrics(
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        int liveThreads,
        int peakThreads,
        long gcCollections,
        long gcTimeMillis,
        long uptimeMillis,
        int modCount,
        int loadedClasses,
        long tickCount,
        double averageTickMs,
        List<ModMemoryEstimate> perModHeapEstimate) {

    public PlatformMetrics {
        perModHeapEstimate = perModHeapEstimate != null
                ? List.copyOf(perModHeapEstimate) : List.of();
    }

    /** 全零快照 —— 服务未装配时的安全默认值。 */
    public static PlatformMetrics empty() {
        return new PlatformMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    /** 单个 Mod 的内存估算。 */
    public record ModMemoryEstimate(String modId, long estimatedBytes, int loadedClasses) {
    }

    /** 堆使用率（0..100）。最大堆未知时返回 0。 */
    public int heapPercent() {
        if (heapMaxBytes <= 0) {
            return 0;
        }
        long pct = heapUsedBytes * 100L / heapMaxBytes;
        return (int) Math.max(0L, Math.min(100L, pct));
    }

    /** 人类可读堆描述，如 {@code "182 / 4096 MiB (4%)"}。 */
    public String heapText() {
        return mib(heapUsedBytes) + " / " + mib(heapMaxBytes) + " MiB (" + heapPercent() + "%)";
    }

    /** GC 摘要，如 {@code "12 次 / 340ms"}。 */
    public String gcText() {
        return gcCollections + " 次 / " + gcTimeMillis + "ms";
    }

    /** 线程摘要，如 {@code "38 live / 52 peak"}。 */
    public String threadText() {
        return liveThreads + " live / " + peakThreads + " peak";
    }

    /** 运行时长，如 {@code "12m 03s"}。 */
    public String uptimeText() {
        long sec = uptimeMillis / 1000L;
        long min = sec / 60;
        long rem = sec % 60;
        // 不用 String.format：它每次都要解析格式串并分配 StringBuilder，
        // 而这个方法是「每帧画底栏」路径上的调用点。
        return min + "m " + (rem < 10 ? "0" : "") + rem + "s";
    }

    /**
     * 内存压力等级：0 = 宽裕，1 = 偏高（>70%），2 = 紧张（>85%）。
     *
     * <p>给 UI 用。阈值是经验值：Minecraft 客户端在 85% 以上时，
     * 一次世界加载就可能触发 Full GC，表现为「走路突然卡一下」。
     */
    public int pressureLevel() {
        int pct = heapPercent();
        if (pct >= 85) {
            return 2;
        }
        return pct >= 70 ? 1 : 0;
    }

    private static long mib(long bytes) {
        return bytes <= 0 ? 0 : bytes / (1024L * 1024L);
    }
}
