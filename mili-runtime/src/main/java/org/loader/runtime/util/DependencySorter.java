package org.loader.runtime.util;

import java.util.*;

/**
 * 依赖图的确定性拓扑排序（三态 DFS）。
 *
 * <h2>为什么需要单一实现</h2>
 *
 * <p>仓库里曾有三处各自独立的依赖解析：
 * <ul>
 *   <li>{@code instance/ModManager#resolveRecursive} —— 只有 {@code visited}，
 *       把「环」当成「已访问」，于是 {@code A → B → A} 会被判为解析成功，
 *       并以任意顺序进入加载流程；</li>
 *   <li>{@code kernel/DependencyResolver} —— 有 {@code inProgress}，能发现环，
 *       但错误信息只有一个节点名，看不出环的形状；</li>
 *   <li>{@code loader/discovery/ModDiscovery#resolveRecursive} —— 又是
 *       {@code visited}，环同样静默通过。</li>
 * </ul>
 * 三套实现的行为不一致，等于「环会不会被拦住」取决于 Mod 从哪个入口进来。
 * 这里收敛成一套算法，三处调用方各自决定「发现环之后怎么办」
 * （返回失败结果 / 抛异常），但<b>发现环这件事本身</b>只有一种语义。
 *
 * <h2>三态而不是两态</h2>
 *
 * <p>两态（访问过/没访问过）无法区分「这个节点正在我当前的递归栈上」和
 * 「这个节点早就解析完了」。前者是环，后者是合法的重复依赖。
 * 三态 DFS 用 {@link State#IN_PROGRESS} 精确表达前者，因此
 * <b>重复依赖只解析一次，环必然被发现</b>。
 *
 * <p>顺序是后序（依赖先于依赖者），同一层按声明顺序，因此对同一输入
 * 输出稳定可复现。
 */
public final class DependencySorter {

    private DependencySorter() {
    }

    /** 图中的一个节点：id 与它依赖的 id 列表。 */
    public record Node(String id, List<String> dependencies) {
        public Node {
            Objects.requireNonNull(id, "node id");
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    /**
     * 排序结果。
     *
     * @param order  拓扑序（依赖在前）
     * @param missing 图中不存在、因而无法解析的 id（去重、保序）
     * @param cycles 检出的环，每个环形如 {@code [A, B, C, A]}
     */
    public record Result(List<String> order, List<String> missing, List<List<String>> cycles) {
        public Result {
            order = List.copyOf(order);
            missing = List.copyOf(missing);
            cycles = cycles.stream().map(List::copyOf).toList();
        }

        public boolean hasCycles() {
            return !cycles.isEmpty();
        }

        public boolean hasMissing() {
            return !missing.isEmpty();
        }

        /** 人类可读的环描述：{@code A -> B -> C -> A}。 */
        public String describeCycles() {
            StringBuilder sb = new StringBuilder();
            for (List<String> cycle : cycles) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(String.join(" -> ", cycle));
            }
            return sb.toString();
        }
    }

    private enum State {
        IN_PROGRESS,
        DONE
    }

    /**
     * 从 {@code roots} 出发排序。
     *
     * @param nodes 全图节点（id 重复时以首次出现为准）
     * @param roots 入口节点，按调用方希望的顺序（结果为后序，故依赖先出现）
     */
    public static Result sort(List<Node> nodes, Collection<String> roots) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (Node node : nodes) {
            edges.putIfAbsent(node.id(), node.dependencies());
        }

        List<String> order = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        List<List<String>> cycles = new ArrayList<>();
        Map<String, State> state = new HashMap<>();
        List<String> path = new ArrayList<>();

        for (String root : roots) {
            visit(root, edges, state, order, missing, cycles, path);
        }
        return new Result(order, List.copyOf(missing), cycles);
    }

    private static void visit(String id,
                              Map<String, List<String>> edges,
                              Map<String, State> state,
                              List<String> order,
                              Set<String> missing,
                              List<List<String>> cycles,
                              List<String> path) {
        State current = state.get(id);
        if (current == State.DONE) {
            // 合法重复依赖：已经排过序，直接复用
            return;
        }
        if (current == State.IN_PROGRESS) {
            // 回到当前递归栈上的节点 = 环。截出环链，便于直接定位。
            int at = path.indexOf(id);
            List<String> cycle = new ArrayList<>(at >= 0 ? path.subList(at, path.size()) : path);
            cycle.add(id);
            cycles.add(cycle);
            return;
        }

        List<String> children = edges.get(id);
        if (children == null) {
            // 图中没有这个 id：可能是「请求了不存在的 Mod」，也可能是
            // 「依赖声明指向不存在的 Mod」。两种都记为 missing，
            // 由调用方决定是失败还是容忍（Loader 对可选依赖容忍）。
            missing.add(id);
            return;
        }

        state.put(id, State.IN_PROGRESS);
        path.add(id);
        try {
            for (String child : children) {
                visit(child, edges, state, order, missing, cycles, path);
            }
        } finally {
            path.remove(path.size() - 1);
        }
        state.put(id, State.DONE);
        order.add(id);
    }
}
