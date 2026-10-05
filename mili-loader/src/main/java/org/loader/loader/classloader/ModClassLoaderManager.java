package org.loader.loader.classloader;

import org.loader.runtime.mod.ModManifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理所有 Mod 的 {@link ModClassLoader}，并保证：
 * <ul>
 *   <li>所有 Mod 共享同一个 {@link MinecraftClassLoader}（MC 类全局唯一）</li>
 *   <li>按依赖顺序创建（被依赖者先就绪）</li>
 *   <li>检测跨 Mod 的重复类定义并给出明确诊断</li>
 *   <li>逆序关闭，确保依赖者先于被依赖者卸载</li>
 * </ul>
 */
public final class ModClassLoaderManager implements AutoCloseable {

    private final Map<String, ModClassLoader> classLoaders = new LinkedHashMap<>();
    private final List<String> creationOrder = new ArrayList<>();
    private final MinecraftClassLoader gameClassLoader;
    private final Path gameDir;

    /** 全局重复类注册表：className -> 定义它的 modId。 */
    private final Map<String, String> classOwners = new LinkedHashMap<>();
    /** 检出的重复类（诊断）。 */
    private final List<DuplicateClass> duplicates = new ArrayList<>();

    public ModClassLoaderManager(MinecraftClassLoader gameClassLoader, Path gameDir) {
        this.gameClassLoader = gameClassLoader;
        this.gameDir = gameDir;
    }

    /**
     * 按依赖拓扑顺序为所有 Mod 创建 ClassLoader。
     *
     * @param manifests 已按依赖排序的清单（来自 {@code ModDiscovery.resolveDependencies}）
     */
    public void createAll(List<ModManifest> manifests) throws IOException {
        for (ModManifest manifest : manifests) {
            create(manifest);
        }
    }

    /**
     * 为单个 Mod 创建 ClassLoader。
     *
     * <p><b>注意</b>：parent 一律是 {@link MinecraftClassLoader}，而非依赖 Mod。
     * 跨 Mod 访问通过 {@link ClassVisibility} 的导出机制控制，而非靠 ClassLoader 父子关系，
     * 这样才能保证「Mod A 卸载不影响 Mod B 已经链接的类」。
     */
    public ModClassLoader create(ModManifest manifest) throws IOException {
        if (classLoaders.containsKey(manifest.id())) {
            return classLoaders.get(manifest.id());
        }
        ModClassLoader mcl = new ModClassLoader(manifest, gameClassLoader, gameDir);
        classLoaders.put(manifest.id(), mcl);
        creationOrder.add(manifest.id());
        return mcl;
    }

    public ModClassLoader get(String modId) {
        return classLoaders.get(modId);
    }

    public boolean has(String modId) {
        return classLoaders.containsKey(modId);
    }

    public Collection<ModClassLoader> all() {
        return Collections.unmodifiableCollection(classLoaders.values());
    }

    public List<String> loadedModIds() {
        return List.copyOf(creationOrder);
    }

    public MinecraftClassLoader gameClassLoader() {
        return gameClassLoader;
    }

    /**
     * 记录某个类由哪个 Mod 定义，用于重复类检测。
     *
     * @return 若该类此前已被另一个 Mod 定义，返回那次定义的 modId；否则 null
     */
    public String registerClassOwner(String className, String modId) {
        String previous = classOwners.putIfAbsent(className, modId);
        if (previous != null && !previous.equals(modId)) {
            DuplicateClass dup = new DuplicateClass(className, previous, modId);
            synchronized (duplicates) {
                duplicates.add(dup);
            }
            return previous;
        }
        return null;
    }

    /**
     * 扫描所有 Mod 的已加载类，检出重复定义。
     *
     * <p>child-first 策略下，两个 Mod 各自定义同名类是<b>允许</b>的（互相隔离），
     * 但这通常意味着 Mod 打包了重复的库，应当告警。
     *
     * @return 重复类清单（按类名排序）
     */
    public List<DuplicateClass> detectDuplicateClasses() {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (ModClassLoader mcl : classLoaders.values()) {
            for (String cls : mcl.loadedClasses()) {
                // 只统计 Mod 私有类（PARENT_FIRST 的共享类天然重复，属正常）
                if (ClassVisibility.resolve(cls) != ClassVisibility.Resolution.SELF_FIRST) {
                    continue;
                }
                owners.computeIfAbsent(cls, k -> new ArrayList<>()).add(mcl.modId());
            }
        }
        List<DuplicateClass> found = new ArrayList<>();
        owners.forEach((cls, mods) -> {
            if (mods.size() > 1) {
                found.add(new DuplicateClass(cls, mods.get(0), String.join(",", mods)));
            }
        });
        found.sort((a, b) -> a.className().compareTo(b.className()));
        return found;
    }

    /** 关闭期间检出的重复类（冻结）。 */
    public List<DuplicateClass> recordedDuplicates() {
        synchronized (duplicates) {
            return List.copyOf(duplicates);
        }
    }

    /** 汇总所有 Mod 的被拒绝访问记录（安全审计）。 */
    public List<ModClassLoader.AccessViolation> allViolations() {
        List<ModClassLoader.AccessViolation> all = new ArrayList<>();
        for (ModClassLoader mcl : classLoaders.values()) {
            all.addAll(mcl.violations());
        }
        return all;
    }

    /**
     * 诊断快照：用于启动日志与 CI 输出。
     */
    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("ClassLoader 拓扑:\n");
        sb.append("  MinecraftClassLoader (net.minecraft.* 唯一定义来源)\n");
        for (ModClassLoader mcl : classLoaders.values()) {
            sb.append("    └─ ").append(mcl.modId())
                    .append("  sources=").append(mcl.modSources().size())
                    .append(" loaded=").append(mcl.loadedClasses().size())
                    .append('\n');
        }
        List<DuplicateClass> dups = detectDuplicateClasses();
        if (!dups.isEmpty()) {
            sb.append("  重复类警告 (").append(dups.size()).append("):\n");
            for (DuplicateClass d : dups) {
                sb.append("    ").append(d).append('\n');
            }
        }
        List<ModClassLoader.AccessViolation> v = allViolations();
        if (!v.isEmpty()) {
            sb.append("  被拒绝的访问 (").append(v.size()).append("):\n");
            for (var a : v) {
                sb.append("    ").append(a).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 逆序关闭所有 Mod ClassLoader（后创建的先关）。
     * 不会关闭 MinecraftClassLoader —— 它的生命周期由平台控制。
     */
    @Override
    public void close() {
        List<String> reverse = new ArrayList<>(creationOrder);
        Collections.reverse(reverse);
        for (String modId : reverse) {
            ModClassLoader mcl = classLoaders.get(modId);
            if (mcl != null) {
                try {
                    mcl.close();
                } catch (IOException ignored) {
                    // 关闭失败不阻断其他 Mod 的卸载
                }
            }
        }
        classLoaders.clear();
        creationOrder.clear();
        classOwners.clear();
    }

    /**
     * 一个被多个 Mod 定义的类。
     */
    public record DuplicateClass(String className, String firstOwner, String conflictingOwners) {
        @Override
        public String toString() {
            return className + " 定义于 [" + firstOwner + "] 与 [" + conflictingOwners + "]";
        }
    }

    /** 便于诊断的 Set 快照。 */
    public Set<String> loadedClassNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(classOwners.keySet()));
    }
}