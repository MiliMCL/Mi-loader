package org.loader.runtime.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 启动阶段计时器 —— 为"启动到底慢在哪"提供第一手数据。
 *
 * <h2>为什么需要它，而不是复用 ProfilerHooks</h2>
 * {@link org.loader.runtime.observability.ProfilerHooks} 是<b>通用</b>剖析器：
 * 以字符串为键存进 {@code LinkedHashMap}，每次 {@code startTiming} 都新建一个
 * {@code TimingEntry}。那套设计服务于"少量、低频、随时可开关"的临时打点，
 * 用在启动路径上有两个问题：
 * <ul>
 *   <li>它只能记录<b>一段</b>的耗时，无法表达"总耗时里有多少没被覆盖"——
 *       而启动优化最先要回答的恰恰是后者：不知道漏了什么，就永远在优化
 *       自己已经量过的那部分。</li>
 *   <li>它不知道嵌套关系。{@code initializeMods} 里有 200 个 Mod 各花多少，
 *       是启动优化最核心的分布问题，用平坦的 map 表达不出来。</li>
 * </ul>
 *
 * <p>因此本类是专用的：<b>栈式嵌套 + 未归因时间 + 按耗时排序的兄弟聚合</b>。
 * 它不追求通用，只追求把启动这件事说清楚。
 *
 * <h2>两种记录方式</h2>
 * <ul>
 *   <li>{@link #mark(String)} —— 平坦打点：从<b>上一个边界</b>到现在记为一段。
 *       适合 {@code launch()} 这种线性流程，插一行就能用，不必改动控制流。</li>
 *   <li>{@link #open(String)} / {@link #close()} —— 嵌套区间：表达父子关系。
 *       用于 {@code invokeModEntrypoints()} 这类需要展开分布的循环。</li>
 * </ul>
 *
 * <h2>为什么是"开区间时就建节点"</h2>
 * 区间按<b>关闭顺序</b>记录，子一定先于父出现。若事后靠 depth 反推父子，
 * 遇到"父尚未闭合、子已记录"的情形（{@code initializeMods} 下的逐 Mod 打点
 * 正是这种）就会把子错认成父的兄弟。所以节点在 {@code open} 时创建并直接
 * 挂到父节点的 children 上 —— 父子关系不靠推断，靠引用。
 *
 * <h2>线程与分配</h2>
 * 单线程使用，不做同步 —— 启动路径在调用 Minecraft main 之前不产生并发
 * Mod 工作。每段一个节点对象，启动期几十到几百个，不在热路径上。
 *
 * <h2>与预算的关系</h2>
 * 本类只负责<b>测量</b>，不判定合格与否。判定阈值见
 * {@code docs/performance/LIFECYCLE_BUDGET.md} —— 那里目前大多标着 TBD，
 * 正是因为在这套打点跑出真实数据之前，任何阈值都是猜的。
 */
public final class StartupTimeline {

    /** 兄弟节点超过这个数量时，只展示耗时最长的若干个，其余聚合为一行。 */
    public static final int DEFAULT_CHILD_LIMIT = 8;

    /**
     * 一段已完成的计时。
     *
     * <p>只有 name / duration / depth 三个字段 —— 刻意不含开始时刻：
     * 调用方要的是"多长、在第几层"，而绝对时刻依赖 {@code nanoTime} 的
     * 任意原点，跨进程没有可比性，暴露它只会诱导错误比较。
     */
    public record Phase(String name, long durationNanos, int depth) {
    }

    private static final class Node {
        final String name;
        final int depth;
        final long startNanos;
        final List<Node> children = new ArrayList<>(4);
        long durationNanos;

        Node(String name, int depth, long startNanos) {
            this.name = name;
            this.depth = depth;
            this.startNanos = startNanos;
            this.durationNanos = -1L;
        }
    }

    private final String label;
    private final long originNanos;
    private final Node root;
    private final Deque<Node> openNodes = new ArrayDeque<>();
    /** 关闭顺序（后序），仅供 {@link #phases()} 使用。 */
    private final List<Phase> phases = new ArrayList<>();

    /** 上一个区间的结束时刻；{@link #mark(String)} 从它算起。 */
    private long lastBoundaryNanos;

    public StartupTimeline(String label) {
        this.label = Objects.requireNonNull(label, "label");
        this.originNanos = System.nanoTime();
        this.lastBoundaryNanos = originNanos;
        this.root = new Node(label, -1, originNanos);
    }

    /**
     * 开启一段嵌套区间。
     *
     * <p>起点取<b>当前时刻</b>而非 {@code lastBoundary}：上一层已经覆盖了
     * 那段空隙，重复计入会让父子的耗时相加超过总时长。
     */
    public void open(String name) {
        Node parent = current();
        Node node = new Node(Objects.requireNonNull(name, "name"),
                parent.depth + 1, System.nanoTime());
        parent.children.add(node);
        openNodes.push(node);
    }

    /**
     * 结束最近一段嵌套区间。
     *
     * @return 该段耗时（纳秒）；没有未闭合区间时返回 {@code -1}
     */
    public long close() {
        Node node = openNodes.poll();
        if (node == null) {
            return -1L;
        }
        long now = System.nanoTime();
        node.durationNanos = now - node.startNanos;
        phases.add(new Phase(node.name, node.durationNanos, node.depth));
        lastBoundaryNanos = now;
        return node.durationNanos;
    }

    /**
     * 关闭所有未闭合区间，由内向外。
     *
     * <p>启动中途抛异常时调用：已经采集到的数据不应因为一次失败而丢失，
     * 失败路径的时间线往往比成功路径更有诊断价值。
     */
    public void closeAll() {
        while (!openNodes.isEmpty()) {
            close();
        }
    }

    /**
     * 平坦打点：把"上一个边界 → 现在"记为名为 {@code name} 的一段。
     *
     * <p>这是为线性启动流程准备的：在每一步之后插一行，不改变控制流，
     * 也不需要 try/finally 配对。
     */
    public void mark(String name) {
        mark(name, lastBoundaryNanos);
    }

    /**
     * 平坦打点，指定起点。
     *
     * <p>用于两种情况：在时间线对象创建之前就已开始的工作（如解析启动参数），
     * 以及"成功路径才记一笔"的逐 Mod 初始化 —— 后者的起点必须显式给出，
     * 因为这一段不构成嵌套区间。
     */
    public void mark(String name, long startNanos) {
        long now = System.nanoTime();
        Node parent = current();
        Node node = new Node(Objects.requireNonNull(name, "name"),
                parent.depth + 1, startNanos);
        node.durationNanos = now - startNanos;
        parent.children.add(node);
        phases.add(new Phase(node.name, node.durationNanos, node.depth));
        lastBoundaryNanos = now;
    }

    /** 从创建到现在（或最后一次边界）的总耗时。 */
    public long totalNanos() {
        return System.nanoTime() - originNanos;
    }

    /** 未被任何顶层区间覆盖的时间。用于发现"漏打点"。 */
    public long unaccountedNanos() {
        long accounted = 0L;
        for (Node child : root.children) {
            accounted += Math.max(0L, child.durationNanos);
        }
        return Math.max(0L, System.nanoTime() - originNanos - accounted);
    }

    /** 全部已记录区间，按关闭顺序（后序）。 */
    public List<Phase> phases() {
        return Collections.unmodifiableList(new ArrayList<>(phases));
    }

    /**
     * 渲染成可读文本。
     *
     * <p>排版规则有明确理由，不是随手排的：
     * <ul>
     *   <li><b>兄弟数量不多时按时间顺序</b>——顶层是启动步骤，它们的
     *       <b>顺序</b>本身就是信息（谁在谁之前）。按耗时排序会破坏它。</li>
     *   <li><b>兄弟过多时按耗时降序 + 聚合</b>——那说明是扇出（每个 Mod 的
     *       初始化），顺序无意义，只关心谁最贵。超过 {@value #DEFAULT_CHILD_LIMIT}
     *       个时其余合并成一行，避免 200 个 Mod 刷屏。</li>
     * </ul>
     */
    public String render() {
        return render(DEFAULT_CHILD_LIMIT);
    }

    public String render(int childLimit) {
        long total = totalNanos();
        StringBuilder out = new StringBuilder(512);
        out.append(label).append(" · 总 ").append(formatMs(total)).append(" ms\n");

        long accounted = 0L;
        for (Node child : root.children) {
            long d = Math.max(0L, child.durationNanos);
            accounted += d;
            appendNode(out, child, total, 1, childLimit);
        }

        long unaccounted = total - accounted;
        if (unaccounted > 0L) {
            appendRow(out, 1, unaccounted, total, "未归因");
        }
        return out.toString();
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private Node current() {
        Node top = openNodes.peek();
        return top != null ? top : root;
    }

    private void appendNode(StringBuilder out, Node node, long parentDuration,
                            int indent, int childLimit) {
        appendRow(out, indent, Math.max(0L, node.durationNanos), parentDuration, node.name);

        if (node.children.isEmpty()) {
            return;
        }

        List<Node> ordered = node.children;
        long skippedNanos = 0L;
        int shown = ordered.size();

        if (childLimit > 0 && ordered.size() > childLimit) {
            List<Node> sorted = new ArrayList<>(ordered);
            // 显式写比较器而不是 comparingLong(...)：后者的类型推断在
            // "Comparator<? super Node> + 显式参数 lambda" 组合下容易失败，
            // 而这里只需要一个降序排列，直接比更清楚。
            sorted.sort((a, b) -> Long.compare(b.durationNanos, a.durationNanos));
            ordered = sorted;
            shown = childLimit;
            for (int i = childLimit; i < sorted.size(); i++) {
                skippedNanos += Math.max(0L, sorted.get(i).durationNanos);
            }
        }

        for (int i = 0; i < shown; i++) {
            appendNode(out, ordered.get(i), Math.max(0L, node.durationNanos),
                    indent + 1, childLimit);
        }
        // 只要发生了截断就必须留一行痕迹 —— 判定条件是"有没有被截断"，
        // 而不是"被截断的耗时可不可忽略"。用后者会让一堆 0.0 ms 的子项
        // 静默消失，读的人以为时间线就这么长。
        if (ordered.size() > shown) {
            appendRow(out, indent + 1, skippedNanos,
                    Math.max(0L, node.durationNanos),
                    "（" + (ordered.size() - shown) + " 个更短的阶段合计）");
        }
    }

    private static void appendRow(StringBuilder out, int indent, long nanos,
                                  long parentDuration, String name) {
        out.append("  ".repeat(indent));
        out.append(padLeft(formatMs(nanos), 9)).append(" ms  ");
        double pct = parentDuration <= 0L ? 0.0 : nanos * 100.0 / parentDuration;
        out.append(padLeft(String.format(Locale.ROOT, "%.1f%%", pct), 6));
        out.append("  ").append(name).append('\n');
    }

    private static String formatMs(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    private static String padLeft(String s, int width) {
        int pad = width - s.length();
        return pad <= 0 ? s : " ".repeat(pad) + s;
    }
}
