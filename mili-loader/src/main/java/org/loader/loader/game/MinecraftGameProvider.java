package org.loader.loader.game;

import org.loader.api.VersionInfo;
import org.loader.api.transform.TransformationEnvironment;
import org.loader.api.transform.symbol.MiliSymbol;
import org.loader.loader.discovery.LibraryResolver;
import org.loader.loader.classloader.MinecraftClassLoader;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.loader.config.LoaderConfig;
import org.loader.loader.discovery.MinecraftDiscovery;
import org.loader.loader.hook.EntryPointHook;
import org.loader.loader.transform.ClassTransformInterceptor;
import org.loader.runtime.RuntimeEnvironment;
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.minecraft.client.TitleScreenDispatch;
import org.loader.runtime.minecraft.transform.MiliClientBrandTransformer;
import org.loader.runtime.minecraft.transform.MiliTickTransformer;
import org.loader.runtime.minecraft.transform.MiliTitleScreenTransformer;
import org.loader.runtime.transform.engine.TransformerPipeline;
import org.loader.runtime.transform.engine.TransformerRegistry;
import org.loader.loader.PlatformLog;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mili GameProvider for Minecraft 26.2。
 *
 * <p><b>启动策略</b>：进程内唯一 {@link MinecraftClassLoader}。
 * <ul>
 *   <li>该 CL 是全平台<b>唯一</b>定义 {@code net.minecraft.*} 的地方</li>
 *   <li>所有 ModClassLoader 以它为 parent —— MC 类全局同源</li>
 *   <li>生命周期桥接到 Mili Runtime（见 {@link EntryPointHook}）</li>
 * </ul>
 *
 * <p>Mili 不是 Fabric 移植：无 Knot / Mixin / LaunchWrapper 概念。
 */
public class MinecraftGameProvider implements GameProvider {

    private String detectedVersion;
    private MinecraftDiscovery.GameType gameType;
    private List<Path> lastClasspath;
    private RuntimeEnvironment detectedEnvironment;

    /** 全局唯一的 Minecraft ClassLoader。 */
    private MinecraftClassLoader gameClassLoader;

    @Override
    public String getGameId() {
        return "minecraft";
    }

    @Override
    public String getGameName() {
        return "Minecraft";
    }

    @Override
    public String getRawGameVersion() {
        return detectedVersion != null ? detectedVersion : "unknown";
    }

    @Override
    public String getNormalizedGameVersion() {
        return getRawGameVersion();
    }

    @Override
    public String getEntrypoint() {
        if (gameType == MinecraftDiscovery.GameType.CLIENT) {
            return "net.minecraft.client.main.Main";
        }
        return "net.minecraft.server.Main";
    }

    @Override
    public Path getLaunchDirectory() {
        return Path.of(".");
    }

    @Override
    public boolean isObfuscated() {
        return false;
    }

    @Override
    public RuntimeEnvironment getEnvironment() {
        return detectedEnvironment != null ? detectedEnvironment : RuntimeEnvironment.CLIENT;
    }

    /**
     * 唯一定义 Minecraft 类的 ClassLoader。在 Mod 加载之前必须先建立。
     */
    public MinecraftClassLoader gameClassLoader() {
        return gameClassLoader;
    }

    @Override
    public List<Path> locateGame(Path gameDir) throws GameDiscoveryException {
        LoaderConfig config = LoaderConfig.at(gameDir);
        MinecraftDiscovery discovery = MinecraftDiscovery.scan(config);
        if (!discovery.found()) {
            throw new GameDiscoveryException("Minecraft JAR not found in " + gameDir);
        }
        gameType = discovery.getGameType();
        detectedVersion = discovery.getVersion();
        lastClasspath = discovery.getClasspath();
        detectedEnvironment = (gameType == MinecraftDiscovery.GameType.CLIENT)
                ? RuntimeEnvironment.CLIENT
                : RuntimeEnvironment.DEDICATED_SERVER;

        List<String> libWarnings = LibraryResolver.validateClasspath(lastClasspath);
        for (String w : libWarnings) {
            PlatformLog.warn("Library warning: " + w);
        }
        return lastClasspath;
    }

    /**
     * 创建 MinecraftClassLoader。必须在任何 Mod ClassLoader 之前调用，
     * 因为后者以其为 parent。
     */
    public MinecraftClassLoader createGameClassLoader(List<Path> gameClasspath)
            throws GameDiscoveryException {
        URL[] urls = new URL[gameClasspath.size()];
        for (int i = 0; i < gameClasspath.size(); i++) {
            try {
                urls[i] = gameClasspath.get(i).toUri().toURL();
            } catch (Exception e) {
                throw new GameDiscoveryException("无法构造 classpath URL: " + e.getMessage());
            }
        }
        gameClassLoader = new MinecraftClassLoader("minecraft-game", urls,
                getClass().getClassLoader());

        // 硬校验：主类必须真的能解析，不能只看文件存在
        String mainClass = getEntrypoint();
        if (!gameClassLoader.canLoad(mainClass)) {
            throw new GameDiscoveryException(
                    "Minecraft 主类无法解析: " + mainClass
                            + "。请确认 gameDir 中的 Minecraft 完整且版本匹配 ("
                            + getRawGameVersion() + ")");
        }

        // 把游戏 ClassLoader 交给绑定层（org.loader.runtime.minecraft.reflect.Reflect）。
        //
        // 这一行曾经根本不存在，于是整个绑定层在生产路径上用的是
        // Reflect.class.getClassLoader() —— 平台的 AppClassLoader。
        // Minecraft 的类只存在于 MinecraftClassLoader 的 URL 里，
        // 平台 CL 看不见，于是启动第一次触碰游戏类时就炸：
        //   ClassNotFoundException: net.minecraft.SharedConstants
        //   → SharedVersionGate.ensureVersionDetected 失败
        //   → EntryPointHook 拒绝启动游戏。
        //
        // 测试路径靠 GameTestClassLoader.install() 调useGameClassLoader，
        // 于是这个缺陷在「有 MC 的测试」里完全不可见 —— 测试和生产
        // 唯一的区别就是这一行。
        //
        // 必须在任何 Mod 加载、任何注册动作排队之前设置：Mod 的
        // initialize() 里第一次 registry().block(...) 就会解析游戏类。
        org.loader.runtime.minecraft.reflect.Reflect
                .useGameClassLoader(gameClassLoader);

        return gameClassLoader;
    }

    /**
     * 启动 Minecraft。
     *
     * <p>此时 Mod 已经加载完毕（{@code classLoaderManager} 已就绪），
     * MinecraftClassLoader 也已创建。本方法只负责调用游戏 main 并桥接生命周期。
     */
    @Override
    public void launch(Path gameDir,
                       Runtime runtime,
                       ModClassLoaderManager classLoaderManager,
                       List<Path> gameClasspath,
                       String[] args) throws Exception {
        if (gameClassLoader == null) {
            createGameClassLoader(gameClasspath);
        }

        // 开启文件日志：此后所有 [Mili] 诊断都会进 logs/latest.log。
        //
        // 【为什么必须开】平台此前全靠 System.out，输出只在控制台。
        // 于是排查时「latest.log 里零条 [Mili]」被误读成「代码没执行」，
        // 连续两轮把诊断方向带偏 —— 日志源缺失本身就是一种故障。
        // 此时游戏 main 尚未调用，但 log4j 已随游戏类加载器就绪，
        // Logger.getLogger() 会自行初始化并挂上文件 appender。
        PlatformLog.enableFileLogging();

        // ── 装配字节码转换管线（必须在任何 Minecraft 类被加载之前） ──────
        //
        // 【根因记录】此前生产启动路径从未装配 TransformerPipeline /
        // ClassTransformInterceptor —— 它们只存在于测试里。于是
        // MinecraftClassLoader 以纯 URLClassLoader 运行，全部平台
        // 转换器（tick 接线、品牌替换、主界面注入）在生产中一次都没
        // 执行过，且日志无任何报错：又一次静默失效。
        //
        // 表现就是用户报告的两个症状：
        //   1. F3 显示原版客户端（ClientBrandRetriever 未被替换）；
        //   2. 主界面没有 mod 列表入口（TitleScreen#init 未被注入）。
        //
        // 时机：canLoad() 在 createGameClassLoader 里已定义过入口类
        //（不在转换目标内，无碍）；此后所有 net.minecraft.* 类都必须
        // 在拦截器就位后才加载 —— registerCreativeTabs 等 Mod 注册
        // 动作发生在 hook.invokeMinecraftMain 内部，晚于本调用。
        installTransformPipeline(gameClassLoader);

        // 主界面 mod 列表的数据源 —— 必须在主界面显示之前设置
        armModListProvider(classLoaderManager);

        // 客户端服务装配（屏幕 / 按键）—— 必须在任何 Mod 代码运行前。
        // 服务端环境不装配：两个服务的 register 保持显式不可用，
        // 在服务器上调 open()/register() 会得到清晰报错而非静默无效。
        if (detectedEnvironment == RuntimeEnvironment.CLIENT) {
            org.loader.runtime.minecraft.client.screen.ScreenServiceBridge.install();
            org.loader.runtime.minecraft.client.input.KeyBindingServiceBridge.install();
        }

        // 配置服务 —— 无 MC 依赖，客户端与服务端都可用。
        org.loader.api.config.ConfigService.registerProvider(
                new org.loader.runtime.config.PropertiesConfigStore());

        // F3 第一行的 launchedVersion 来自该系统属性；未设置时原样
        // 显示 "null"。仅在外部启动器未提供时补上，不覆盖外部值。
        if (System.getProperty("minecraft.launcher.brand") == null) {
            System.setProperty("minecraft.launcher.brand", "Mili-loader");
        }

        // 注册为 bootstrap 的外部资源 —— 失败/停止时随之释放
        EntryPointHook hook = new EntryPointHook();
        hook.install(gameClassLoader, runtime, classLoaderManager, detectedEnvironment);
        hook.invokeMinecraftMain(gameClassLoader, args != null ? args : new String[0]);
    }

    /**
     * 构建转换注册表与流水线，并安装到游戏 ClassLoader 的拦截器上。
     *
     * <p>平台自身的转换器全部在此登记（modId = null，CORE 阶段）。
     * 精确索引只登记转换器实际目标的类 —— 其余约一万个 Minecraft 类
     * 在热路径上零开销直接放行。
     */
    private void installTransformPipeline(MinecraftClassLoader gameClassLoader) {
        TransformerRegistry registry =
                new TransformerRegistry(MiliSymbol.MINECRAFT_VERSION);
        registry.register(new MiliTickTransformer(), null);
        registry.register(new MiliClientBrandTransformer(), null);
        registry.register(new MiliTitleScreenTransformer(), null);
        // 客户端专属：按键轮询挂在 Minecraft#tick 上（与 world 无关）。
        if (detectedEnvironment == RuntimeEnvironment.CLIENT) {
            registry.register(new org.loader.runtime.minecraft.transform.MiliClientTickTransformer(),
                    null);
            registry.indexClass(MiliSymbol.CLIENT_TICK.owner());
        }
        registry.indexClass(MiliSymbol.SERVER_TICK.owner());
        registry.indexClass(MiliSymbol.CLIENT_BRAND.owner());
        registry.indexClass(MiliSymbol.TITLE_SCREEN_INIT.owner());
        registry.seal();

        TransformerPipeline pipeline = TransformerPipeline.builder()
                .registry(registry)
                .verifyEnabled(true)   // 生产保持验证：VerifyError 前置为启动报错
                .build();

        TransformationEnvironment env = new TransformationEnvironment(
                gameClassLoader,
                detectedVersion,
                toEnvironmentValue(detectedEnvironment),
                VersionInfo.PLATFORM_ID);

        gameClassLoader.setInterceptor(
                new ClassTransformInterceptor(pipeline, env, null));

        PlatformLog.info("[Mili] Transform pipeline installed: "
                + registry.diagnostics());
    }

    private static TransformationEnvironment.RuntimeEnvironmentValue toEnvironmentValue(
            RuntimeEnvironment env) {
        if (env == RuntimeEnvironment.CLIENT) {
            return TransformationEnvironment.RuntimeEnvironmentValue.CLIENT;
        }
        if (env == RuntimeEnvironment.DEDICATED_SERVER) {
            return TransformationEnvironment.RuntimeEnvironmentValue.DEDICATED_SERVER;
        }
        return TransformationEnvironment.RuntimeEnvironmentValue.UNKNOWN;
    }

    /**
     * 为主界面 Mods 按钮提供 mod 列表（modId + 首个 JAR 文件名）。
     * 列表在按钮被点击时才读取 —— 此处只装配数据源。
     */
    private void armModListProvider(ModClassLoaderManager classLoaderManager) {
        TitleScreenDispatch.setModListProvider(() -> {
            List<String> lines = new ArrayList<>();
            try {
                for (ModClassLoader mcl : classLoaderManager.all()) {
                    String source = "";
                    for (Path p : mcl.modSources()) {
                        if (p != null && p.toString().endsWith(".jar")) {
                            source = " (" + p.getFileName() + ")";
                            break;
                        }
                    }
                    lines.add(mcl.modId() + source);
                }
            } catch (Throwable t) {
                PlatformLog.warn("[Mili] 读取 mod 列表失败: " + t);
            }
            return lines;
        });
    }
}