package org.loader.api.diagnostics;

import java.util.Locale;

/**
 * 性能快照 —— TPS、tick 延迟分布与帧率的一次采样结果。
 *
 * <h2>为什么单独一个快照，而不是塞进 {@link PlatformMetrics}</h2>
 * {@code PlatformMetrics} 回答的是「内存与线程够不够用」，采样源是 JMX；
 * 本快照回答的是「卡不卡、卡在哪」，采样源是 tick 循环与帧循环。
 * 两者的刷新频率、成本、失效方式都不同（JMX 可以 4Hz，tick 统计必须逐 tick），
 * 混在一个 record 里会让「只想读堆占用」的调用方也承担排序百分位的成本。
 *
 * <h2>窗口而不是累计值</h2>
 * 累计平均 TPS 是最没用的指标：开局 30 秒的卡顿会被后面两小时的好数据稀释到
 * 看不见。这里所有 TPS/百分位都是<b>滑动窗口</b>（1s / 5s / 15s），
 * 卡顿会在窗口里停留一段时间然后自然滚出 —— 与玩家的感受一致。
 *
 * <h2>空数据与「暂停」</h2>
 * 主菜单里没有 integrated server tick，TPS 必然是 0。把 0 当成「性能极差」
 * 会误导判断，因此 {@link #lastTickAgeMs()} 用于区分「没有 tick」与
 * 「tick 很慢」：{@link #hasTickData()} 为 false 时 UI 应显示 {@code —}。
 *
 * @param totalTicks       进程生命周期内累计 tick 数
 * @param tickSamples      当前窗口内的 tick 样本数（0 表示尚未开始采样）
 * @param lastTickAgeMs    距离最近一次 tick 结束的毫秒数；无样本时为 -1
 * @param tps1s            最近 1 秒窗口的实际 TPS
 * @param tps5s            最近 5 秒窗口的实际 TPS
 * @param tps15s           最近 15 秒窗口的实际 TPS
 * @param averageTickMs    窗口内 tick 耗时均值
 * @param p50TickMs        tick 耗时中位数
 * @param p95TickMs        tick 耗时 95 分位（卡顿的主要判据）
 * @param p99TickMs        tick 耗时 99 分位
 * @param maxTickMs        窗口内最慢的一次 tick
 * @param missedDeadlines  最近窗口内超过 50ms 预算的 tick 数（不是累计值）
 * @param fps              最近一次帧率采样（原版 1 秒平滑值）
 * @param minFps5s         最近 5 秒内的最低帧率
 * @param averageFps5s     最近 5 秒内的平均帧率
 * @param p95FrameMs       帧耗时 95 分位（毫秒）
 * @param frameSamples     当前窗口内的帧样本数
 * @param uptimeMillis     平台启动至今的毫秒数
 */
public record PerformanceSnapshot(
        long totalTicks,
        int tickSamples,
        long lastTickAgeMs,
        double tps1s,
        double tps5s,
        double tps15s,
        double averageTickMs,
        double p50TickMs,
        double p95TickMs,
        double p99TickMs,
        double maxTickMs,
        long missedDeadlines,
        int fps,
        int minFps5s,
        double averageFps5s,
        double p95FrameMs,
        int frameSamples,
        long uptimeMillis) {

    /** 无数据快照 —— 服务未装配或采样器已关闭时的安全默认值。 */
    public static PerformanceSnapshot empty() {
        return new PerformanceSnapshot(0, 0, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0);
    }

    /** 健康度分级 —— 只用于显示，不参与任何自动决策。 */
    public enum Health {
        /** 没有足够样本（主菜单、刚启动、服务未装配）。 */
        UNKNOWN,
        /** 目标帧率/TPS 内，没有可感知的卡顿。 */
        GOOD,
        /** 有掉帧或 tick 超时，但仍可玩。 */
        DEGRADED,
        /** 明显卡顿，需要排查。 */
        POOR
    }

    /**
     * 是否有可解释的 tick 数据。
     *
     * <p>阈值取 5 秒：超过 5 秒没有 tick，要么世界没加载，要么 tick 循环
     * 已经停了 —— 两种情况都不该显示一个「0 TPS 极差」的结论。
     */
    public boolean hasTickData() {
        return tickSamples >= 2 && lastTickAgeMs >= 0 && lastTickAgeMs <= 5_000L;
    }

    /** 是否有可解释的帧数据。 */
    public boolean hasFrameData() {
        // fps == 0 是启动首秒的常见值，不应当作「0 帧」参与健康度判断
        return frameSamples > 0 && fps > 0;
    }

    /**
     * 综合健康度。
     *
     * <p>判据刻意简单：tick 看 5 秒 TPS 与 p95，帧率看当前值。
     * 复杂的加权评分在诊断界面上没有意义 —— 玩家需要的是「正常/不正常」，
     * 而不是一个无法解释的 73 分。
     */
    public Health health() {
        if (!hasTickData()) {
            return Health.UNKNOWN;
        }
        if (tps5s >= 19.0 && p95TickMs <= 40.0 && (!hasFrameData() || fps >= 50)) {
            return Health.GOOD;
        }
        if (tps5s >= 15.0 && p95TickMs <= 60.0) {
            return Health.DEGRADED;
        }
        return Health.POOR;
    }

    /** 形如 {@code "20.0 / 19.9"}（1s / 5s）；无数据时为 {@code "—"}。 */
    public String tpsText() {
        if (!hasTickData()) {
            return "—";
        }
        return one(tps1s) + " / " + one(tps5s);
    }

    /** 形如 {@code "1.2 / 3.4 / 12.0"}（均值 / p95 / 峰值，毫秒）。 */
    public String tickText() {
        if (!hasTickData()) {
            return "—";
        }
        return one(averageTickMs) + " / " + one(p95TickMs) + " / " + one(maxTickMs);
    }

    /** 形如 {@code "61 (5s 最低 48)"}；无数据时为 {@code "—"}。 */
    public String fpsText() {
        if (!hasFrameData()) {
            return "—";
        }
        return fps + " (5s 最低 " + minFps5s + ")";
    }

    /** 窗口内超时 tick 数；0 时返回 {@code "0"}，非 0 时带单位。 */
    public String deadlineText() {
        return missedDeadlines == 0 ? "0" : missedDeadlines + " 次";
    }

    private static String one(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
