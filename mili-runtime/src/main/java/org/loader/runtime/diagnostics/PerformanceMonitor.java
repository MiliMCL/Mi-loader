package org.loader.runtime.diagnostics;

import org.loader.api.diagnostics.PerformanceService;
import org.loader.api.diagnostics.PerformanceSnapshot;
import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.tick.TickContract;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 逐 tick / 逐帧的性能采样器 —— TPS 与延迟分布的唯一数据源。
 *
 * <h2>为什么不用 {@code List<Long>} 存样本</h2>
 * 旧实现（{@code Scheduler} 的百分位采集）用 {@code List<Long>} 装样本：
 * 每个样本一次 {@code Long.valueOf} 装箱，最多 10000 个，再排序。
 * 那是「偶尔查一次」的量级，不是「每 tick 一次」的量级。
 *
 * <p>本类用两个定长 {@code long[]} 环形缓冲：写入是两次数组赋值 + 两次自增，
 * <b>稳态零分配</b>；容量固定，内存上限在构造时就确定（约 34 KiB），
 * 不随游玩时长增长。百分位只在快照时排序，且排序的是复用的 scratch 数组。
 *
 * <h2>为什么用滑动窗口而不是累计平均</h2>
 * 累计平均会把「开局卡了 20 秒」稀释到看不见。窗口（1s/5s/15s）让卡顿
 * 在读数上停留一段时间后自然滚出，与玩家的体感一致。
 *
 * <h2>线程模型</h2>
 * 写入发生在 Minecraft 主线程（tick 引擎 / 客户端 tick），读取可能来自
 * 同一线程（面板）或其他线程（诊断命令）。全部方法 {@code synchronized}
 * 到本对象上：每 tick 一次、每次纳秒级的锁，代价远小于一次对象分配；
 * 换来的是「快照不会读到半个 tick」这种确定性。
 *
 * <h2>什么不算在这里</h2>
 * 堆、GC、线程数属于 {@link RuntimeMetrics}（JMX 采样，4Hz 足够）。
 * 本类刻意不碰 MXBean —— 逐 tick 调用 MXBean 会把观测成本变成被测成本。
 */
public final class PerformanceMonitor implements PerformanceService.Provider, Resource {

    /** tick 环形缓冲容量：600 tick ≈ 30 秒（20 TPS）。 */
    static final int TICK_CAPACITY = 600;
    /** 帧采样容量：客户端每 tick 采一次，600 ≈ 30 秒。 */
    static final int FRAME_CAPACITY = 600;
    /** 百分位窗口：最近 300 tick ≈ 15 秒。 */
    private static final int TICK_PERCENTILE_WINDOW = 300;
    /** 帧率窗口：5 秒（客户端 20Hz 采样 → 100 个样本）。 */
    private static final long FPS_WINDOW_NANOS = 5_000_000_000L;
    /** 帧百分位窗口：最近 300 个样本 ≈ 15 秒。 */
    private static final int FRAME_PERCENTILE_WINDOW = 300;

    private final String id;
    private final Scope owner;
    private final long startedAtNanos = System.nanoTime();
    private final long tickPeriodNanos;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ── tick 环形缓冲 ──────────────────────────────────────────────────────
    private final long[] tickEndNanos = new long[TICK_CAPACITY];
    private final long[] tickDurationNanos = new long[TICK_CAPACITY];
    private int tickWrite;
    private int tickFilled;
    private long totalTicks;

    // ── 帧环形缓冲 ─────────────────────────────────────────────────────────
    private final long[] frameEndNanos = new long[FRAME_CAPACITY];
    private final long[] frameDurationNanos = new long[FRAME_CAPACITY];
    private final int[] frameFps = new int[FRAME_CAPACITY];
    private int frameWrite;
    private int frameFilled;

    // ── 查询用 scratch（构造时分配，查询时复用） ───────────────────────────
    private final long[] tickScratch = new long[TICK_CAPACITY];
    private final long[] frameScratch = new long[FRAME_CAPACITY];
    private final int[] fpsScratch = new int[FRAME_CAPACITY];

    public PerformanceMonitor(String id, Scope owner) {
        this(id, owner, TickContract.DEFAULT_TICK_PERIOD_MS);
    }

    public PerformanceMonitor(String id, Scope owner, long tickPeriodMs) {
        this.id = id;
        this.owner = owner;
        this.tickPeriodNanos = Math.max(1L, tickPeriodMs) * 1_000_000L;
    }

    // ── Resource ───────────────────────────────────────────────────────────

    @Override
    public String id() {
        return id;
    }

    @Override
    public Scope owner() {
        return owner;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            synchronized (this) {
                tickWrite = 0;
                tickFilled = 0;
                frameWrite = 0;
                frameFilled = 0;
            }
        }
    }

    // ── 采样 ───────────────────────────────────────────────────────────────

    /**
     * 记录一次 tick。由 {@code TickEngine} 在 tick 结束时调用。
     *
     * @param startNanos tick 开始时刻（{@link System#nanoTime()}）
     * @param endNanos   tick 结束时刻
     */
    public synchronized void recordTick(long startNanos, long endNanos) {
        if (closed.get()) {
            return;
        }
        long duration = Math.max(0L, endNanos - startNanos);
        tickEndNanos[tickWrite] = endNanos;
        tickDurationNanos[tickWrite] = duration;
        tickWrite = (tickWrite + 1) % TICK_CAPACITY;
        if (tickFilled < TICK_CAPACITY) {
            tickFilled++;
        }
        totalTicks++;
    }

    /**
     * 记录一次帧采样。由客户端 tick 钩子调用（约 20Hz）。
     *
     * @param fps              原版每秒帧数（1 秒平滑值）；未知传 {@code -1}
     * @param frameDurationNs  上一帧耗时（纳秒）；未知传 0
     */
    public synchronized void recordFrame(int fps, long frameDurationNs) {
        // fps <= 0 是「还没有帧」而非「0 帧」：启动首秒原版就是 0。
        // 把它记进窗口会让「5s 最低帧率」出现一个假的 0。
        if (closed.get() || fps <= 0) {
            return;
        }
        frameEndNanos[frameWrite] = System.nanoTime();
        frameDurationNanos[frameWrite] = Math.max(0L, frameDurationNs);
        frameFps[frameWrite] = fps;
        frameWrite = (frameWrite + 1) % FRAME_CAPACITY;
        if (frameFilled < FRAME_CAPACITY) {
            frameFilled++;
        }
    }

    /** 清空窗口与计数 —— 用于「从此刻开始重新观察」的验收场景。 */
    public synchronized void reset() {
        tickWrite = 0;
        tickFilled = 0;
        totalTicks = 0;
        frameWrite = 0;
        frameFilled = 0;
    }

    // ── 查询 ───────────────────────────────────────────────────────────────

    /** 生命周期内累计 tick 数。 */
    public synchronized long tickCount() {
        return totalTicks;
    }

    /** 最近 300 tick 的平均耗时（毫秒）；无样本时为 0。 */
    public synchronized double averageTickMs() {
        int n = copyRecentTicks(TICK_PERCENTILE_WINDOW);
        if (n == 0) {
            return 0.0;
        }
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += tickScratch[i];
        }
        return sum / (double) n / 1_000_000.0;
    }

    @Override
    public synchronized PerformanceSnapshot snapshot() {
        if (closed.get()) {
            return PerformanceSnapshot.empty();
        }
        long now = System.nanoTime();

        int n = copyRecentTicks(TICK_PERCENTILE_WINDOW);
        long sumNanos = 0;
        long maxNanos = 0;
        long missed = 0;
        for (int i = 0; i < n; i++) {
            long v = tickScratch[i];
            sumNanos += v;
            if (v > maxNanos) {
                maxNanos = v;
            }
            if (v > tickPeriodNanos) {
                missed++;
            }
        }
        double avgMs = n == 0 ? 0 : sumNanos / (double) n / 1_000_000.0;
        double maxMs = maxNanos / 1_000_000.0;
        double p50 = 0;
        double p95 = 0;
        double p99 = 0;
        if (n > 0) {
            // 只排一次序，三个百分位共用 —— 排序会破坏顺序，但上面
            // 的均值/峰值已经在排序前算完。
            Arrays.sort(tickScratch, 0, n);
            p50 = tickScratch[percentileIndex(n, 50)] / 1_000_000.0;
            p95 = tickScratch[percentileIndex(n, 95)] / 1_000_000.0;
            p99 = tickScratch[percentileIndex(n, 99)] / 1_000_000.0;
        }

        long lastTickEnd = tickFilled == 0 ? -1 : tickEndNanos[lastIndex(tickWrite, TICK_CAPACITY)];
        long ageMs = lastTickEnd < 0 ? -1 : Math.max(0L, (now - lastTickEnd) / 1_000_000L);

        int lastFps = -1;
        if (frameFilled > 0) {
            lastFps = frameFps[lastIndex(frameWrite, FRAME_CAPACITY)];
        }
        int frameWindow = copyRecentFramesInWindow(FPS_WINDOW_NANOS, now);
        int minFps = -1;
        double avgFps = 0;
        long fpsSum = 0;
        for (int i = 0; i < frameWindow; i++) {
            int f = fpsScratch[i];
            fpsSum += f;
            if (minFps < 0 || f < minFps) {
                minFps = f;
            }
        }
        if (frameWindow > 0) {
            avgFps = fpsSum / (double) frameWindow;
        }
        int pFrameCount = copyRecentFrames(FRAME_PERCENTILE_WINDOW);
        double p95FrameMs = 0;
        if (pFrameCount > 0) {
            Arrays.sort(frameScratch, 0, pFrameCount);
            p95FrameMs = frameScratch[percentileIndex(pFrameCount, 95)] / 1_000_000.0;
        }

        return new PerformanceSnapshot(
                totalTicks,
                tickFilled,
                ageMs,
                tps(1_000_000_000L, now),
                tps(5_000_000_000L, now),
                tps(15_000_000_000L, now),
                avgMs,
                p50,
                p95,
                p99,
                maxMs,
                missed,
                lastFps,
                minFps,
                avgFps,
                p95FrameMs,
                frameFilled,
                (now - startedAtNanos) / 1_000_000L);
    }

    // ── 内部计算（调用方已持有本对象锁） ────────────────────────────────────

    /** 把最近 {@code max} 个 tick 耗时按时间顺序复制进 scratch，返回个数。 */
    private int copyRecentTicks(int max) {
        int n = Math.min(tickFilled, Math.min(max, TICK_CAPACITY));
        for (int i = 0; i < n; i++) {
            int idx = tickWrite - n + i;
            if (idx < 0) {
                idx += TICK_CAPACITY;
            }
            tickScratch[i] = tickDurationNanos[idx];
        }
        return n;
    }

    /**
     * 把时间窗口内的帧样本复制进 scratch，返回个数。
     *
     * @param windowNanos 时间窗口
     */
    private int copyRecentFramesInWindow(long windowNanos, long now) {
        int n = Math.min(frameFilled, FRAME_CAPACITY);
        int count = 0;
        long cutoff = now - windowNanos;
        for (int i = 0; i < n; i++) {
            int idx = frameWrite - 1 - i;
            if (idx < 0) {
                idx += FRAME_CAPACITY;
            }
            if (frameEndNanos[idx] < cutoff) {
                break;
            }
            frameScratch[count] = frameDurationNanos[idx];
            fpsScratch[count] = frameFps[idx];
            count++;
        }
        return count;
    }

    /** 把最近 {@code maxCount} 个帧耗时复制进 frameScratch，返回个数。 */
    private int copyRecentFrames(int maxCount) {
        int n = Math.min(frameFilled, Math.min(maxCount, FRAME_CAPACITY));
        for (int i = 0; i < n; i++) {
            int idx = frameWrite - n + i;
            if (idx < 0) {
                idx += FRAME_CAPACITY;
            }
            frameScratch[i] = frameDurationNanos[idx];
        }
        return n;
    }

    /** 窗口内的实际 TPS。 */
    private double tps(long windowNanos, long now) {
        int n = tickFilled;
        if (n < 2) {
            return 0.0;
        }
        long cutoff = now - windowNanos;
        int count = 0;
        long oldest = 0;
        for (int i = 0; i < n; i++) {
            int idx = tickWrite - 1 - i;
            if (idx < 0) {
                idx += TICK_CAPACITY;
            }
            long t = tickEndNanos[idx];
            if (t < cutoff) {
                break;
            }
            oldest = t;
            count++;
        }
        if (count < 2) {
            return 0.0;
        }
        long span = now - oldest;
        if (span <= 0) {
            return 0.0;
        }
        double value = count * 1_000_000_000.0 / span;
        // 上限只防除零导致的荒谬读数，不参与「是否达标」的判断
        return Math.min(value, 1_000.0);
    }

    /** 已排序的 scratch 中取百分位（纳秒）。 */    private static int percentileIndex(int n, double p) {
        int idx = (int) Math.ceil(p / 100.0 * n) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= n) {
            idx = n - 1;
        }
        return idx;
    }

    private static int lastIndex(int write, int capacity) {
        int idx = write - 1;
        return idx < 0 ? capacity - 1 : idx;
    }

    @Override
    public String toString() {
        return "PerformanceMonitor[" + id + ", ticks=" + tickCount() + "]";
    }
}
