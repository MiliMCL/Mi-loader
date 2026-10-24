package org.loader.runtime.instance;

import org.loader.runtime.kernel.*;
import org.loader.runtime.mod.*;
import org.loader.runtime.util.DependencySorter;
import org.loader.runtime.util.ModLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages mod discovery, resolution, loading, and unloading for instances.
 * <p>
 * The ModManager orchestrates the full mod lifecycle:
 * <ol>
 *   <li><b>Discovery</b> — scans registered sources to find available mod manifests</li>
 *   <li><b>Resolution</b> — selects the mod set for an instance and resolves dependencies</li>
 *   <li><b>Loading</b> — loads resolved mods into a Runtime via ModLoader</li>
 *   <li><b>Unloading</b> — unloads mods in reverse order and cleans up scopes</li>
 * </ol>
 * <p>
 * Thread safety is provided through a ReentrantReadWriteLock.
 */
public class ModManager implements Resource {

    private final String id;
    private final Scope owner;
    private final ModDiscoverer discoverer;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, ModResolutionResult> resolutionCache = new ConcurrentHashMap<>();
    private volatile boolean closed = false;
    private final ModLogger logger;

    public ModManager(String id, Scope owner) {
        this.id = Objects.requireNonNull(id, "ModManager id must not be null");
        this.owner = Objects.requireNonNull(owner, "ModManager owner must not be null");
        this.discoverer = new ModDiscoverer(null);
        this.logger = new ModLogger(id, owner);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Scope owner() {
        return owner;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /**
     * Adds a mod source for discovery.
     */
    public void addSource(ModDiscoverer.ModSource source) {
        checkNotClosed();
        discoverer.addSource(source);
    }

    /**
     * Discovers all available mods from all registered sources.
     *
     * @return list of discovered mod manifests
     */
    public List<ModManifest> availableMods() {
        checkNotClosed();
        lock.readLock().lock();
        try {
            return discoverer.discover();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Finds an available mod by ID.
     */
    public Optional<ModManifest> findAvailable(String modId) {
        return availableMods().stream()
                .filter(m -> m.id().equals(modId))
                .findFirst();
    }

    /**
     * Resolves the mod set for an instance.
     * Takes the instance's requested mod IDs and resolves them against
     * available mods, checking dependencies and producing a load-ordered list.
     *
     * <p><b>循环检测</b>：排序交给 {@link DependencySorter}（三态 DFS）。
     * 历史实现只用 {@code visited} 去重，{@code A → B → A} 会被当成
     * 「已访问」而非环，于是循环依赖以任意顺序「解析成功」并进入加载流程。
     * 现在环、缺失、版本不匹配是三类可区分的失败。
     *
     * @param instance the instance definition
     * @return the resolution result
     */
    public ModResolutionResult resolve(Instance instance) {
        Objects.requireNonNull(instance, "instance must not be null");
        checkNotClosed();

        lock.readLock().lock();
        try {
            // Build lookup of available mods
            Map<String, ModManifest> available = new LinkedHashMap<>();
            for (ModManifest mod : discoverer.discover()) {
                available.putIfAbsent(mod.id(), mod);
            }

            List<DependencySorter.Node> nodes = new ArrayList<>(available.size());
            for (ModManifest mod : available.values()) {
                nodes.add(new DependencySorter.Node(mod.id(), requiredDependencyIds(mod)));
            }

            DependencySorter.Result sorted =
                    DependencySorter.sort(nodes, instance.modIds());

            List<String> conflicts = new ArrayList<>();
            for (List<String> cycle : sorted.cycles()) {
                conflicts.add("依赖循环: " + String.join(" -> ", cycle));
            }
            conflicts.addAll(versionConflicts(available, sorted.order()));

            if (sorted.hasMissing() || !conflicts.isEmpty()) {
                ModResolutionResult result = ModResolutionResult.failed(
                        instance.instanceId(), sorted.missing(), conflicts);
                resolutionCache.put(instance.instanceId(), result);
                logger.info("Resolution failed for instance " + instance.instanceId()
                        + ": " + result.summary());
                return result;
            }

            List<ModManifest> resolved = new ArrayList<>(sorted.order().size());
            for (String id : sorted.order()) {
                ModManifest manifest = available.get(id);
                if (manifest != null) {
                    resolved.add(manifest);
                }
            }

            ModResolutionResult result = ModResolutionResult.success(
                    instance.instanceId(), resolved);
            resolutionCache.put(instance.instanceId(), result);
            logger.info("Resolved " + resolved.size() + " mods for instance: " + instance.instanceId());
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the cached resolution result for an instance, if any.
     */
    public Optional<ModResolutionResult> cachedResolution(String instanceId) {
        return Optional.ofNullable(resolutionCache.get(instanceId));
    }

    /**
     * Creates a resolved ModSet for an instance.
     * Convenience method that wraps resolve() and produces a ModSet.
     *
     * @throws ModManagerException if resolution fails
     */
    public ModSet createModSet(Instance instance) {
        ModResolutionResult result = resolve(instance);
        if (!result.success()) {
            throw new ModManagerException("Cannot create ModSet: " + result.summary());
        }
        return new ModSet(instance.instanceId(), result.resolvedMods());
    }

    /**
     * Loads all mods from a resolved set into a Runtime.
     *
     * @param runtime the runtime to load mods into
     * @param modSet  the resolved mod set
     * @return list of loaded Mod instances
     */
    public List<Mod> load(org.loader.runtime.kernel.Runtime runtime, ModSet modSet) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        Objects.requireNonNull(modSet, "modSet must not be null");
        checkNotClosed();

        lock.writeLock().lock();
        try {
            ModLoader loader = new ModLoader(runtime);
            List<Mod> loaded = new ArrayList<>();

            for (ModManifest manifest : modSet.mods()) {
                try {
                    Mod mod = loader.load(manifest, ModManager.class.getClassLoader());
                    loaded.add(mod);
                    logger.info("Loaded mod: " + manifest.id());
                } catch (Exception e) {
                    logger.info("Failed to load mod " + manifest.id() + ": " + e.getMessage());
                    // Rollback already loaded mods
                    for (Mod loadedMod : loaded) {
                        try {
                            loader.unload(loadedMod);
                        } catch (Exception ignored) {
                            // Best effort rollback
                        }
                    }
                    throw new ModManagerException(
                            "Failed to load mod: " + manifest.id(), e);
                }
            }

            logger.info("Loaded " + loaded.size() + " mods");
            return loaded;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Loads and resolves mods for an instance into a Runtime.
     * Convenience method combining resolve + load.
     */
    public List<Mod> loadForInstance(org.loader.runtime.kernel.Runtime runtime, Instance instance) {
        ModSet modSet = createModSet(instance);
        return load(runtime, modSet);
    }

    /** 只取必需依赖的 mod id；可选依赖不参与加载顺序约束。 */
    private static List<String> requiredDependencyIds(ModManifest mod) {
        List<String> ids = new ArrayList<>(mod.dependencies().size());
        for (ModManifest.DependencyEntry dep : mod.dependencies()) {
            if (dep.required()) {
                ids.add(dep.modId());
            }
        }
        return ids;
    }

    /**
     * 检查已解析 Mod 之间的版本约束冲突。
     *
     * <p>与「缺失」「循环」刻意分开表达：这三类失败的处理方式完全不同
     * （装包 / 改依赖关系 / 换版本），混在一个列表里等于没诊断。
     */
    private static List<String> versionConflicts(Map<String, ModManifest> available,
                                                 List<String> order) {
        List<String> conflicts = new ArrayList<>();
        for (String id : order) {
            ModManifest mod = available.get(id);
            if (mod == null) {
                continue;
            }
            for (ModManifest.DependencyEntry dep : mod.dependencies()) {
                if (!dep.required()) {
                    continue;
                }
                ModManifest target = available.get(dep.modId());
                if (target == null) {
                    // 缺失已由 missingDependencies 表达，不重复报告
                    continue;
                }
                if (!versionSatisfies(dep.versionRange(), target.version())) {
                    conflicts.add("版本不匹配: " + mod.id() + " 要求 " + dep.modId()
                            + " " + dep.versionRange() + "，实际为 " + target.version());
                }
            }
        }
        return conflicts;
    }

    /**
     * 版本约束求值。
     *
     * <p>当前只实现三种情形：通配（{@code *} / 空）、精确版本、以及
     * <b>未支持的范围表达式</b>。范围表达式（{@code ^}、{@code ~}、
     * 比较符、逗号交集）一律按「不校验」处理 —— 平台的依赖声明目前
     * 只产出 {@code *}，把未实现的语法当作失败会造成误报，
     * 而误报会阻断本来能跑的 Mod。
     */
    static boolean versionSatisfies(String range, String actualVersion) {
        if (range == null || range.isBlank() || "*".equals(range.trim())) {
            return true;
        }
        String r = range.trim();
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c == '^' || c == '~' || c == '>' || c == '<' || c == ','
                    || c == '[' || c == '(' || c == ')' || c == ']' || c == ' ') {
                return true;   // 未实现的范围语法：不参与校验
            }
        }
        return r.equals(actualVersion);
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("ModManager is closed");
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            resolutionCache.clear();
            logger.info("ModManager closed");
        } finally {
            lock.writeLock().unlock();
        }
    }
}
