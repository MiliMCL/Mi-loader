package org.loader.runtime.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关闭期回收审计 —— 用两个快照的差集回答"有没有东西没还回来"。
 *
 * <h2>为什么不是"断言 0 残留"</h2>
 * 进程退出时 JVM 会回收一切，所以关闭期的泄漏<b>不会让玩家看见</b>：
 * 它只在两种情况下有害 —— 开发者模式反复热重启，以及 Mod 被禁用后
 * 仍占着内存。后者的伤害是隐性的：玩家以为关掉了，实际上没有。
 *
 * <p>因此这里<b>只报告，不判定</b>。把"线程数增量必须为 0"写成硬断言
 * 会在真实客户端上大量误报：Minecraft 自己就会在关闭期创建线程，
 * 网络栈、音频设备、资源重载都可能多出几个。误报多了，这条审计就会
 * 被当成噪音关掉 —— 那它连报告的价值都没有了。
 *
 * <h2>为什么基线要早于 Mod 初始化</h2>
 * Mod 初始化是唯一有理由创建常驻线程的阶段（它们应当登记在 Scope 里，
 * 随 Scope 关闭）。把基线取在初始化之前，增量就只反映"Mod 之后新增、
 * 且没被回收"的线程 —— 这正是要查的那一类。基线取在初始化之后，
 * 增量会恒为 0，看起来干净，实际什么都没测。
 *
 * <h2>线程名过滤</h2>
 * 平台自己的看门狗线程是在首个 tick 才启动的，它在基线之后出现，
 * 但属于平台而不是泄漏。用前缀白名单把它排除，而不是靠"它应该已关闭"
 * 来祈祷 —— 看门狗是 daemon 且关闭时不 join，本来就可能还活着。
 */
public final class ShutdownAudit {

    /** 平台自有线程前缀：出现在基线之后也属正常。 */
    private static final List<String> PLATFORM_THREAD_PREFIXES = List.of(
            "mili-watchdog",
            "mili-scheduler",
            "mili-timer"
    );

    /** 两个时刻的线程视图。 */
    public record Snapshot(int threadCount, Set<String> threadNames, long capturedAtMillis) {
    }

    private ShutdownAudit() {
    }

    /** 抓一份当前线程快照。 */
    public static Snapshot capture() {
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        Set<String> names = new LinkedHashSet<>();
        for (Thread t : all.keySet()) {
            names.add(t.getName());
        }
        return new Snapshot(all.size(), Set.copyOf(names), System.currentTimeMillis());
    }

    /**
     * 生成关闭期报告。
     *
     * @param baseline      运行时基线（Mod 初始化之前抓的那份）
     * @param atStop        关闭完成后抓的那份
     * @param classesPerMod 关闭前的每个 Mod 已加载类数（关闭后查不到，必须先取）
     */
    public static String report(Snapshot baseline, Snapshot atStop,
                                Map<String, Integer> classesPerMod) {
        StringBuilder out = new StringBuilder(512);
        out.append("关闭期回收审计\n");

        if (baseline == null || atStop == null) {
            out.append("  基线缺失，无法比较\n");
        } else {
            int delta = atStop.threadCount() - baseline.threadCount();
            out.append("  线程: 基线 ").append(baseline.threadCount())
                    .append(" → 关闭后 ").append(atStop.threadCount())
                    .append("  (Δ").append(delta >= 0 ? "+" : "").append(delta).append(")\n");

            List<String> leaked = new ArrayList<>();
            for (String name : atStop.threadNames()) {
                if (baseline.threadNames().contains(name)) {
                    continue;
                }
                if (isPlatformThread(name)) {
                    continue;
                }
                leaked.add(name);
            }
            if (leaked.isEmpty()) {
                out.append("  新增线程: 无\n");
            } else {
                leaked.sort(Comparator.naturalOrder());
                out.append("  新增线程: ").append(leaked.size()).append(" 个");
                if (delta > 0) {
                    out.append("（线程数同时增长，值得查）");
                }
                out.append('\n');
                int shown = Math.min(leaked.size(), 20);
                for (int i = 0; i < shown; i++) {
                    out.append("    · ").append(leaked.get(i)).append('\n');
                }
                if (leaked.size() > shown) {
                    out.append("    ... 另有 ").append(leaked.size() - shown).append(" 个\n");
                }
                out.append("  说明: 只报告不判定 —— Minecraft 自身也会在关闭期建线程。\n")
                        .append("  若同一 Mod 关闭后线程数持续增长，才是泄漏信号。\n");
            }
        }

        if (classesPerMod != null && !classesPerMod.isEmpty()) {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(classesPerMod.entrySet());
            entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            int total = 0;
            for (Map.Entry<String, Integer> e : entries) {
                total += e.getValue();
            }
            out.append("  已加载类: 合计 ").append(total).append("（关闭前快照）\n");
            int shown = Math.min(entries.size(), 10);
            for (int i = 0; i < shown; i++) {
                Map.Entry<String, Integer> e = entries.get(i);
                out.append("    · ").append(e.getKey()).append("  ").append(e.getValue()).append('\n');
            }
            if (entries.size() > shown) {
                out.append("    ... 另有 ").append(entries.size() - shown).append(" 个 Mod\n");
            }
        }
        return out.toString();
    }

    private static boolean isPlatformThread(String name) {
        for (String prefix : PLATFORM_THREAD_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
