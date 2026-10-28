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
import org.loader.runtime.minecraft.client.screen.ModListScreenDispatch;
import org.loader.runtime.modlist.ModEntry;
import org.loader.runtime.modlist.ModListSnapshot;
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

    /**
     * 各 Mod 声明的方块清单（modId → 方块路径），由 {@code LoaderMain} 在
     * 调用完全部 Mod 入口后、启动游戏前注入。
     *
     * <p>这是创造栏标签与资源注入的数据源。走实例注入而非 LoaderMain
     * 静态字段：静态可变状态会让多次启动（测试、未来热重启）之间的
     * 清单互相泄漏，且依赖方向会被藏起来 —— 现在数据流向与依赖方向
     * 一致：LoaderMain → GameProvider → EntryPointHook。
     */
    private volatile java.util.Map<String, java.util.List<String>> declaredBlocks =
            java.util.Map.of();

    /**
     * 各 Mod 声明的物品清单 —— 创造栏标签的第二类内容（第一类是方块）。
     *
     * <p>只传方块会让「纯物品 Mod」没有页签，也会让 Aurora 的
     * {@code aurora_shard} 不出现在自己的页里。
     */
    private volatile java.util.Map<String, java.util.List<String>> declaredItems =
            java.util.Map.of();

    /**
     * 注入各 Mod 声明的方块清单。必须在 {@link #launch} 之前调用；
     * null 视为空映射。
     */
    public void declaredBlocks(java.util.Map<String, java.util.List<String>> byMod) {
        this.declaredBlocks = byMod == null
                ? java.util.Map.of() : java.util.Map.copyOf(byMod);
    }

    /** 注入各 Mod 声明的物品清单。必须在 {@link #launch} 之前调用。 */
    public void declaredItems(java.util.Map<String, java.util.List<String>> byMod) {
        this.declaredItems = byMod == null
                ? java.util.Map.of() : java.util.Map.copyOf(byMod);
    }

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

        // 转换管线必须在本方法内、canLoad 之前装好（根因记录）：
        //
        // 此前它装在 launch() 里，而 LoaderMain 的时序是
        //   createGameClassLoader → 加载 Mod 并调 initialize() → launch()
        // Mod 的 initialize() 会经由注册 API 触发一批 net.minecraft.* 类
        // 加载 —— 那时拦截器还没就位，这批类从 super.findClass 走
        // URLClassLoader 标准路径定义，带上了签名 jar 的条目证书；拦截器
        // 就位后的类走拦截路径。canLoad 同理：它在装配前就定义了入口类。
        //
        // 除了转换窗口缺口本身，证书混装在 JDK 的包级一致性校验
        // （ClassLoader.checkCerts）上是硬错误：同包两类证书不同 →
        // SecurityException，游戏根本起不来。把装配提前到第一个游戏类
        // 定义之前，窗口从根上消失。
        installTransformPipeline(gameClassLoader);

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

        // 开启平台文件日志 → <gameDir>/logs/mili-platform.log。
        //
        // 【为什么必须开】平台此前全靠 System.out，输出只在控制台。
        // 于是排查时「latest.log 里零条 [Mili]」被误读成「代码没执行」，
        // 连续两轮把诊断方向带偏 —— 日志源缺失本身就是一种故障。
        //
        // 【为什么不写 latest.log】旧实现反射调游戏的 log4j，但本类加载在
        // 平台类加载器上，log4j 在游戏类加载器里 —— Class.forName 必然
        // ClassNotFoundException 且被吞，结果建出空文件（真实事故）。
        // 现在写平台自有文件，无加载器可见性依赖，见 PlatformLog 类注释。
        PlatformLog.enableFileLogging(gameDir.resolve("logs"));

        // ── 字节码转换管线 ────────────────────────────────────────────
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
        // 【时机演变】管线最初装在这里，但 Mod 的 initialize() 发生在
        // launch() 之前且已触发游戏类加载 —— 转换窗口与包内证书一致性
        // 都被破坏（SecurityException 根因，见 createGameClassLoader
        // 与 MinecraftClassLoader.findClass 的根因记录）。装配已上移到
        // createGameClassLoader 内、canLoad 之前；本方法不再重复装配。

        // 主界面 mod 列表的数据源 —— 必须在主界面显示之前设置
        armModListProvider(classLoaderManager);

        // 客户端服务 / 配置服务 / 存储服务 / 指标服务的装配统一上移到
        // LoaderMain.installRuntimeServices() —— 它们全部在 Mod 的
        // initialize() 之前就必须可用。放在 launch() 里（Mod 初始化之后）
        // 会让 Mod 看到「服务未装配」，而那不是环境问题，是装配时序错误。

        // 配置服务已上移到 LoaderMain.installRuntimeServices()：
        // 它的消费方是 Mod 的 initialize()，而 initialize() 发生在 launch() 之前。
        // 在这里重复注册会新建一个 PropertiesConfigStore 实例，
        // 把 Mod 在 initialize() 里已经加载的配置对象<b>整体替换掉</b> ——
        // 「内存已改、磁盘未存」的状态会静默丢失。

        // F3 第一行的 launchedVersion 来自该系统属性；未设置时原样
        // 显示 "null"。仅在外部启动器未提供时补上，不覆盖外部值。
        if (System.getProperty("minecraft.launcher.brand") == null) {
            System.setProperty("minecraft.launcher.brand", "Mili-loader");
        }

        // 注册为 bootstrap 的外部资源 —— 失败/停止时随之释放
        EntryPointHook hook = new EntryPointHook();
        hook.install(gameClassLoader, runtime, classLoaderManager, detectedEnvironment);
        // 方块/物品清单在游戏 main 之前注入 —— 创造栏标签与资源注入都依赖它。
        hook.declaredBlocks(declaredBlocks);
        hook.declaredItems(declaredItems);
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

            // 创造栏页签布局：把 Mod 的哨兵列排到面板右侧，
            // 并修正 extractTabButton 的 clamp 越界。
            registry.register(
                    new org.loader.runtime.minecraft.transform.MiliCreativeTabLayoutTransformer(),
                    null);
            registry.indexClass(
                    org.loader.runtime.minecraft.transform.MiliCreativeTabLayoutTransformer.SCREEN);

            // 主菜单液态玻璃转场（docs/design/LIQUID_GLASS_MENU_TRANSITION.md）：
            // TitleScreen#init RETURN 接管单人/多人按钮，
            // Screen#extractRenderStateWithTooltipAndSubtitles RETURN 每帧绘制玻璃。
            registry.register(
                    new org.loader.runtime.minecraft.transform.MiliMenuTransitionTransformer(),
                    null);
            registry.indexClass(MiliSymbol.SCREEN_EXTRACT_RENDER_STATE.owner());
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
        // 旧路径：AlertScreen 的纯文本列表。保留给自绘界面失败时的降级。
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

        // 主路径：结构化快照，供自绘列表界面使用。
        //
        // 快照在「点击按钮」这一刻构建一次 —— 因此这里不需要缓存，
        // 也不需要担心 loadClass 统计的并发一致性：调用点在客户端主线程，
        // 此刻没有类加载正在进行。
        ModListScreenDispatch.setSource(
                () -> buildModListSnapshot(classLoaderManager));
    }

    /**
     * 构建一次 Mod 列表快照。
     *
     * <p>刻意做成「每次点击构建」而不是「启动时构建后缓存」：
     * 类加载数量、堆占用、生命周期状态都是<b>会变的</b>，
     * 一张启动时的快照会让玩家看到过时数据，而这恰恰是诊断界面
     * 最不该出现的错误 —— 用户拿它来判断「我的 mod 到底怎么了」。
     */
    private ModListSnapshot buildModListSnapshot(ModClassLoaderManager classLoaderManager) {
        List<ModEntry> entries = new ArrayList<>();
        try {
            for (ModClassLoader mcl : classLoaderManager.all()) {
                org.loader.runtime.mod.ModManifest m = mcl.manifest();
                String source = "";
                for (Path p : mcl.modSources()) {
                    if (p != null && p.toString().endsWith(".jar")) {
                        source = p.getFileName().toString();
                        break;
                    }
                }
                String state = mcl.isClosed() ? "UNLOADED" : "ACTIVE";
                int blocks = declaredBlocks
                        .getOrDefault(mcl.modId(), List.of()).size();
                entries.add(new ModEntry(
                        m.id(),
                        m.name(),
                        m.version(),
                        m.description(),
                        m.author(),
                        source,
                        state,
                        mcl.loadedClasses().size(),
                        blocks,
                        false));
            }
        } catch (Throwable t) {
            PlatformLog.warn("[Mili] 构建 mod 快照失败（列表可能不完整）: " + t);
        }

        java.lang.Runtime rt = java.lang.Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        int threads = java.lang.management.ManagementFactory
                .getThreadMXBean().getThreadCount();

        return new ModListSnapshot(
                VersionInfo.CURRENT_VERSION,
                VersionInfo.ABI_VERSION,
                VersionInfo.TARGET_MINECRAFT,
                String.valueOf(VersionInfo.TARGET_JAVA),
                detectedEnvironment != null ? detectedEnvironment.name() : "UNKNOWN",
                used,
                max,
                threads,
                uptimeMillis(),
                entries);
    }

    /** 平台启动时刻 —— 用于列表底栏展示运行时长。 */
    private static final long START_NANOS = System.nanoTime();

    private static long uptimeMillis() {
        return (System.nanoTime() - START_NANOS) / 1_000_000L;
    }
}