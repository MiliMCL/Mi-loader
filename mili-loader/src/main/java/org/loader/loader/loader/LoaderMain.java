package org.loader.loader;

import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.loader.config.LoaderConfig;
import org.loader.loader.discovery.ModDiscovery;
import org.loader.loader.game.GameProvider;
import org.loader.runtime.RuntimeEnvironment;
import org.loader.runtime.error.ModLoadError;
import org.loader.runtime.mod.Mod;
import org.loader.runtime.mod.ModContext;
import org.loader.runtime.mod.ModManifest;
import org.loader.runtime.mod.ModManifest.ValidationResult;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Mili Platform Loader 入口。
 * 启动顺序：
 * 1. 发现 Minecraft
 * 2. 读取 Platform Descriptor
 * 3. 发现 Mod
 * 4. 解析 Manifest
 * 5. Manifest Schema 校验
 * 6. Mili Platform / ABI / Minecraft Version 严格校验
 * 7. 依赖解析
 * 8. ClassLoader 创建
 * 9. 调用 Mod 入口方法 {@code initialize(ModContext)}
 * 10. 启动 Minecraft
 */
public class LoaderMain {

    private final LoaderConfig config;
    private final org.loader.runtime.kernel.Runtime runtime;
    private final ModClassLoaderManager classLoaderManager;
    private final GameProvider gameProvider;
    private Path gameDir;
    private final List<Mod> loadedMods = new ArrayList<>();

    public LoaderMain(LoaderConfig config) {
        this.config = config;
        this.runtime = org.loader.runtime.kernel.Runtime.create("loader-runtime");
        this.classLoaderManager = new ModClassLoaderManager();
        this.gameProvider = discoverGameProvider();
    }

    private static GameProvider discoverGameProvider() {
        ServiceLoader<GameProvider> loader = ServiceLoader.load(GameProvider.class);
        return loader.stream()
                .map(ServiceLoader.Provider::get)
                .findFirst()
                .orElseGet(() -> new org.loader.loader.game.MinecraftGameProvider());
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
     * <p>这是「下载即启动」的关键一环：用户解压分发包后直接运行，
     * 不需要先手动跑一次安装器。安装器是幂等的，重复调用只补齐缺失文件。
     *
     * <p>安装失败不会中断启动流程 —— 后续 {@code locateGame} 会抛出带
     * 明确原因的 GameDiscoveryException，用户仍有机会手动排查。
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
        System.out.println("[Mili] 未在 " + gameDir + " 找到 Minecraft，"
                + "开始自动安装 " + version + "（首次约需下载 600MB）");

        try {
            Class<?> installer = Class.forName("org.loader.installer.InstallerMain");
            Method main = installer.getMethod("main", String[].class);
            // --skip-assets 不适合这里：缺资源会在进世界时崩溃，必须完整安装
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
     * <p>优先用 JAR 内嵌的 platform.json（由构建时指纹写入，保证与
     * 平台编译版本一致）；读不到时回退到 26.2 这个当前支持版本。
     */
    private String detectTargetMinecraftVersion() {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(
                LoaderMain.class.getProtectionDomain().getCodeSource()
                        .getLocation().toURI())) {
            var entry = zf.getEntry("META-INF/mili/platform.json");
            if (entry != null) {
                String text;
                try (InputStream in = zf.getInputStream(entry)) {
                    text = new String(in.readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
                var matcher = java.util.regex.Pattern
                        .compile("\"minecraft\"\\s*:\\s*\"([^\"]+)\"")
                        .matcher(text);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception ignored) {
            // 运行期无 JAR（IDE 启动）或读取失败，走回退值
        }
        return "26.2";
    }

    public void launch(String[] mcArgs) throws Exception {
        ensureMinecraftPresent();
        String[] passThrough = stripGameDirArg(mcArgs);
        List<Path> gameClasspath = gameProvider.locateGame(gameDir);
        LoaderConfig gameConfig = LoaderConfig.at(gameDir);
        ModDiscovery modDiscovery = ModDiscovery.scan(gameConfig);
        List<ModManifest> resolved = modDiscovery.resolveDependencies(modDiscovery.discover());

        runtime.start();

        // 严格平台版本校验 —— 拒绝任何不匹配或未绑定的 Mod
        validateVersionBindings(resolved);

        for (ModManifest manifest : resolved) {
            org.loader.runtime.kernel.Scope modScope = runtime.rootScope().createChild("mod:" + manifest.id());
            createModClassLoader(manifest, gameClasspath);
            var mcl = classLoaderManager.getModClassLoader(manifest.id());
            Mod mod = new Mod(manifest, modScope, mcl.getClassLoader());
            loadedMods.add(mod);
            modScope.registerResource(mod);
        }

        invokeModEntrypoints();

        try {
            gameProvider.launch(gameDir, runtime, classLoaderManager, gameClasspath, passThrough);
        } finally {
            shutdownMods();
            classLoaderManager.close();
            try { runtime.close(); } catch (Exception ignored) {}
        }
    }

    private void createModClassLoader(ModManifest mod, List<Path> gameClasspath) {
        URL[] urls = new URL[gameClasspath.size()];
        for (int i = 0; i < gameClasspath.size(); i++) {
            try { urls[i] = gameClasspath.get(i).toUri().toURL(); }
            catch (java.net.MalformedURLException e) { throw new RuntimeException(e); }
        }
        classLoaderManager.createModClassLoaderManifest(mod, Arrays.asList(urls), gameDir);
    }

    /**
     * 调用 Mod 入口点。
     * 标准单一入口：{@code void initialize(ModContext ctx)}。
     * 不再接受 {@code onInitialize(ModContext)}、{@code main(String[])}、{@code init()} 等旧 fallback。
     * 找不到入口将作为 ModLoadError 抛出，不会静默忽略。
     */
    private void invokeModEntrypoints() {
        for (Mod mod : loadedMods) {
            String entrypoint = mod.manifest().entrypoint();
            if (entrypoint == null || entrypoint.isBlank()) {
                throw new ModLoadError("Mod '" + mod.id() + "' 没有声明 entrypoint", "MOD_ENTRYPOINT_INVALID");
            }
            try {
                var mcl = classLoaderManager.getModClassLoader(mod.id());
                if (mcl == null) {
                    throw new ModLoadError("Mod '" + mod.id() + "' ClassLoader 不存在", "MOD_ENTRYPOINT_INVALID");
                }
                Class<?> clazz = mcl.loadModClass(entrypoint);
                Object instance = clazz.getDeclaredConstructor().newInstance();
                ModContext ctx = new ModContext(mod);

                // 严格只允许 void initialize(ModContext ctx)
                var method = clazz.getMethod("initialize", ModContext.class);
                method.invoke(instance, ctx);
                System.out.println("[Mili] Mod initialized: " + mod.id() + " -> " + entrypoint);

            } catch (NoSuchMethodException e) {
                throw new ModLoadError(
                    "Mod '" + mod.id() + "' 缺少标准入口 void initialize(ModContext ctx)",
                    "MOD_ENTRYPOINT_INVALID");
            } catch (ModLoadError e) {
                throw e;
            } catch (Exception e) {
                throw new ModLoadError("Mod '" + mod.id() + "' 入口调用失败: " + e.getMessage(),
                    "MOD_ENTRYPOINT_INVALID");
            }
        }
    }

    private void shutdownMods() {
        List<Mod> reverse = new ArrayList<>(loadedMods);
        Collections.reverse(reverse);
        for (Mod m : reverse) {
            try { m.scope().shutdown(); } catch (Exception ignored) {}
        }
    }

    private String[] stripGameDirArg(String[] args) {
        if (args == null || args.length <= 1) return new String[0];
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return rest;
    }

    /**
     * 强制平台版本三元组精确匹配。
     * <ul>
     *     <li>未绑定 mili metadata: MOD_PLATFORM_MISSING</li>
     *     <li>platform 不匹配: MOD_PLATFORM_MISMATCH</li>
     *     <li>ABI 不匹配: MOD_ABI_MISMATCH</li>
     *     <li>Minecraft 不匹配: MOD_MINECRAFT_MISMATCH</li>
     * </ul>
     */
    private void validateVersionBindings(List<ModManifest> manifests) {
        for (ModManifest m : manifests) {
            ValidationResult result = m.validateVersionBinding();
            if (result instanceof ValidationResult.MissingBinding) {
                throw new ModLoadError(diagnostic(m, "没有声明 mili 平台绑定",
                    "MOD_PLATFORM_MISSING"), "MOD_PLATFORM_MISSING");
            } else if (result instanceof ValidationResult.PlatformMismatch pm) {
                throw new ModLoadError(diagnostic(m,
                    "Mili Platform 版本不匹配，需要 " + pm.expected() + "，当前 " + pm.actual(),
                    "MOD_PLATFORM_MISMATCH"), "MOD_PLATFORM_MISMATCH");
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
        return "[Mod: " + m.id() + "] " + detail + "\n" +
               "  ErrorCode: " + code + "\n" +
               "  ModFile: " + m.name() + " v" + m.version();
    }

    public org.loader.runtime.kernel.Runtime runtime() { return runtime; }
    public List<Mod> getLoadedMods() { return Collections.unmodifiableList(loadedMods); }
}