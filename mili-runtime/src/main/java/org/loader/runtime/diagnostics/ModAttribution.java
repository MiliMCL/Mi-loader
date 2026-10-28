package org.loader.runtime.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 Mod 归因的执行成本 —— 回答"这 3 毫秒是谁花的"。
 *
 * <h2>为什么这是平台最该有、而同类没有的能力</h2>
 * Fabric 要知道某个 Mod 花了多少 tick 时间，必须在字节码里织探针：
 * 它没有"这次执行属于谁"的信息，只能猜。而 Mili 的每个 tick 任务都经过
 * {@code TickEngine}，归属由 {@code ModClassLoader} 决定 ——
 * <b>归因是隔离机制的副产品，不需要额外插桩。</b>
 *
 * <h2>热路径约束</h2>
 * {@link #record} 在每 tick 的每个任务上调用，因此：
 * <ul>
 *   <li><b>不分配</b>：只有数组下标写与一次 {@code ConcurrentHashMap.get}。
 *       归因本身不能成为被归因的成本。</li>
 *   <li><b>槽位固定</b>：owner → 槽位在首次出现时分配，之后只做查表；
 *       槽位数量上限 {@value #MAX_SLOTS}，超出后全部并入槽位 0。</li>
 *   <li><b>单写者</b>：{@code long[]} 的累加只在 tick 驱动线程发生
 *       （{@code TickEngine} 强制驱动线程唯一）。读方可能在别的线程，
 *       因此读到的数字是近似的 —— 对诊断足够，因为它只用于排序。</li>
 * </ul>
 *
 * <p>槽位 0 保留给"未归因"，永不分配给某个具体 Mod。这样
 * {@link #render} 可以把"平台自身的开销"与"某个 Mod 的开销"分开呈现，
 * 而不是让两者混在一起、看起来像某个 Mod 变慢了。
 */
public final class ModAttribution {

    /** 槽位总数；0 固定给未归因。 */
    public static final int MAX_SLOTS = 256;

    private final String[] owners = new String[MAX_SLOTS];
    private final long[] totalNanos = new long[MAX_SLOTS];
    private final long[] calls = new long[MAX_SLOTS];
    private final long[] peakNanos = new long[MAX_SLOTS];

    private final Map<String, Integer> slotByOwner = new ConcurrentHashMap<>();
    private int nextSlot = 1;

    public ModAttribution() {
        owners[0] = ModOwners.UNATTRIBUTED;
    }

    /**
     * 记一次执行成本。
     *
     * @param owner 归属名（由 {@link ModOwners#idOf(Class)} 得到）
     * @param nanos 本次耗时（纳秒）
     */
    public void record(String owner, long nanos) {
        int slot = slotOf(owner);
        totalNanos[slot] += nanos;
        calls[slot]++;
        if (nanos > peakNanos[slot]) {
            peakNanos[slot] = nanos;
        }
    }

    /** 记一次执行成本，按类型反查归属。 */
    public void record(Class<?> type, long nanos) {
        record(ModOwners.idOf(type), nanos);
    }

    private int slotOf(String owner) {
        if (owner == null) {
            return 0;
        }
        Integer existing = slotByOwner.get(owner);
        if (existing != null) {
            return existing;
        }
        // 未登记的新归属：分配槽位。这里会分配（Integer + 数组写入），
        // 但每个 Mod 一生只发生一次 —— 首次 tick 之后全是查表命中。
        synchronized (this) {
            Integer again = slotByOwner.get(owner);
            if (again != null) {
                return again;
            }
            if (nextSlot >= MAX_SLOTS) {
                // 槽位耗尽：并入未归因。宁可少一个 Mod 的明细，
                // 也不能让数组越界或让平台在启动期因诊断能力而失败。
                return 0;
            }
            int slot = nextSlot++;
            owners[slot] = owner;
            slotByOwner.put(owner, slot);
            return slot;
        }
    }

    /** 已归因的总耗时（纳秒）。 */
    public long totalNanos() {
        long sum = 0L;
        for (long v : totalNanos) {
            sum += v;
        }
        return sum;
    }

    /** 已归因的总次数。 */
    public long totalCalls() {
        long sum = 0L;
        for (long c : calls) {
            sum += c;
        }
        return sum;
    }

    /** 已占用的槽位数（诊断用）。 */
    public int ownerCount() {
        int n = 0;
        for (String owner : owners) {
            if (owner != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * 按总耗时降序取前 {@code limit} 项。
     *
     * <p>返回的是快照，不受后续 {@link #record} 影响。只有真正被记录过的
     * 归属才会出现 —— 未归因如果没有发生过，这里不会补一行 0
     * （补零是渲染层的事，见 {@link #render}）。
     */
    public List<Row> top(int limit) {
        List<Row> rows = sortedRows();
        return limit > 0 && rows.size() > limit
                ? List.copyOf(rows.subList(0, limit))
                : List.copyOf(rows);
    }

    private List<Row> sortedRows() {
        List<Row> rows = new ArrayList<>(MAX_SLOTS);
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (owners[i] == null || calls[i] == 0L) {
                continue;
            }
            rows.add(new Row(owners[i], totalNanos[i], calls[i], peakNanos[i]));
        }
        rows.sort((a, b) -> Long.compare(b.totalNanos(), a.totalNanos()));
        return rows;
    }

    /**
     * 渲染成可读文本。
     *
     * <p>未归因那一行<b>永远显示</b>（哪怕为 0），因为它的存在本身就是
     * 一条结论：说明平台自身的调度开销占比多少。把它隐藏起来，读者会
     * 以为"所有时间都属于 Mod"；而在归因链路断掉的情况下，那正是
     * 最容易得出的错误结论。
     */
    public String render(int limit) {
        long total = totalNanos();
        List<Row> all = sortedRows();

        boolean hasUnattributed = false;
        for (Row r : all) {
            if (r.owner().equals(ModOwners.UNATTRIBUTED)) {
                hasUnattributed = true;
                break;
            }
        }
        if (!hasUnattributed) {
            // 0 行也要有，见方法注释。追加在末尾，不打乱降序。
            all.add(new Row(ModOwners.UNATTRIBUTED, 0L, 0L, 0L));
        }

        int cap = limit <= 0 ? 10 : limit;
        List<Row> shown = all.size() > cap ? all.subList(0, cap) : all;

        StringBuilder out = new StringBuilder(256);
        out.append("Mod 归因 · ").append(all.size()).append(" 项 / ")
                .append(totalCalls()).append(" 次 / ")
                .append(String.format(Locale.ROOT, "%.2f", total / 1_000_000.0))
                .append(" ms\n");

        long covered = 0L;
        for (Row r : shown) {
            covered += r.totalNanos();
            out.append("  ")
                    .append(padLeft(String.format(Locale.ROOT, "%.2f", r.totalNanos() / 1_000_000.0), 8))
                    .append(" ms  ")
                    .append(padLeft(String.format(Locale.ROOT, "%.1f%%",
                            total <= 0 ? 0.0 : r.totalNanos() * 100.0 / total), 6))
                    .append("  ")
                    .append(padRight(r.owner(), 28))
                    .append(r.calls()).append(" 次 / 峰值 ")
                    .append(String.format(Locale.ROOT, "%.2f", r.peakNanos() / 1_000_000.0))
                    .append(" ms\n");
        }
        if (all.size() > shown.size()) {
            out.append("  ")
                    .append(padLeft(String.format(Locale.ROOT, "%.2f",
                            Math.max(0L, total - covered) / 1_000_000.0), 8))
                    .append(" ms  ")
                    .append(padLeft(String.format(Locale.ROOT, "%.1f%%",
                            total <= 0 ? 0.0 : Math.max(0L, total - covered) * 100.0 / total), 6))
                    .append("  （其余 ").append(all.size() - shown.size())
                    .append(" 个归属合计）\n");
        }
        return out.toString();
    }

    /** 清空统计（保留槽位分配，避免槽位泄漏到下一轮）。 */
    public void reset() {
        java.util.Arrays.fill(totalNanos, 0L);
        java.util.Arrays.fill(calls, 0L);
        java.util.Arrays.fill(peakNanos, 0L);
    }

    /** 一行归因结果。 */
    public record Row(String owner, long totalNanos, long calls, long peakNanos) {
    }

    private static String padLeft(String s, int width) {
        int pad = width - s.length();
        return pad <= 0 ? s : " ".repeat(pad) + s;
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        return s + " ".repeat(width - s.length());
    }
}
