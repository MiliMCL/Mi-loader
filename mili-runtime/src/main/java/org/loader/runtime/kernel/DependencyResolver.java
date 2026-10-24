package org.loader.runtime.kernel;

import org.loader.runtime.util.DependencySorter;

import java.util.*;

/**
 * Resolves dependency graphs between Mods.
 * <p>
 * Dependencies are represented separately from ownership.
 *
 * <p><b>与其它入口共享同一套算法</b>：排序委托给
 * {@link DependencySorter}（三态 DFS）。本类额外保留「插入期就拒绝成环」
 * 的快速失败（{@link #addDependency}），但即使有人绕过它构造出环，
 * {@link #resolveInitializationOrder()} 也会发现并拒绝，且错误信息包含
 * 完整的环链（{@code A -> B -> C -> A}），而不只是一个节点名。
 */
public class DependencyResolver {

    private final Map<String, Node> nodes = new LinkedHashMap<>();

    /**
     * Registers a node in the dependency graph.
     */
    public void registerNode(String id) {
        nodes.computeIfAbsent(id, Node::new);
    }

    /**
     * Adds a dependency: {@code dependent} depends on {@code dependency}.
     */
    public void addDependency(String dependent, String dependency) {
        Node depNode = nodes.get(dependent);
        Node reqNode = nodes.get(dependency);
        if (depNode == null || reqNode == null) {
            throw new IllegalArgumentException("Unknown nodes: " + dependent + " -> " + dependency);
        }
        if (wouldCreateCycle(depNode, reqNode)) {
            throw new IllegalStateException(
                    "Dependency would create cycle: " + dependent + " -> " + dependency);
        }
        depNode.addDependency(reqNode);
    }

    /**
     * Returns the initialization order (topological sort).
     *
     * @throws IllegalStateException 若图中存在环；消息包含完整环链
     */
    public List<String> resolveInitializationOrder() {
        List<DependencySorter.Node> graph = new ArrayList<>(nodes.size());
        for (Node node : nodes.values()) {
            List<String> dependencies = new ArrayList<>(node.dependencies.size());
            for (Node dep : node.dependencies) {
                dependencies.add(dep.id);
            }
            graph.add(new DependencySorter.Node(node.id, dependencies));
        }

        DependencySorter.Result result = DependencySorter.sort(graph, nodes.keySet());
        if (result.hasCycles()) {
            throw new IllegalStateException("Cycle detected: " + result.describeCycles());
        }
        return Collections.unmodifiableList(new ArrayList<>(result.order()));
    }

    /**
     * Returns the shutdown order (reverse of initialization).
     */
    public List<String> resolveShutdownOrder() {
        List<String> order = new ArrayList<>(resolveInitializationOrder());
        Collections.reverse(order);
        return order;
    }

    private boolean wouldCreateCycle(Node from, Node to) {
        // If 'to' can reach 'from', adding from->to would create a cycle
        Set<Node> visited = new HashSet<>();
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(to);
        while (!stack.isEmpty()) {
            Node current = stack.pop();
            if (current == from) {
                return true;
            }
            if (visited.add(current)) {
                stack.addAll(current.dependencies);
            }
        }
        return false;
    }

    /**
     * Internal node representation.
     */
    private static class Node {
        final String id;
        final List<Node> dependencies = new ArrayList<>();

        Node(String id) {
            this.id = id;
        }

        void addDependency(Node dep) {
            dependencies.add(dep);
        }
    }
}
