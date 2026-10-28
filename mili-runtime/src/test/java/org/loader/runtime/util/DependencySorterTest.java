package org.loader.runtime.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.util.DependencySorter.Node;
import org.loader.runtime.util.DependencySorter.Result;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 依赖排序的回归测试。
 *
 * <p>核心不变式只有三条：
 * <ol>
 *   <li>合法 DAG 里，依赖一定排在依赖者之前；</li>
 *   <li>重复依赖只解析一次；</li>
 *   <li>环一定被识别，并且能报出完整的环链 —— 而不是被当成「已访问」。</li>
 * </ol>
 */
class DependencySorterTest {

    private static Node node(String id, String... deps) {
        return new Node(id, List.of(deps));
    }

    @Test
    @DisplayName("无依赖单节点")
    void singleNodeWithoutDependencies() {
        Result r = DependencySorter.sort(List.of(node("a")), List.of("a"));
        assertEquals(List.of("a"), r.order());
        assertFalse(r.hasCycles());
        assertFalse(r.hasMissing());
    }

    @Test
    @DisplayName("线性依赖：被依赖者在前")
    void linearChain() {
        Result r = DependencySorter.sort(
                List.of(node("c", "b"), node("b", "a"), node("a")),
                List.of("c"));
        assertEquals(List.of("a", "b", "c"), r.order());
    }

    @Test
    @DisplayName("分叉与共享依赖：共享节点只出现一次")
    void sharedDependencyAppearsOnce() {
        Result r = DependencySorter.sort(
                List.of(node("top", "left", "right"), node("left", "base"),
                        node("right", "base"), node("base")),
                List.of("top"));

        assertEquals(List.of("base", "left", "right", "top"), r.order());
    }

    @Test
    @DisplayName("重复声明的依赖不重复解析")
    void duplicateDependencyIsResolvedOnce() {
        Result r = DependencySorter.sort(
                List.of(node("a", "b", "b", "b"), node("b")),
                List.of("a"));
        assertEquals(List.of("b", "a"), r.order());
        assertEquals(1, r.order().stream().filter("b"::equals).count());
    }

    @Test
    @DisplayName("自循环 a -> a")
    void selfCycle() {
        Result r = DependencySorter.sort(List.of(node("a", "a")), List.of("a"));
        assertTrue(r.hasCycles());
        assertEquals(List.of(List.of("a", "a")), r.cycles());
        assertEquals("a -> a", r.describeCycles());
    }

    @Test
    @DisplayName("两节点循环 a -> b -> a，环链完整")
    void twoNodeCycle() {
        Result r = DependencySorter.sort(
                List.of(node("a", "b"), node("b", "a")),
                List.of("a"));
        assertTrue(r.hasCycles());
        assertEquals(List.of(List.of("a", "b", "a")), r.cycles());
    }

    @Test
    @DisplayName("多节点循环 a -> b -> c -> a，且从任意入口都能发现")
    void multiNodeCycleDetectedFromAnyRoot() {
        List<Node> graph = List.of(node("a", "b"), node("b", "c"), node("c", "a"));
        for (String root : List.of("a", "b", "c")) {
            Result r = DependencySorter.sort(graph, List.of(root));
            assertTrue(r.hasCycles(), "从 " + root + " 出发必须发现环");
            assertTrue(r.describeCycles().contains("->"), r.describeCycles());
            assertEquals(4, r.cycles().get(0).size(), "环链应回到起点: " + r.cycles());
        }
    }

    @Test
    @DisplayName("缺失依赖单独报告，不与环混淆")
    void missingDependencyIsReportedSeparately() {
        Result r = DependencySorter.sort(
                List.of(node("a", "ghost")),
                List.of("a"));

        assertEquals(List.of("a"), r.order());
        assertEquals(List.of("ghost"), r.missing());
        assertTrue(r.hasMissing());
        assertFalse(r.hasCycles(), "缺失不是环");
    }

    @Test
    @DisplayName("请求一个根本不存在的 Mod 也算缺失")
    void unknownRootIsMissing() {
        Result r = DependencySorter.sort(List.of(node("a")), List.of("not-installed"));
        assertEquals(List.of("not-installed"), r.missing());
    }

    @Test
    @DisplayName("同一输入多次排序结果稳定")
    void orderingIsDeterministic() {
        List<Node> graph = List.of(
                node("ui", "core", "render"),
                node("render", "core"),
                node("core"),
                node("extra", "core"));
        List<String> first = DependencySorter.sort(graph, List.of("ui", "extra")).order();
        for (int i = 0; i < 20; i++) {
            assertEquals(first, DependencySorter.sort(graph, List.of("ui", "extra")).order());
        }
        assertTrue(first.indexOf("core") < first.indexOf("render"));
        assertTrue(first.indexOf("render") < first.indexOf("ui"));
    }

    @Test
    @DisplayName("环与缺失同时存在时两者都报告")
    void cycleAndMissingTogether() {
        Result r = DependencySorter.sort(
                List.of(node("a", "b", "ghost"), node("b", "a")),
                List.of("a"));
        assertTrue(r.hasCycles());
        assertEquals(List.of("ghost"), r.missing());
    }
}
