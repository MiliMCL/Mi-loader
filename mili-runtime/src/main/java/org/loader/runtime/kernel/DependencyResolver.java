package org.loader.runtime.kernel;

import java.util.*;

/**
 * Resolves dependency graphs between Mods.
 * <p>
 * Dependencies are represented separately from ownership.
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
     */
    public List<String> resolveInitializationOrder() {
        List<String> result = new ArrayList<>();
        Set<Node> visited = new HashSet<>();
        Set<Node> inProgress = new HashSet<>();

        for (Node node : nodes.values()) {
            visit(node, visited, inProgress, result);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns the shutdown order (reverse of initialization).
     */
    public List<String> resolveShutdownOrder() {
        List<String> order = new ArrayList<>(resolveInitializationOrder());
        Collections.reverse(order);
        return order;
    }

    private void visit(Node node, Set<Node> visited, Set<Node> inProgress, List<String> result) {
        if (visited.contains(node)) {
            return;
        }
        if (inProgress.contains(node)) {
            throw new IllegalStateException("Cycle detected at node: " + node.id);
        }
        inProgress.add(node);
        for (Node dep : node.dependencies) {
            visit(dep, visited, inProgress, result);
        }
        inProgress.remove(node);
        visited.add(node);
        result.add(node.id);
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
