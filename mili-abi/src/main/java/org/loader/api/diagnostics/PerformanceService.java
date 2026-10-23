package org.loader.api.diagnostics;

/**
 * 性能采样服务 —— TPS / tick 延迟 / 帧率的唯一读取入口。
 *
 * <h2>与 {@link MetricsService} 的分工</h2>
 * {@code MetricsService} 是低频快照（堆、GC、线程），本服务是高频快照
 * （每 tick 更新的 TPS 与百分位）。分开注册是为了让「没有接入 tick 的环境」
 * 仍然能读到内存指标，而不必伪造一个 TPS。
 *
 * <h2>未装配时的行为</h2>
 * 返回 {@link PerformanceSnapshot#empty()}，不抛异常 —— 与服务端、
 * 单元测试、平台尚未启动到 tick 阶段这些场景保持一致。
 *
 * <pre>
 * PerformanceSnapshot p = PerformanceService.current();
 * if (p.hasTickData() &amp;&amp; p.p95TickMs() &gt; 45) {
 *     log.warning("tick p95 偏高: " + p.tickText() + " ms");
 * }
 * </pre>
 */
public final class PerformanceService {

    /** 平台实现。 */
    @FunctionalInterface
    public interface Provider {
        PerformanceSnapshot snapshot();
    }

    private static volatile Provider provider;

    private PerformanceService() {
    }

    /** 平台装配；传 null 恢复为「永远返回空快照」。 */
    public static void registerProvider(Provider p) {
        provider = p;
    }

    /**
     * 撤销装配 —— 仅当当前 provider 就是传入实例时生效。
     *
     * <p>按身份撤销而不是无条件清空：测试里会创建多个 Runtime，
     * 后关闭的那个不该把仍在运行的实例的 provider 抹掉。
     */
    public static void clearProvider(Provider p) {
        if (provider == p) {
            provider = null;
        }
    }

    /** 是否已装配。 */
    public static boolean available() {
        return provider != null;
    }

    /**
     * 读取一次快照。
     *
     * <p>实现方保证 O(1) 取数（百分位在采样器内部按窗口计算），
     * 可以在 4Hz 的诊断面板里直接调用。逐帧调用没有必要：
     * 16ms 内 TPS 不会发生有意义的变化。
     */
    public static PerformanceSnapshot current() {
        Provider p = provider;
        if (p == null) {
            return PerformanceSnapshot.empty();
        }
        try {
            PerformanceSnapshot s = p.snapshot();
            return s != null ? s : PerformanceSnapshot.empty();
        } catch (RuntimeException e) {
            // 诊断实现自己炸了不该传染给调用方
            return PerformanceSnapshot.empty();
        }
    }
}
