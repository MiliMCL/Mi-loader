package org.loader.runtime.transform.debug;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 转换日志 —— 调试模式下的转换追踪。
 *
 * <h2>默认关闭，且必须能靠系统属性打开</h2>
 * 启用方式：{@code -Dmili.transform.debug=true}
 *
 * <p><b>为什么默认关闭</b>：一次完整启动会加载约 1 万个类，其中被转换的
 * 可能有几十个。逐条打印到 stdout 会淹没真正的错误信息。更重要的是 ——
 * 本仓库已有 {@code BootstrapTimingProbe} 的教训：<b>在启动路径上打印
 * 任何东西都会成为性能问题</b>，且这类开销在开发机上不明显、在玩家机上
 * 很明显。
 *
 * <h2>为什么它值得存在</h2>
 * 转换出问题时，用户能拿到的信息是「游戏崩了」。而以下三个问题
 * <b>只有日志能回答</b>：
 * <ul>
 *   <li>「我声明的转换到底生效了吗？」—— 静默失效是本仓库最痛的 bug 类型；</li>
 *   <li>「谁先执行的？」—— 顺序问题导致的 bug 无法从代码看出；</li>
 *   <li>「字节码变了多少？」—— 变化量为 0 说明转换器实际没做事。</li>
 * </ul>
 */
public final class TransformationLogger {

    /** 系统属性开关。 */
    public static final String DEBUG_PROPERTY = "mili.transform.debug";

    private final boolean enabled;
    private final PrintStream out;

    private final AtomicLong traceCount = new AtomicLong();
    private final AtomicLong transformCount = new AtomicLong();
    private final AtomicLong unchangedCount = new AtomicLong();
    private final AtomicLong skippedCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong cacheHitCount = new AtomicLong();

    /** 最近一次转换的追踪文本；测试可读取。 */
    private volatile String lastTrace;

    private TransformationLogger(boolean enabled, PrintStream out) {
        this.enabled = enabled;
        this.out = out;
    }

    /** 创建日志器；是否启用由系统属性决定。 */
    public static TransformationLogger create() {
        return new TransformationLogger(isDebugEnabled(), System.out);
    }

    /** 创建日志器到指定流（测试用）。 */
    public static TransformationLogger create(PrintStream stream, boolean enabled) {
        return new TransformationLogger(enabled, stream);
    }

    /** 显式关闭的日志器 —— 生产默认。 */
    public static TransformationLogger disabled() {
        return new TransformationLogger(false, System.out);
    }

    private static boolean isDebugEnabled() {
        try {
            return Boolean.parseBoolean(System.getProperty(DEBUG_PROPERTY, "false"));
        } catch (Throwable t) {
            // 安全策略可能禁止读系统属性 —— 此时按关闭处理，不影响启动。
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 开始一次类转换的追踪。
     *
     * @return 追踪缓冲；结束时交给 {@link #endTrace}
     */
    public StringBuilder beginTrace(String className, int transformerCount) {
        traceCount.incrementAndGet();
        if (!enabled) {
            return new StringBuilder();
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("[transform] ").append(className)
                .append("  applicable=").append(transformerCount).append('\n');
        return sb;
    }

    /** 结束追踪并输出。 */
    public void endTrace(StringBuilder trace, String className, boolean changed) {
        if (trace == null || trace.length() == 0) {
            return;
        }
        trace.append("[transform] ").append(className)
                .append("  result=").append(changed ? "CHANGED" : "UNCHANGED")
                .append('\n');
        lastTrace = trace.toString();
        print(trace.toString());
    }

    /** 记录一次成功的字节码改变。 */
    public void transformed(String className, String transformerId, int sizeDelta) {
        transformCount.incrementAndGet();
        if (!enabled) {
            return;
        }
        print("    → " + transformerId + " transformed " + className
                + " (Δ" + sizeDelta + " bytes)\n");
    }

    /**
     * 记录一次「处理了但没改」。
     *
     * <p>这个事件值得单独记录：若一个转换器长期对同一个类返回
     * Unchanged，通常意味着它的目标解析逻辑有问题（例如匹配到了类
     * 但没找到方法，且没有抛 TargetNotFound）。
     */
    public void unchanged(String className, String transformerId) {
        unchangedCount.incrementAndGet();
        if (!enabled) {
            return;
        }
        print("    · " + transformerId + " unchanged " + className + '\n');
    }

    /** 记录一次声明式跳过。 */
    public void skipped(String className, String transformerId) {
        skippedCount.incrementAndGet();
        if (!enabled) {
            return;
        }
        print("    - " + transformerId + " skipped " + className + '\n');
    }

    /** 记录一次失败。失败永远输出，不受 debug 开关影响 —— 它是错误。 */
    public void failure(String className, String transformerId, String reason) {
        failureCount.incrementAndGet();
        print("[transform] FAILED " + className + " by " + transformerId
                + "\n  " + reason + '\n');
    }

    /** 记录一次缓存命中。 */
    public void cacheHit(String className) {
        cacheHitCount.incrementAndGet();
        if (!enabled) {
            return;
        }
        print("    = cache hit " + className + '\n');
    }

    private void print(String message) {
        if (out == null) {
            return;
        }
        try {
            out.print(message);
        } catch (Throwable ignored) {
            // 日志失败绝不能影响启动。
        }
    }

    /** 最近一次追踪（测试用）。 */
    public String lastTrace() {
        return lastTrace;
    }

    public long traceCount() {
        return traceCount.get();
    }

    public long transformCount() {
        return transformCount.get();
    }

    public long unchangedCount() {
        return unchangedCount.get();
    }

    public long skippedCount() {
        return skippedCount.get();
    }

    public long failureCount() {
        return failureCount.get();
    }

    public long cacheHitCount() {
        return cacheHitCount.get();
    }

    public String diagnostics() {
        List<String> parts = new ArrayList<>();
        parts.add("enabled=" + enabled);
        parts.add("traces=" + traceCount.get());
        parts.add("transformed=" + transformCount.get());
        parts.add("unchanged=" + unchangedCount.get());
        parts.add("skipped=" + skippedCount.get());
        parts.add("failures=" + failureCount.get());
        parts.add("cacheHits=" + cacheHitCount.get());
        return "TransformationLogger[" + String.join(", ", parts) + "]";
    }
}