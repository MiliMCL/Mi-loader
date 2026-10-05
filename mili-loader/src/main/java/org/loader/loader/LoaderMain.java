package org.loader.loader;

import org.loader.loader.classloader.MinecraftClassLoader;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.loader.config.LoaderConfig;
import org.loader.loader.discovery.MinecraftDiscovery;
import org.loader.loader.discovery.ModDiscovery;
import org.loader.loader.game.GameProvider;
import org.loader.loader.game.MinecraftGameProvider;
import org.loader.runtime.error.ModLoadError;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.mod.ApiModContext;
import org.loader.runtime.mod.Mod;
import org.loader.runtime.mod.ModContext;
import org.loader.runtime.mod.ModManifest;
import org.loader.runtime.mod.ModManifest.ValidationResult;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Mili Platform Loader 入口。
 *
 * <p><b>启动流程（严格顺序）</b>：
 * <pre>
 *  1. ensureMinecraftPresent   —— 缺失时调 Installer 现场拉取
 *  2. locateGame               —— 发现 Minecraft JAR + 库
 *  3. createGameClassLoader    —— 建立唯一的 MinecraftClassLoader
 *  4. ModDiscovery             —— 发现 Mod、解析依赖
 *  5. runtime.start()          —— 启动 Mili Runtime
 *  6. validateVersionBindings  —— platform/abi/minecraft 三元组严格校验
 *  7. createAll ModClassLoader —— 每个 Mod 挂在 gameCL 之下
 *  8. invokeModEntrypoints     —— void initialize(ModContext)
 *  9. launch Minecraft         —— 真正调用游戏 main
 * 10. shutdown                 —— 逆序释放 Mod / ClassLoader / Scope
 * </pre>
 *
 * <p><b>关键顺序约束</b>：MinecraftClassLoader 必须在所有 ModClassLoader 之前创建，
 * 否则 Mod 无法与游戏共享同一份 Minecraft 类。
 */
public class LoaderMain {

    private final LoaderConfig config;
    private final org.loader.runtime.kernel.Runtime runtime;
    private ModClassLoaderManager classLoaderManager;
    private final GameProvider gameProvider;
    private Path gameDir;
    private final List<Mod> loadedMods = new ArrayList<>();

    public LoaderMain(LoaderConfig config) {
        this.config = config;
        this.runtime = org.loader.runtime.kernel.Runtime.create("loader-runtime");
        this.gameProvider = discoverGameProvider();
    }

    private static GameProvider discoverGameProvider() {
        ServiceLoader<GameProvider> loader = ServiceLoader.load(GameProvider.class);
        return loader.stream()
                .map(ServiceLoader.Provider::get)
                .findFirst()
                .orElseGet(MinecraftGameProvider::new);
    }

    public static void main(String[] args) throws Exception {
        Path gd = args.length > 0 ? Path.of(args[0]) : Path.of(".");
        LoaderConfig config = LoaderConfig.load(gd);
        LoaderMain m = new LoaderMain(config);
        m.gameDir = gd.toAbsolutePath().normalize();
        m.launch(args);
    }

    /**
     * 确保 gameDir 里存在可用的 Minecraft，缺失时调用安装器现场拉取。
     *
     * <p>安装失败不阻断启动 —— 后续 {@code locateGame} 会抛出明确的
     * {@code GameDiscoveryException}，用户仍可手动排查。
     */
    private void ensureMinecraftPresent() {
        try {
            MinecraftDiscovery probe = MinecraftDiscovery.scan(LoaderConfig.at(gameDir));
            if (probe.found()) {
                return;
            }
        } catch (Exception ignored) {
            // 探测异常不阻断，直接尝试安装
        }

        String version = detectTargetMinecraftVersion();
        System.out.println("[Mili] 未在 " + gameDir + " 找到 Minecraft，开始自动安装 " + version);

        try {
            Class<?> installer = Class.forName("org.loader.installer.InstallerMain");
            Method main = installer.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[]{
                    "--game-dir", gameDir.toString(),
                    "--version", version
            });
            System.out.println("[Mili] Minecraft 安装完成，继续启动");
        } catch (ClassNotFoundException e) {
            System.err.println("[Mili] 提示: 分发包中缺少 mili-installer，"
                    + "请手动提供 Minecraft 安装到 " + gameDir);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("[Mili] 自动安装失败: " + cause.getMessage());
        }
    }

    /**
     * 读取平台绑定的 Minecraft 版本。
     *
     * <p>优先读 JAR 内嵌的 {@code META-INF/mili/platform.json}
     * （构建时写入，保证与编译版本一致）；读不到时回退到 26.2。
     */
    private String detectTargetMinecraftVersion() {
        try {
            java.io.File self = new java.io.File(LoaderMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (!self.isFile()) {
                return "26.2";
            }
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(self)) {
                var entry = zf.getEntry("META-INF/mili/platform.json");
                if (entry != null) {
                    String text;
                    try (InputStream in = zf.getInputStream(entry)) {
                        text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    var matcher = java.util.regex.Pattern
                            .compile("\"minecraft\"\\s*:\\s*\"([^\"]+)\"")
                            .matcher(text);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
        } catch (Exception ignored) {
            // 运行期无 JAR（IDE 启动）或读取失败，走回退值
        }
        return "26.2";
    }

    /**
     * 完整启动流程。
     */
    public void launch(String[] mcArgs) throws Exception {
        ensureMinecraftPresent();
        String[] passThrough = stripGameDirArg(mcArgs);

        // ── 2. 发现 Minecraft ────────────────────────────────────────────
        List<Path> gameClasspath = gameProvider.locateGame(gameDir);

        // ── 3. 建立唯一的 MinecraftClassLoader ───────────────────────────
        if (!(gameProvider instanceof MinecraftGameProvider mcProvider)) {
            throw new IllegalStateException(
                    "当前 GameProvider 不支持显式 ClassLoader 拓扑: "
                            + gameProvider.getClass().getName());
        }
        MinecraftClassLoader gameCL = mcProvider.createGameClassLoader(gameClasspath);
        classLoaderManager = new ModClassLoaderManager(gameCL, gameDir);

        // ── 4. 发现 Mod ──────────────────────────────────────────────────
        LoaderConfig gameConfig = LoaderConfig.at(gameDir);
        ModDiscovery modDiscovery = ModDiscovery.scan(gameConfig);
        List<ModManifest> resolved = modDiscovery.resolveDependencies(modDiscovery.discover());

        runtime.start();

        // ── 6. 严格平台版本校验 ───────────────────────────────────────────
        validateVersionBindings(resolved);

        // ── 7. 创建 ModClassLoader（parent = gameCL） ──────────────────────
        for (ModManifest manifest : resolved) {
            Scope modScope = runtime.rootScope().createChild("mod:" + manifest.id());
            ModClassLoader mcl = classLoaderManager.create(manifest);
            Mod mod = new Mod(manifest, modScope, mcl);
            loadedMods.add(mod);
            modScope.registerResource(mod);
        }

        // ── 8. 调用 Mod 入口 ─────────────────────────────────────────────
        invokeModEntrypoints();

        System.out.println("[Mili] ClassLoader 拓扑:\n" + classLoaderManager.diagnostics());

        // ── 9. 启动 Minecraft ────────────────────────────────────────────
        try {
            gameProvider.launch(gameDir, runtime, classLoaderManager, gameClasspath, passThrough);
        } finally {
            shutdown();
        }
    }

    /**
     * 调用 Mod 入口点。
     *
     * <p>标准单一入口：{@code void initialize(ModContext ctx)}，
     * 其中 {@code ModContext} 是<b>契约接口</b> {@link org.loader.api.ModContext}。
     *
     * <p><b>这里曾经是一个静默失效的入口</b>：早期实现把 runtime 的
     * {@link ModContext}（具体类）直接传给 Mod，并用
     * {@code getMethod("initialize", runtime.ModContext.class)} 找方法。
     * 但 Mod 是按契约编程的，它声明的是
     * {@code initialize(org.loader.api.ModContext)} —— 两个类型不同，
     * {@code getMethod} 必然抛 {@code NoSuchMethodException}，
     * 于是<b>每一个 Mod 都加载失败</b>，而错误消息说的是「缺少标准入口」，
     * 把矛头指向 Mod 作者，实际是平台传错了类型。
     *
     * <p>修法是回到契约：传 {@link ApiModContext}（它实现契约接口），
     * 并按契约接口类型去匹配方法。
     *
     * <p>不接受 {@code onInitialize}、{@code main}、{@code init()} 等 fallback ——
     * 找不到入口即 {@code ModLoadError}，不静默忽略。
     */
    private void invokeModEntrypoints() {
        for (Mod mod : loadedMods) {
            String entrypoint = mod.manifest().entrypoint();
            if (entrypoint == null || entrypoint.isBlank()) {
                throw new ModLoadError("Mod '" + mod.id() + "' 没有声明 entrypoint",
                        "MOD_ENTRYPOINT_INVALID");
            }
            ModClassLoader mcl = classLoaderManager.get(mod.id());
            if (mcl == null) {
                throw new ModLoadError("Mod '" + mod.id() + "' ClassLoader 不存在",
                        "MOD_ENTRYPOINT_INVALID");
            }
            try {
                Class<?> clazz = mcl.loadModClass(entrypoint);

                // 契约要求入口类实现 org.loader.api.Mod。先验证再实例化 ——
                // 否则 Mod 作者会收到 "ClassCastException" 这类完全指不到
                // 「你忘了 implements Mod」的报错。
                if (!org.loader.api.Mod.class.isAssignableFrom(clazz)) {
                    throw new ModLoadError(
                            "Mod '" + mod.id() + "' 入口类 " + entrypoint
                                    + " 没有实现 org.loader.api.Mod"
                                    + "\n  ModFile: " + mod.manifest().name()
                                    + " v" + mod.manifest().version(),
                            "MOD_ENTRYPOINT_INVALID");
                }
                if (java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
                    throw new ModLoadError(
                            "Mod '" + mod.id() + "' 入口类 " + entrypoint + " 是抽象类，无法实例化",
                            "MOD_ENTRYPOINT_INVALID");
                }

                Object instance = clazz.getDeclaredConstructor().newInstance();
                ModContext runtimeCtx = new ModContext(mod);

                // 平台侧装入 Minecraft 注册表契约实现：这一步必须在 Mod
                // 的 initialize() 之前完成，否则 Mod 调 registry() 只会拿到 null。
                RegistryBinder.bind(runtimeCtx, mod);

                // 传给 Mod 的是契约接口的实现，不是 runtime 的具体类。
                org.loader.api.ModContext apiCtx = new ApiModContext(runtimeCtx);
                ((org.loader.api.Mod) instance).initialize(apiCtx);

                modContexts.put(mod.id(), runtimeCtx);
                System.out.println("[Mili] Mod initialized: " + mod.id() + " -> " + entrypoint);

            } catch (ModLoadError e) {
                throw e;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new ModLoadError(
                        "Mod '" + mod.id() + "' 的 initialize() 抛出异常: " + cause,
                        "MOD_ENTRYPOINT_FAILED", cause);
            } catch (ReflectiveOperationException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new ModLoadError(
                        "Mod '" + mod.id() + "' 入口加载失败: " + cause
                                + "\n  入口类: " + entrypoint
                                + "\n  要求: public class " + entrypoint
                                + " implements org.loader.api.Mod，"
                                + "并有 public 无参构造器与 public void initialize("
                                + "org.loader.api.ModContext)",
                        "MOD_ENTRYPOINT_INVALID", cause);
            } catch (RuntimeException e) {
                throw new ModLoadError(
                        "Mod '" + mod.id() + "' 加载失败: " + e,
                        "MOD_ENTRYPOINT_FAILED", e);
            }
        }
    }

    /** 已装配的 runtime ModContext，供关闭阶段与诊断使用。 */
    private final Map<String, ModContext> modContexts = new LinkedHashMap<>();

    /**
     * 逆序释放全部资源。单个失败不阻断其余清理。
     */
    private void shutdown() {
        List<Mod> reverse = new ArrayList<>(loadedMods);
        Collections.reverse(reverse);
        for (Mod m : reverse) {
            try {
                m.scope().shutdown();
            } catch (Exception ignored) {
                // 继续清理其余 Mod
            }
        }
        loadedMods.clear();

        if (classLoaderManager != null) {
            try {
                classLoaderManager.close();
            } catch (Exception ignored) {
                // 同上
            }
        }
        try {
            runtime.close();
        } catch (Exception ignored) {
            // 同上
        }
    }

    private String[] stripGameDirArg(String[] args) {
        if (args == null || args.length <= 1) {
            return new String[0];
        }
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return rest;
    }

    /**
     * 强制平台版本三元组精确匹配。任一不匹配即拒绝。
     */
    private void validateVersionBindings(List<ModManifest> manifests) {
        for (ModManifest m : manifests) {
            ValidationResult result = m.validateVersionBinding();
            if (result instanceof ValidationResult.MissingBinding) {
                throw new ModLoadError(diagnostic(m, "没有声明 mili 平台绑定",
                        "MOD_PLATFORM_MISSING"), "MOD_PLATFORM_MISSING");
            } else if (result instanceof ValidationResult.PlatformMismatch pm) {
                throw new ModLoadError(diagnostic(m,
                        "Mili Platform 版本不匹配，需要 " + pm.expected()
                                + "，当前 " + pm.actual(), "MOD_PLATFORM_MISMATCH"),
                        "MOD_PLATFORM_MISMATCH");
            } else if (result instanceof ValidationResult.AbiMismatch am) {
                throw new ModLoadError(diagnostic(m,
                        "ABI 版本不匹配，需要 " + am.expected() + "，当前 " + am.actual(),
                        "MOD_ABI_MISMATCH"), "MOD_ABI_MISMATCH");
            } else if (result instanceof ValidationResult.MinecraftMismatch mm) {
                throw new ModLoadError(diagnostic(m,
                        "Minecraft 版本不匹配，需要 " + mm.expected() + "，当前 " + mm.actual(),
                        "MOD_MINECRAFT_MISMATCH"), "MOD_MINECRAFT_MISMATCH");
            }
        }
    }

    private String diagnostic(ModManifest m, String detail, String code) {
        return "[Mod: " + m.id() + "] " + detail + "\n"
                + "  ErrorCode: " + code + "\n"
                + "  ModFile: " + m.name() + " v" + m.version();
    }

    public org.loader.runtime.kernel.Runtime runtime() {
        return runtime;
    }

    public List<Mod> getLoadedMods() {
        return Collections.unmodifiableList(loadedMods);
    }

    public ModClassLoaderManager classLoaderManager() {
        return classLoaderManager;
    }
}