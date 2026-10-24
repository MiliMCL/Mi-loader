package org.loader.loader.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.error.ModLoadError;
import org.loader.runtime.mod.ModManifest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ModDiscovery#resolveDependencies} 的顺序与循环语义。
 *
 * <p>这些用例不需要真实的 jar：直接构造 {@link ModManifest} 调用解析方法。
 * 循环依赖必须在加载前被拒绝 —— 一旦以错误顺序初始化，Mod 之间的相互引用
 * 已经发生，事后无法补救。
 */
class ModDiscoveryDependencyTest {

    private static ModManifest mod(String id, String... requiredDeps) {
        List<ModManifest.DependencyEntry> deps = java.util.Arrays.stream(requiredDeps)
                .map(d -> new ModManifest.DependencyEntry(d, "*", true))
                .toList();
        return new ModManifest(id, id, "1.0", "", "", deps, List.of(), "", List.of(),
                ModManifest.VersionBinding.unbound());
    }

    private static ModDiscovery discovery() {
        // config 只在 scan() 中使用；这里直接测依赖排序，不需要目录。
        return new ModDiscovery(null);
    }

    @Test
    @DisplayName("线性依赖：被依赖者先出现")
    void linearOrder() {
        List<ModManifest> resolved = discovery().resolveDependencies(List.of(
                mod("c", "b"), mod("b", "a"), mod("a")));

        assertEquals(List.of("a", "b", "c"),
                resolved.stream().map(ModManifest::id).toList());
    }

    @Test
    @DisplayName("重复依赖只解析一次")
    void sharedDependencyOnce() {
        List<ModManifest> resolved = discovery().resolveDependencies(List.of(
                mod("top", "left", "right"), mod("left", "base"),
                mod("right", "base"), mod("base")));

        assertEquals(List.of("base", "left", "right", "top"),
                resolved.stream().map(ModManifest::id).toList());
    }

    @Test
    @DisplayName("两节点循环 → 抛 MOD_DEPENDENCY_CYCLE，消息含完整环链")
    void twoNodeCycleIsRejected() {
        ModLoadError error = assertThrows(ModLoadError.class, () ->
                discovery().resolveDependencies(List.of(mod("a", "b"), mod("b", "a"))));

        assertEquals("MOD_DEPENDENCY_CYCLE", error.errorCode());
        assertTrue(error.getMessage().contains("a -> b -> a"), error.getMessage());
    }

    @Test
    @DisplayName("三节点循环 → 拒绝，且环链首尾闭合")
    void threeNodeCycleIsRejected() {
        ModLoadError error = assertThrows(ModLoadError.class, () ->
                discovery().resolveDependencies(List.of(
                        mod("a", "b"), mod("b", "c"), mod("c", "a"))));

        String message = error.getMessage();
        assertTrue(message.contains("依赖循环"), message);
        assertTrue(message.matches("(?s).*[abc] -> [abc] -> [abc].*"), message);
    }

    @Test
    @DisplayName("缺失依赖不阻断加载，但会被记录")
    void missingDependencyIsToleratedAndRecorded() {
        ModDiscovery discovery = discovery();
        List<ModManifest> resolved = discovery.resolveDependencies(List.of(mod("a", "ghost")));

        assertEquals(List.of("a"), resolved.stream().map(ModManifest::id).toList());
        assertEquals(List.of("ghost"), discovery.missingDependencies());
    }

    @Test
    @DisplayName("可选依赖不参与排序，也不计入缺失")
    void optionalDependencyIgnored() {
        ModManifest withOptional = new ModManifest("a", "a", "1.0", "", "",
                List.of(new ModManifest.DependencyEntry("ghost", "*", false)),
                List.of(), "", List.of(), ModManifest.VersionBinding.unbound());

        ModDiscovery discovery = discovery();
        List<ModManifest> resolved = discovery.resolveDependencies(List.of(withOptional));

        assertEquals(List.of("a"), resolved.stream().map(ModManifest::id).toList());
        assertTrue(discovery.missingDependencies().isEmpty());
    }
}
