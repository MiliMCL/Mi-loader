package org.loader.runtime.diagnostics;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Tick 看门狗 —— 在"某个 Mod 卡住了主线程"时说出是谁。
 *
 * <h2>它要解决的问题</h2>
 * 未捕获异常有堆栈、有开关，处理起来很直接。真正让玩家抓狂的是另一种：
 * <b>没有异常，游戏就是不响应了</b>。某个 Mod 在 tick 回调里死循环、等锁、
 * 或者同步走了网络，主线程被按在那里，日志里什么都看不见。
 *
 * <p>这时唯一有用的信息是「出事那一刻谁在跑」。{@link #enter} /
 * {@link #exit} 在每次任务执行前后维护这个上下文，看门狗线程在另一侧
 * 检查它持有了多久 —— 超过阈值就把<b>当前归属 + 驱动线程的真实栈</b>
 * 一起吐出去。
 *
 * <h2>为什么不做"超时就杀掉"</h2>
 * {@code Thread.stop} 已废弃且有充分理由：它会在任意指令边界抛
 * {@code ThreadDeath}，把半改完的世界状态留在内存里，比卡住更难排查。
 * 平台能安全做的只有两件事：<b>说清楚是谁</b>，以及在崩溃后<b>下次启动
 * 跳过它</b>。本类只做第一件，第二件需要跨进程的墓碑文件，见
 * {@link LifecycleAudit#dumpTo}。
 *
 * <h2>热路径成本</h2>
 * 每次任务两次 volatile 写（enter 写三个字段、exit 写一个 0）。
 * 一个 tick 里任务数是几十量级，合计不到一微秒 —— 比一次
 * {@code System.nanoTime()} 还便宜，而它换来的是"卡住时能说清是谁"。
 *
 * <h2>为什么 sink 是进程级静态量</h2>
 * 一个进程只有一个 tick 驱动线程（{@code TickEngine} 强制校验），
 * 所以"停滞后往哪报"天然是进程级问题。做成实例注入的话，得把 sink
 * 从 Loader 一路穿到 Integration 里的 TickEngine —— 为了一个日志目的地
 * 增加一条跨模块参数链，不值得。这里用显式安装的静态 sink，
 * 并在 {@link #installSink} 上写清它的生命周期。
 */
public final class TickWatchdog implements AutoCloseable {

    /** 默认停滞阈值：200ms，是 50ms tick 预算的四倍，正常抖动够不到。 */
    public static final long DEFAULT_STALL_NANOS = 200_000_000L;
    /** 检查间隔：100ms。比阈值小一半，保证不会错过刚过线的停滞。 */
    public static final long DEFAULT_CHECK_INTERVAL_MS = 100L;

    private static volatile Consumer<String> sink =
            msg -> System.err.println("[Mili] " + msg);
    private static final AtomicLong PROCESS_STALLS = new AtomicLong(0);

    /**
     * 安装停滞后报告的去处。进程级，启动期调用一次即可。
     *
     * @param consumer 接收完整报告文本（含栈）；传 {@code null} 恢复默认的 stderr
     */
    public static void installSink(Consumer<String> consumer) {
        sink = consumer != null ? consumer : msg -> System.err.println("[Mili] " + msg);
    }

    /** 进程生命周期内检测到的停滞次数。 */
    public static long processStallCount() {
        return PROCESS_STALLS.get();
    }

    private final String name;
    private final long stallNanos;
    private final long checkIntervalMs;

    private volatile String currentMod = ModOwners.UNATTRIBUTED;
    private volatile String currentTask = "";
    private volatile long heldSinceNanos;
    private volatile Thread driverThread;

    private volatile boolean running;
    private volatile Thread watchdog;
    /** 上一次报告时刻；用于对同一次停滞去重，而不是每 100ms 刷一屏。 */
    private volatile long lastReportNanos;

    public TickWatchdog(String name) {
        this(name, DEFAULT_STALL_NANOS, DEFAULT_CHECK_INTERVAL_MS);
    }

    public TickWatchdog(String name, long stallNanos, long checkIntervalMs) {
        this.name = name;
        this.stallNanos = Math.max(1_000_000L, stallNanos);
        this.checkIntervalMs = Math.max(10L, checkIntervalMs);
    }

    // ── 被观测侧 ───────────────────────────────────────────────────────────

    /**
     * 标记"此刻开始执行某个 Mod 的任务"。
     *
     * <p>只在 tick 驱动线程调用。首次调用时记下驱动线程，供停滞时抓栈。
     */
    public void enter(String modId, String taskId) {
        if (driverThread == null) {
            driverThread = Thread.currentThread();
        }
        currentMod = modId != null ? modId : ModOwners.UNATTRIBUTED;
        currentTask = taskId != null ? taskId : "";
        heldSinceNanos = System.nanoTime();
    }

    /** 标记"执行结束"。未配对调用时是幂等的。 */
    public void exit() {
        heldSinceNanos = 0L;
    }

    /** 当前归属（可能为空闲时的占位值）。 */
    public String currentMod() {
        return currentMod;
    }

    /** 当前任务 id。 */
    public String currentTask() {
        return currentTask;
    }

    /** 已持有当前上下文多久（纳秒）；空闲为 0。 */
    public long heldNanos() {
        long since = heldSinceNanos;
        return since == 0L ? 0L : System.nanoTime() - since;
    }

    /** 此刻是否已超过停滞阈值。 */
    public boolean stalled() {
        return heldNanos() > stallNanos;
    }

    // ── 观测侧 ─────────────────────────────────────────────────────────────

    /** 启动看门狗线程。幂等；未启动时所有查询仍然可用。 */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::loop, "mili-watchdog-" + name);
        t.setDaemon(true);
        watchdog = t;
        t.start();
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(checkIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) {
                return;
            }
            inspect();
        }
    }

    /**
     * 检查一次并（必要时）报告。
     *
     * <p>单独暴露成包级方法，让测试可以驱动检查而不依赖 sleep ——
     * 用真实时间等停滞的测试在 CI 上必然不稳。
     */
    void inspect() {
        long since = heldSinceNanos;
        if (since == 0L) {
            lastReportNanos = 0L;
            return;
        }
        long now = System.nanoTime();
        long held = now - since;
        if (held <= stallNanos) {
            lastReportNanos = 0L;
            return;
        }
        long last = lastReportNanos;
        // 同一次停滞：每过一个阈值周期补一条，而不是每 100ms 刷一条。
        if (last != 0L && now - last < stallNanos) {
            return;
        }
        lastReportNanos = now;
        PROCESS_STALLS.incrementAndGet();
        try {
            sink.accept(report(held));
        } catch (RuntimeException ignored) {
            // 报告通道自身出错不得杀死看门狗线程
        }
    }

    private String report(long heldNanos) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("tick 停滞 ").append(String.format(Locale.ROOT, "%.0f", heldNanos / 1_000_000.0))
                .append(" ms —— 归属: ").append(currentMod);
        if (!currentTask.isEmpty()) {
            sb.append(" / 任务: ").append(currentTask);
        }
        sb.append('\n').append("  驱动线程: ");
        Thread t = driverThread;
        sb.append(t != null ? t.getName() : "<未知>").append('\n');

        if (t != null) {
            StackTraceElement[] stack = t.getStackTrace();
            int shown = Math.min(stack.length, 12);
            sb.append("  调用栈（最多 12 帧）:\n");
            for (int i = 0; i < shown; i++) {
                sb.append("    at ").append(stack[i]).append('\n');
            }
            if (stack.length > shown) {
                sb.append("    ... 共 ").append(stack.length).append(" 帧\n");
            }
        }
        sb.append("  说明: 平台不会强制终止该任务（Thread.stop 会留下半改状态）。")
                .append("若该 Mod 反复停滞，请在下次启动时禁用它。");
        return sb.toString();
    }

    /** 一行摘要，供诊断面板 / 报告使用。 */
    public String describe() {
        long held = heldNanos();
        return "watchdog[" + name + "] mod=" + currentMod
                + " task=" + (currentTask.isEmpty() ? "-" : currentTask)
                + " held=" + String.format(Locale.ROOT, "%.1f", held / 1_000_000.0) + "ms"
                + (held > stallNanos ? " STALLED" : "");
    }

    @Override
    public void close() {
        running = false;
        Thread t = watchdog;
        watchdog = null;
        if (t != null) {
            t.interrupt();
            // 不 join：关闭阶段不能为了等一个守护线程而阻塞主线程。
            // t 是 daemon，进程退出时自然结束；running=false 也会让它尽快返回。
        }
        heldSinceNanos = 0L;
    }

    /** 是否已启动。 */
    public boolean isRunning() {
        return running;
    }
}
