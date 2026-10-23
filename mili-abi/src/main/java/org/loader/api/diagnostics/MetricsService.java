package org.loader.api.diagnostics;

/**
 * 平台指标服务 —— Mod 读取运行时健康度的唯一入口。
 *
 * <h2>能力边界</h2>
 * 只读。Mod 不能通过它改任何东西 —— 诊断接口一旦可写，就会变成
 * 另一种形式的全局状态，而全局状态正是这个平台反复在躲的东西。
 *
 * <h2>未装配时的行为</h2>
 * 返回 {@link PlatformMetrics#empty()} 而不是抛异常。理由：诊断是
 * <b>可选能力</b>。一个 Mod 想在 HUD 上显示内存，不该因为平台尚未
 * 启动到运行期就整个初始化失败 —— 那种「为了可选功能牺牲主流程」
 * 的设计在别处已经吃过亏。
 *
 * <pre>
 * PlatformMetrics m = MetricsService.current();
 * if (m.heapPercent() > 85) {
 *     log.warning("堆占用偏高: " + m.heapText());
 * }
 * </pre>
 */
public final class MetricsService {

    /** 平台实现。 */
    @FunctionalInterface
    public interface Provider {
        PlatformMetrics snapshot();
    }

    private static volatile Provider provider;

    private MetricsService() {
    }

    /** 平台装配；传 null 恢复为「永远返回空快照」。 */
    public static void registerProvider(Provider p) {
        provider = p;
    }

    /** 是否已装配（Mod 可据此决定要不要显示诊断 UI）。 */
    public static boolean available() {
        return provider != null;
    }

    /**
     * 采样一次。
     *
     * <p>实现方可能有锁，但保证 O(1) 级别；调用频率建议不超过每秒一次。
     * 在渲染循环里逐帧调用是允许的，只是没有必要 —— 内存不会在
     * 16ms 内发生有意义的变化。
     */
    public static PlatformMetrics current() {
        Provider p = provider;
        if (p == null) {
            return PlatformMetrics.empty();
        }
        try {
            PlatformMetrics m = p.snapshot();
            return m != null ? m : PlatformMetrics.empty();
        } catch (RuntimeException e) {
            // 诊断实现自己炸了不该传染给调用方 —— 这正是「可选能力」的含义
            return PlatformMetrics.empty();
        }
    }
}
