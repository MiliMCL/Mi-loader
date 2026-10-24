package org.loader.runtime.mod;

import org.loader.runtime.kernel.*;

import java.util.*;

/**
 * Loads Mods into the Runtime.
 *
 * <h2>本类<b>不是</b>生产加载路径</h2>
 *
 * <p>{@code mili-runtime} 不解析 Mod 二进制：它不知道 Mod 的 jar 在哪，
 * 也没有 {@code ClassVisibility} 契约所需的 Minecraft 类加载器上下文。
 * 因此本类使用的是<b>调用方给定的 ClassLoader</b>（{@link #load} 的
 * {@code parentClassLoader} 参数），不会为每个 Mod 新建隔离加载器。
 *
 * <p>生产路径是 {@code mili-loader} 的
 * {@code org.loader.loader.classloader.ModClassLoader}：
 * <pre>
 *   LoaderMain.launch()
 *     → ModDiscovery.scan()          发现 mod.json + 记录代码源
 *     → ModDiscovery.resolveDependencies()   三态 DFS 拓扑排序（环 → 拒绝）
 *     → validateVersionBindings()    三元组严格校验
 *     → ModClassLoaderManager.create(manifest, source)   ← 真正的隔离加载器
 *     → Mod(manifest, scope, mcl)
 *     → invokeModEntrypoints()       void initialize(org.loader.api.ModContext)
 * </pre>
 * 本类与 {@code ModManager} 是<b>独立于生产链路的进程内 API</b>，
 * 供测试与「Mod 代码已在当前 ClassLoader 上」的宿主场景使用。
 *
 * <p>为避免「看起来加载成功、实际什么都没加载」，{@link #load} 会校验清单声明
 * 的入口类在给定 ClassLoader 上确实可见 —— 不可见就立刻失败，
 * 而不是等到调用 {@code initialize} 时抛一句难以定位的 {@code NoClassDefFoundError}。
 */
public class ModLoader {

    private final org.loader.runtime.kernel.Runtime runtime;
    private final Map<String, Mod> loadedMods = new LinkedHashMap<>();

    public ModLoader(org.loader.runtime.kernel.Runtime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    /**
     * 本实现是否会为每个 Mod 创建隔离 ClassLoader。
     *
     * <p>恒为 {@code false}，见类注释。需要隔离请用
     * {@code mili-loader} 的 {@code ModClassLoader}。
     */
    public static boolean createsIsolatedClassLoaders() {
        return false;
    }

    /**
     * Loads a mod from its manifest.
     *
     * @param manifest         Mod 清单
     * @param parentClassLoader 能看到该 Mod 类的加载器；null 表示用本类的加载器
     * @throws ModLoadException 入口类不可见，或 Mod 已加载
     */
    public Mod load(ModManifest manifest, ClassLoader parentClassLoader) {
        if (loadedMods.containsKey(manifest.id())) {
            throw new IllegalStateException("Mod already loaded: " + manifest.id());
        }

        // Create mod scope as child of root
        Scope modScope = runtime.rootScope().createChild("mod:" + manifest.id());
        runtime.lifecycleManager().registerScope(modScope);

        // Resolve classloader（占位语义：不解析 Mod 二进制，见类注释）
        ClassLoader modClassLoader = resolveClassLoader(manifest, parentClassLoader);

        // Create Mod instance
        Mod mod = new Mod(manifest, modScope, modClassLoader);
        modScope.registerResource(mod);
        loadedMods.put(manifest.id(), mod);

        // Transition through lifecycle
        modScope.transitionTo(LifecycleState.RESOLVED);
        modScope.transitionTo(LifecycleState.LOADED);
        modScope.transitionTo(LifecycleState.INITIALIZED);
        modScope.transitionTo(LifecycleState.REGISTERED);
        modScope.transitionTo(LifecycleState.RUNNING);

        return mod;
    }

    /**
     * Unloads a mod.
     *
     * <p>注意：这只解除 Runtime 侧的登记并关闭 Scope。
     * <b>JVM 不保证类被卸载</b> —— 只要还有强引用（静态字段、线程栈、
     * 缓存），对应 ClassLoader 与其类就不会被回收。因此这里不声称
     * 「彻底卸载」。
     */
    public void unload(Mod mod) {
        loadedMods.remove(mod.id());
        mod.scope().shutdown();
        runtime.rootScope().removeChild(mod.scope().id());
    }

    /**
     * Returns all loaded mods.
     */
    public Collection<Mod> mods() {
        return Collections.unmodifiableCollection(loadedMods.values());
    }

    /**
     * Finds a loaded mod by ID.
     */
    public Optional<Mod> find(String modId) {
        return Optional.ofNullable(loadedMods.get(modId));
    }

    /**
     * 解析（而非创建）Mod 的 ClassLoader，并验证入口类可见。
     *
     * <p>历史实现直接 {@code return parent}，注释写着「In a real implementation,
     * this would create a URLClassLoader from mod jars」。这行注释掩盖了一个
     * 真实问题：{@code load()} 返回成功后，后续任何对 Mod 类的引用都会在
     * 完全无关的地方抛 {@code ClassNotFoundException}。
     */
    private ClassLoader resolveClassLoader(ModManifest manifest, ClassLoader parent) {
        ClassLoader loader = parent != null ? parent : getClass().getClassLoader();

        String entrypoint = manifest.entrypoint();
        if (entrypoint == null || entrypoint.isBlank()) {
            return loader;
        }
        try {
            // initialize=false：只做可见性检查，不触发入口类的静态初始化
            Class.forName(entrypoint, false, loader);
        } catch (ClassNotFoundException e) {
            throw new ModLoadException(
                    "Mod '" + manifest.id() + "' 的入口类 " + entrypoint
                            + " 在给定 ClassLoader 上不可见。"
                            + "Runtime 的 ModLoader 不解析 Mod 二进制（占位实现）；"
                            + "生产路径请使用 mili-loader 的 ModClassLoader，"
                            + "它按发现阶段记录的 jar/目录源装配。", e);
        }
        return loader;
    }
}
