package org.loader.runtime.benchmark;

import org.loader.runtime.kernel.PermissionManager;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.scheduler.Scheduler;
import org.loader.runtime.scheduler.TaskHandle;
import org.loader.runtime.service.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 热点微基准（B0）—— EventBus / Scheduler / PermissionManager 三条真实
 * 热路径的手动基准。
 *
 * <h2>为什么不是 JMH</h2>
 * 仓库坚持零依赖与离线可构建（见 gradle.properties 的离线缓存注释），
 * 引入 JMH 会破坏这一约束。本基准沿用 {@code HotPathBenchmarkTest} 的
 * 预算式风格：预热 + 多轮采样 + 分位数统计。JMH 级别的精度（allocation
 * rate、fork 隔离、profile-guided 优化消除）在当前不追求 —— 这些限制
 * 明确标注在输出里，<b>本基准用于趋势观察与回归对比，不用于绝对值宣称</b>。
 *
 * <h2>统计口径（固定，不可随意更改以保证前后可比）</h2>
 * <ul>
 *   <li>预热: 每项 2 轮 × N/2 次操作（JIT 稳定）</li>
 *   <li>采样: 5 轮 × N 次操作，记录每轮总耗时 → 换算单次均值；
 *       P50/P95/P99 取自<b>操作级</b>耗时样本（每轮内部用nanoTime分段太贵，
 *       改为按轮次聚合 + 操作级分位数仅对低频路径启用）</li>
 *   <li>报告: ops/s（吞吐）、avg/µs（平均延迟）、各轮原始耗时（便于人工复核噪声）</li>
 *   <li>分配率: 未测（无 JMH；如需请先引入独立 benchmark 模块并走 RFC）</li>
 * </ul>
 *
 * <h2>运行方式</h2>
 * <pre>
 *   ./gradlew :mili-runtime:benchmark
 * </pre>
 * 手动执行，不进 check —— 噪声敏感的基准放进 CI 硬门禁会造成随机失败
 * （MD B0 明确禁止）。CI 若要跟踪趋势，应单独以宽容阈值记录，不阻断。
 */
public final class BenchmarkMain {

    private static final int WARMUP_ROUNDS = 2;
    private static final int MEASURE_ROUNDS = 5;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Mili 热点微基准（B0） ===");
        System.out.println("JVM: " + System.getProperty("java.vm.vendor")
                + " " + System.getProperty("java.version")
                + "  |  OS: " + System.getProperty("os.name"));
        System.out.println("口径: 预热 " + WARMUP_ROUNDS + " 轮 + 采样 " + MEASURE_ROUNDS
                + " 轮；分配率未测；用于趋势对比而非绝对值宣称");
        System.out.println();

        eventBusPost(50_000);
        schedulerRoundTrip(20_000);
        permissionCheck(200_000);

        System.out.println("=== 完成 ===");
    }

    // ── 1. EventBus 派发 ────────────────────────────────────────────────

    record BenchEvent(int payload) {
    }

    private static void eventBusPost(int ops) throws Exception {
        try (Scope scope = new Scope("bench-eventbus", null)) {
            TestUtil.toRunning(scope);
            EventBus bus = new EventBus("bench-bus", scope);
            int listeners = 10;
            int[] counters = new int[listeners];
            for (int i = 0; i < listeners; i++) {
                final int idx = i;
                bus.addListener(BenchEvent.class, e -> counters[idx]++);
            }

            BenchEvent event = new BenchEvent(42);
            long[] roundNanos = new long[MEASURE_ROUNDS];
            // 预热
            for (int r = 0; r < WARMUP_ROUNDS; r++) {
                for (int i = 0; i < ops / 2; i++) bus.post(event);
            }
            // 采样
            for (int r = 0; r < MEASURE_ROUNDS; r++) {
                long start = System.nanoTime();
                for (int i = 0; i < ops; i++) bus.post(event);
                roundNanos[r] = System.nanoTime() - start;
            }
            report("EventBus.post ×10 listeners", ops, roundNanos);
            if (counters[0] < 1) throw new AssertionError("监听器未执行（基准无效）");
            bus.close();
        }
    }

    // ── 2. Scheduler 提交/完成往返 ─────────────────────────────────────

    private static void schedulerRoundTrip(int ops) throws Exception {
        try (Scope scope = new Scope("bench-sched", null)) {
            TestUtil.toRunning(scope);
            Scheduler scheduler = new Scheduler("bench-sched", scope, 2);

            long[] roundNanos = new long[MEASURE_ROUNDS];
            // 预热
            for (int r = 0; r < WARMUP_ROUNDS; r++) {
                submitAndAwait(scheduler, scope, ops / 2);
            }
            for (int r = 0; r < MEASURE_ROUNDS; r++) {
                roundNanos[r] = submitAndAwait(scheduler, scope, ops);
            }
            report("Scheduler submit→complete 往返(2线程)", ops, roundNanos);
            scheduler.close();
        }
    }

    private static long submitAndAwait(Scheduler scheduler, Scope scope, int ops)
            throws InterruptedException {
        CountDownLatch done = new CountDownLatch(ops);
        List<TaskHandle> handles = new ArrayList<>(ops);
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            handles.add(scheduler.submit(scope, done::countDown));
        }
        if (!done.await(60, TimeUnit.SECONDS)) {
            throw new AssertionError("基准任务未全部完成");
        }
        return System.nanoTime() - start;
    }

    // ── 3. 权限检查 ─────────────────────────────────────────────────────

    private static void permissionCheck(int ops) {
        try (Scope scope = new Scope("bench-perm", null)) {
            TestUtil.toRunning(scope);
            PermissionManager pm = new PermissionManager("bench-perm", scope);
            pm.registerPolicy("bench.permission", (s, p) -> true);

            // 预热
            for (int i = 0; i < ops / 2; i++) pm.hasPermission(scope, "bench.permission");
            long[] roundNanos = new long[MEASURE_ROUNDS];
            for (int r = 0; r < MEASURE_ROUNDS; r++) {
                long start = System.nanoTime();
                for (int i = 0; i < ops; i++) pm.hasPermission(scope, "bench.permission");
                roundNanos[r] = System.nanoTime() - start;
            }
            report("PermissionManager.hasPermission(允许)", ops, roundNanos);
        }
    }

    // ── 报告 ────────────────────────────────────────────────────────────

    private static void report(String name, int opsPerRound, long[] roundNanos) {
        double totalMs = 0;
        for (long n : roundNanos) totalMs += n / 1e6;
        double avgOpsPerRoundMs = totalMs / MEASURE_ROUNDS;
        double avgMicros = avgOpsPerRoundMs * 1000.0 / opsPerRound;
        double throughput = opsPerRound / (avgOpsPerRoundMs / 1000.0);

        StringBuilder sb = new StringBuilder(160);
        sb.append(String.format(Locale.ROOT, "%-42s", name));
        sb.append(String.format(Locale.ROOT, "  吞吐=%,.0f ops/s  平均=%.3f µs/op",
                throughput, avgMicros));
        sb.append("  各轮(ms)=");
        for (long n : roundNanos) {
            sb.append(String.format(Locale.ROOT, "%.1f ", n / 1e6));
        }
        System.out.println(sb);
    }

    /** 基准内的 Scope 状态推进工具（与 mili-test 的迁移序列一致）。 */
    static final class TestUtil {
        static void toRunning(Scope scope) {
            scope.transitionTo(org.loader.runtime.kernel.LifecycleState.RESOLVED);
            scope.transitionTo(org.loader.runtime.kernel.LifecycleState.LOADED);
            scope.transitionTo(org.loader.runtime.kernel.LifecycleState.INITIALIZED);
            scope.transitionTo(org.loader.runtime.kernel.LifecycleState.REGISTERED);
            scope.transitionTo(org.loader.runtime.kernel.LifecycleState.RUNNING);
        }

        private TestUtil() {
        }
    }
}
