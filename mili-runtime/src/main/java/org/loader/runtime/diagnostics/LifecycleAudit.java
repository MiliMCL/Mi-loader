package org.loader.runtime.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 生命周期审计环 —— 定长、可落盘、崩溃后仍能读的一小段历史。
 *
 * <h2>为什么是环形缓冲而不是日志</h2>
 * 崩溃现场最缺的不是"全部日志"，而是<b>最后那几十条</b>：崩溃发生在
 * 哪个阶段、前一个动作是什么、当时归因到谁。完整日志要靠刷盘保证，
 * 而崩溃时缓冲区的数据本来就丢了 —— 所以真正可靠的做法是让这一小段
 * 历史尽可能便宜地写出去，而不是指望几十 MB 的日志最后一行是对的。
 *
 * <p>定长的另一个好处：它不会因为某个 Mod 在循环里刷事件而吃光内存。
 * 满了就覆盖最旧的，这是刻意的取舍 —— 最近的历史比完整的历史更有用。
 *
 * <h2>时间用墙钟而不是 nanoTime</h2>
 * 落盘之后要与人看到的日志、玩家的操作时刻对齐，因此必须用
 * {@code System.currentTimeMillis()}。{@code nanoTime} 的原点是任意的，
 * 跨进程没有意义 —— 这一点与 {@link StartupTimeline} 正好相反：
 * 那边测的是时长，这边要的是时刻。
 *
 * <h2>线程安全</h2>
 * 事件来自多个线程（Mod 初始化在主线程、看门狗在自己的线程、关闭在
 * 关闭线程），因此全表加锁。生命周期事件是低频的（每个 Mod 几条），
 * 锁不构成热点；<b>它绝不能被放进 tick 热路径</b>，这是使用约束而非建议。
 */
public final class LifecycleAudit {

    /** 容量。256 条足够覆盖一次失败的启动，又不至于让落盘变成负担。 */
    public static final int CAPACITY = 256;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** 一条审计事件。 */
    public record Event(long epochMillis, String kind, String modId, String detail) {
    }

    private final Event[] ring = new Event[CAPACITY];
    private int writeIndex;
    private int filled;
    private long total;

    /**
     * 记一条事件。
     *
     * @param kind   类别，如 {@code "tick.stall"} / {@code "mod.failed"} / {@code "shutdown"}
     * @param modId  归属；平台级事件传 {@code null}
     * @param detail 细节（异常消息、耗时、阶段名等）
     */
    public void record(String kind, String modId, String detail) {
        Event e = new Event(System.currentTimeMillis(), kind, modId, detail);
        synchronized (this) {
            ring[writeIndex] = e;
            writeIndex = (writeIndex + 1) % CAPACITY;
            if (filled < CAPACITY) {
                filled++;
            }
            total++;
        }
    }

    /** 已记录的事件总数（含被覆盖的）。 */
    public synchronized long totalRecorded() {
        return total;
    }

    /** 当前有效的事件条数。 */
    public synchronized int size() {
        return filled;
    }

    /**
     * 最近 {@code limit} 条，按时间正序。
     */
    public synchronized List<Event> recent(int limit) {
        if (filled == 0) {
            return List.of();
        }
        int count = Math.min(Math.max(0, limit), filled);
        List<Event> out = new ArrayList<>(count);
        int start = (writeIndex - count + CAPACITY * 2) % CAPACITY;
        for (int i = 0; i < count; i++) {
            Event e = ring[(start + i) % CAPACITY];
            if (e != null) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    /** 渲染最近 {@code limit} 条为可读文本。 */
    public String render(int limit) {
        List<Event> events = recent(limit);
        if (events.isEmpty()) {
            return "生命周期审计: 无事件\n";
        }
        StringBuilder out = new StringBuilder(events.size() * 64);
        out.append("生命周期审计 · 最近 ").append(events.size())
                .append(" 条 / 累计 ").append(totalRecorded()).append('\n');
        for (Event e : events) {
            out.append("  ").append(TIME.format(
                            Instant.ofEpochMilli(e.epochMillis()).atZone(ZoneId.systemDefault())))
                    .append("  ").append(padRight(e.kind(), 20));
            if (e.modId() != null && !e.modId().isBlank()) {
                out.append('[').append(e.modId()).append("] ");
            }
            out.append(e.detail() == null ? "" : e.detail()).append('\n');
        }
        return out.toString();
    }

    /**
     * 把最近 {@code limit} 条追加写到文件（用于崩溃墓碑）。
     *
     * <p>刻意用追加而不是覆盖：一个进程可能崩多次，前一次的墓碑不该被
     * 下一次覆盖。文件名由调用方决定，通常带时间戳。
     *
     * @return 写入成功返回 true；失败返回 false（诊断不能影响主流程）
     */
    public boolean dumpTo(Path file, int limit, String header) {
        if (file == null) {
            return false;
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String text = (header != null ? header + "\n" : "") + render(limit);
            Files.writeString(file, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[Mili] 审计落盘失败(" + file + "): " + e);
            return false;
        }
    }

    /** 清空。仅用于测试与热重启。 */
    public synchronized void clear() {
        java.util.Arrays.fill(ring, null);
        writeIndex = 0;
        filled = 0;
        total = 0;
    }

    private static String padRight(String s, int width) {
        if (s == null) {
            s = "";
        }
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
