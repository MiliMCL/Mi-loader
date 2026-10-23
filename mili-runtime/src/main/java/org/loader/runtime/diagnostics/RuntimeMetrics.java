package org.loader.runtime.diagnostics;

import org.loader.api.diagnostics.MetricsService;
import org.loader.api.diagnostics.PlatformMetrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * {@link MetricsService} 的平台实现 —— 从 JMX MXBean 采样。
 *
 * <h2>采样一次的代价，与为什么仍然要缓存</h2>
 * 单次 JMX 读取是微秒级，但「读一次」不等于「采样一次」：
 * {@code classCountsByMod} 要遍历每个 Mod 的 ClassLoader 并复制类集合，
 * 那是与 Mod 数量线性相关的操作，发生在渲染线程上时会直接吃掉帧预算。
 *
 * <p>因此这里加了 <b>250ms 的合并窗口</b>：窗口内的多次调用返回同一个
 * 不可变快照。理由不是「JMX 慢」，而是「调用方在 16ms 内反复问同一个问题」——
 * 内存和线程数不可能在 16ms 内发生有意义的变化，4Hz 已经高于人眼在
 * 诊断面板上能分辨的刷新率。代价是读数最多滞后 250ms，这在排障时
 * 远小于「观测者自己造成掉帧」的代价。
 *
 * <p>需要绝对实时值的场景（例如写进崩溃报告）应调用 {@link #sampleNow()}，
 * 它绕过缓存。
 *
 * <h2>per-mod 内存估算的方法与局限</h2>
 * 精确的 per-Mod 堆归因需要 JFR 或 instrumentation，代价远超收益。
 * 这里用「已加载类数 × 经验系数」做粗估，<b>只能用来排序，不能用来断言</b>：
 * 一个加载了 500 个类的 Mod 和一个加载了 50 个类的 Mod，前者占用
 * 更大的概率很高，但具体倍数没有意义。
 *
 * <p>系数取 3 KiB/class：一个已加载类在堆上的常驻开销（Class 对象、
 * 常量池、方法元数据镜像、字符串字面量）在这个量级。它不含 Mod
 * 自己 new 出来的业务对象 —— 所以真实值通常<b>更大</b>。
 * 显示时会带 {@code ~} 前缀提醒这是估算。
 */
public final class RuntimeMetrics implements MetricsService.Provider {

    /** 每个已加载类的粗略堆开销。见类注释的局限说明。 */
    private static final long BYTES_PER_CLASS_ESTIMATE = 3L * 1024L;

    /** 快照合并窗口：4Hz，高于诊断面板的实际刷新需求。 */
    private static final long SNAPSHOT_TTL_NANOS = 250_000_000L;

    private final IntSupplier modCount;
    private final java.util.function.Supplier<Map<String, Integer>> classCountsByMod;
    private final LongSupplier tickCount;
    private final DoubleSupplier averageTickMs;
    private final long startNanos;

    /** 合并窗口内的快照；只由 {@link #snapshot()} 写入。 */
    private volatile PlatformMetrics cached;
    private volatile long cachedAtNanos;

    /**
     * @param modCount             当前 Mod 数量
     * @param classCountsByMod     modId → 已加载类数
     * @param tickCount            已处理 tick 数（未接入时传 {@code () -> 0L}）
     * @param averageTickMs        平均 tick 耗时（未接入时传 {@code () -> 0d}）
     */
    public RuntimeMetrics(IntSupplier modCount,
                          java.util.function.Supplier<Map<String, Integer>> classCountsByMod,
                          LongSupplier tickCount,
                          DoubleSupplier averageTickMs) {
        this.modCount = modCount != null ? modCount : () -> 0;
        this.classCountsByMod = classCountsByMod != null ? classCountsByMod : Map::of;
        this.tickCount = tickCount != null ? tickCount : () -> 0L;
        this.averageTickMs = averageTickMs != null ? averageTickMs : () -> 0d;
        this.startNanos = System.nanoTime();
    }

    public static RuntimeMetrics install(IntSupplier modCount,
                                        java.util.function.Supplier<Map<String, Integer>> classCountsByMod,
                                        LongSupplier tickCount,
                                        DoubleSupplier averageTickMs) {
        RuntimeMetrics metrics = new RuntimeMetrics(
                modCount, classCountsByMod, tickCount, averageTickMs);
        MetricsService.registerProvider(metrics);
        return metrics;
    }

    @Override
    public PlatformMetrics snapshot() {
        long now = System.nanoTime();
        PlatformMetrics current = cached;
        if (current != null && now - cachedAtNanos < SNAPSHOT_TTL_NANOS) {
            return current;
        }
        synchronized (this) {
            now = System.nanoTime();
            current = cached;
            if (current != null && now - cachedAtNanos < SNAPSHOT_TTL_NANOS) {
                return current;
            }
            PlatformMetrics fresh = sampleNow();
            cached = fresh;
            cachedAtNanos = now;
            return fresh;
        }
    }

    /** 绕过合并窗口，强制采一次真实值。 */
    public PlatformMetrics sampleNow() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        int liveThreads = threads.getThreadCount();
        int peakThreads = threads.getPeakThreadCount();

        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gc.getCollectionCount();
            long t = gc.getCollectionTime();
            if (c > 0) {
                gcCount += c;
            }
            if (t > 0) {
                gcTime += t;
            }
        }

        long loadedClasses = 0;
        try {
            loadedClasses = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        } catch (RuntimeException ignored) {
            // 某些裁剪过的 JVM 不提供 —— 诊断缺一项不该整体失败
        }

        return new PlatformMetrics(
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                nonHeap.getUsed(),
                liveThreads,
                peakThreads,
                gcCount,
                gcTime,
                uptimeMillis(),
                safeInt(modCount.getAsInt()),
                safeInt(loadedClasses),
                tickCount.getAsLong(),
                averageTickMs.getAsDouble(),
                estimates());
    }

    /** 按估算占用降序排列。 */
    private List<PlatformMetrics.ModMemoryEstimate> estimates() {
        Map<String, Integer> counts = classCountsByMod.get();
        if (counts == null || counts.isEmpty()) {
            return List.of();
        }
        List<PlatformMetrics.ModMemoryEstimate> out = new ArrayList<>(counts.size());
        Map<String, PlatformMetrics.ModMemoryEstimate> byId = new LinkedHashMap<>();
        counts.forEach((id, n) -> {
            if (id == null) {
                return;
            }
            int classes = n != null ? n : 0;
            byId.put(id, new PlatformMetrics.ModMemoryEstimate(
                    id, classes * BYTES_PER_CLASS_ESTIMATE, classes));
        });
        out.addAll(byId.values());
        out.sort(Comparator
                .comparingLong(PlatformMetrics.ModMemoryEstimate::estimatedBytes)
                .reversed());
        return out;
    }

    private long uptimeMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static int safeInt(long v) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, v));
    }
}
