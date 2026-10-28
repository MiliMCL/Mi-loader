package org.loader.loader;

import org.loader.loader.classloader.MinecraftClassLoader;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.loader.config.LoaderConfig;
import org.loader.loader.discovery.MinecraftDiscovery;
import org.loader.loader.discovery.ModDiscovery;
import org.loader.loader.game.GameProvider;
import org.loader.loader.game.MinecraftGameProvider;
import org.loader.runtime.diagnostics.LifecycleAudit;
import org.loader.runtime.diagnostics.ModOwners;
import org.loader.runtime.diagnostics.ShutdownAudit;
import org.loader.runtime.diagnostics.StartupTimeline;
import org.loader.runtime.diagnostics.TickWatchdog;
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
import java.nio.file.Files;
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
    /**
     * 启动阶段计时器。
     *
     * <p>由 {@link #main(String[])} 创建后传入，而不是在这里 {@code new} ——
     * 起点必须早于 {@code parseArgs}，否则「解析参数 → 构造 Loader」这段
     * 会整体掉进"未归因"，而它恰恰是最容易被服务启动器放大的一段
     * （分发包脚本、参数拼接错误都发生在那里）。
     */
    private final StartupTimeline timeline;

    /**
     * 生命周期审计环。失败时落盘，成为下次启动的线索。
     *
     * <p>刻意不在每次正常启动都写文件：正常路径已经有时间线和日志了，
     * 再写一份只会让 logs/ 变成没人看的垃圾堆。<b>只在失败时留下痕迹</b>，
     * 这份痕迹才有分量。
     */
    private final LifecycleAudit audit = new LifecycleAudit();

    /**
     * 关闭期审计的线程基线，取在 Mod 初始化<b>之前</b>。
     * 取在之后会让增量恒为 0，等于什么都没测。
     */
    private ShutdownAudit.Snapshot threadBaseline;

    private final org.loader.runtime.kernel.Runtime runtime;
    private ModClassLoaderManager classLoaderManager;
    private final GameProvider gameProvider;
    /** 强类型的 Minecraft Provider 引用；步骤 3 校验后赋值，供实例传值使用。 */
    private MinecraftGameProvider mcProvider;
    private Path gameDir;
    private final List<Mod> loadedMods = new ArrayList<>();

    /** 强类型的 provider 访问器；步骤 3 未执行前不可调用。 */
    private MinecraftGameProvider mcProvider() {
        if (mcProvider == null) {
            throw new IllegalStateException("GameProvider 尚未装配（步骤 3 未执行）");
        }
        return mcProvider;
    }

    public LoaderMain(LoaderConfig config) {
        this(config, new StartupTimeline("bootstrap"));
    }

    /**
     * 带时间线构造；包级可见，供测试注入一个已开始计时的实例。
     */
    LoaderMain(LoaderConfig config, StartupTimeline timeline) {
        this.config = config;
        this.timeline = java.util.Objects.requireNonNull(timeline, "timeline");
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

    /**
     * 启动参数里解析出来的、属于<b>平台</b>（而非 Minecraft）的选项。
     *
     * <p><b>为什么是包级可见而不是 private</b>：{@code parseArgs} 是平台
     * 启动路径上唯一决定「Minecraft 拿到什么参数」的地方，而参数拆分一旦
     * 出错，症状出现在很远处（Minecraft 找不到资源、静默不认得某个选项）。
     * {@code LaunchArgPassthroughTest} 需要直接断言拆分结果，因此
     * record 与 parseArgs 都放开到包级 —— 仍是包内可见，外部 API 不变。
     *
     * @param gameDir    Minecraft 所在目录（{@code <dist>/game}）
     * @param modsDir    Mod 所在目录（{@code <dist>/mods}）；未显式指定时
     *                   回退到 {@code gameDir/mods}
     * @param mcArgs     转发给 Minecraft main 的参数（已剔除平台选项，
     *                   并按需补上 {@code --gameDir}）
     */
    record LaunchOptions(Path gameDir, Path modsDir, String[] mcArgs) {
    }

    /**
     * 拆分平台选项与 Minecraft 参数。
     *
     * <p><b>为什么必须拆</b>：{@code args[0]} 是 gameDir，其余全部原样转发给
     * {@code net.minecraft.client.main.Main}。Minecraft 用 joptsimple 解析参数，
     * 遇到不认识的 {@code --xxx} 会直接抛 {@code UnrecognizedOptionException}
     * 并拒绝启动。所以平台自己的选项必须在转发前摘掉。
     *
     * <p>支持的平台选项：
     * <ul>
     *   <li>{@code --mili-mods <dir>} —— Mod 目录。分发包把Mod 放在
     *       {@code <dist>/mods}，而 gameDir 是 {@code <dist>/game}，
     *       两者不是同一个目录；不显式指定就会去扫一个不存在的
     *       {@code game/mods/}，结果是零个 Mod 且无任何报错。</li>
     * </ul>
     */
    static LaunchOptions parseArgs(String[] args) {
        Path gameDir = args.length > 0 ? Path.of(args[0]) : Path.of(".");
        Path modsDir = null;
        java.util.List<String> mc = new java.util.ArrayList<>(args.length);
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--mili-mods".equals(a)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(
                            "--mili-mods requires a directory argument");
                }
                modsDir = Path.of(args[++i]);
            } else if (a.startsWith("--mili-mods=")) {
                modsDir = Path.of(a.substring("--mili-mods=".length()));
            } else {
                mc.add(a);
            }
        }
        Path resolvedGameDir = gameDir.toAbsolutePath().normalize();
        Path resolvedMods = modsDir != null
                ? modsDir.toAbsolutePath().normalize()
                : resolvedGameDir.resolve("mods");

        // ── 注入 --gameDir ───────────────────────────────────────────────
        //
        // args[0] 只被平台消费，Minecraft 自己看不到它。而 Minecraft 在
        // 拿不到 --gameDir 时会把**当前工作目录**当游戏目录 —— 启动脚本
        // 在 bin/ 里执行 java，于是 gameDir 变成 bin/：
        //
        //   · 资源索引读 bin/assets/indexes/32.json，而资源其实下载在
        //     game/assets/ 下 → 索引读不到 → 界面背景图、多语言（lang）、
        //     全部音效（assets/sounds.json）一起消失
        //   · icons/icon_16x16.png 找不到
        //   · options.txt / saves / resourcepacks 全落在 bin/ 而不是 game/
        //
        // 关键点：Minecraft 的资源与存档目录是按 --gameDir 推导的，不是按
        // 「jar 在哪」。平台定位 jar 用 gameDir，交给游戏用 --gameDir，
        // 两者必须一致 —— 否则平台能找到游戏，游戏却在自己的目录里找资源。
        java.util.List<String> forwarded = new java.util.ArrayList<>(mc.size() + 2);
        boolean hasGameDir = mc.stream().anyMatch(a -> a.startsWith("--gameDir"));
        if (!hasGameDir) {
            forwarded.add("--gameDir");
            forwarded.add(resolvedGameDir.toString());
        }
        forwarded.addAll(mc);

        return new LaunchOptions(resolvedGameDir, resolvedMods,
                forwarded.toArray(new String[0]));
    }

    public static void main(String[] args) throws Exception {
        // 时间线必须先于 parseArgs 建立：启动参数解析、配置装配、Loader 构造
        // 这三步在任何 Mod 相关的工作之前，却是"改了启动脚本之后变慢"时
        // 最先要排除的嫌疑段。
        StartupTimeline timeline = new StartupTimeline("bootstrap");

        long parseStart = System.nanoTime();
        LaunchOptions opts = parseArgs(args);
        timeline.mark("parseArgs", parseStart);

        long configStart = System.nanoTime();
        LoaderConfig config = LoaderConfig.at(opts.gameDir(), opts.modsDir());
        timeline.mark("loadConfig", configStart);

        long constructStart = System.nanoTime();
        LoaderMain m = new LoaderMain(config, timeline);
        timeline.mark("constructLoader", constructStart);

        m.gameDir = opts.gameDir();
        m.launch(opts.mcArgs());
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
        PlatformLog.info("未在 " + gameDir + " 找到 Minecraft，开始自动安装 " + version);

        try {
            Class<?> installer = Class.forName("org.loader.installer.InstallerMain");
            Method main = installer.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[]{
                    "--game-dir", gameDir.toString(),
                    "--version", version
            });
            PlatformLog.info("Minecraft 安装完成，继续启动");
        } catch (ClassNotFoundException e) {
            PlatformLog.warn("提示: 分发包中缺少 mili-installer，"
                    + "请手动提供 Minecraft 安装到 " + gameDir);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            PlatformLog.warn("自动安装失败: " + cause.getMessage());
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
     *
     * <p>失败时先落盘时间线再抛出：一次启动失败发生在哪一段、前序阶段
     * 各花了多久，是排障时唯一能区分"Mod 写错了"与"平台自己慢"的证据。
     */
    public void launch(String[] mcArgs) throws Exception {
        try {
            launchTracked(mcArgs);
        } catch (Exception e) {
            timeline.closeAll();
            audit.record("platform.failed", null, String.valueOf(e));
            // 失败才落盘，且是追加：同一台机器上连续崩两次，两份墓碑都在。
            if (gameDir != null) {
                audit.dumpTo(gameDir.resolve("logs").resolve("mili-audit.log"), 64,
                        "=== " + java.time.LocalDateTime.now() + " 启动失败 ===");
            }
            // [A3 Crash Capsule] 结构化失败报告（logs/startup-report.json）
            // + 本地可选诊断包。调用内部全量兜底：报告生成失败绝不改变
            // 原始异常的传播路径。
            if (gameDir != null) {
                org.loader.loader.report.StartupReport.onLaunchFailure(
                        gameDir, e, timeline, getLoadedMods());
            }
            reportTimeline("启动失败");
            throw e;
        }
    }

    /**
     * 实际启动流程，每个阶段末尾打一个点。
     *
     * <p>打点位置的选择原则：<b>打在该阶段产生的副作用之后</b>，而不是
     * 之前。{@code locateGame} 的耗时里包含文件系统扫描，{@code createGameClassLoader}
     * 的耗时里包含 JAR 打开与主类可解析校验 —— 这些才是真正的成本项。
     */
    private void launchTracked(String[] mcArgs) throws Exception {
        // 停滞报告接到平台日志（同时写控制台与 logs/mili-platform.log）。
        // 默认 sink 是 stderr —— 在启动器吞掉 stderr 的环境里那份报告会丢，
        // 而"卡住"恰好是最需要留下证据的场景。
        TickWatchdog.installSink(PlatformLog::warn);
        audit.record("platform.start", null, "bootstrap begin");

        ensureMinecraftPresent();
        timeline.mark("ensureMinecraft");

        // ── 2. 发现 Minecraft ────────────────────────────────────────────
        List<Path> gameClasspath = gameProvider.locateGame(gameDir);
        timeline.mark("locateGame");

        // ── 3. 建立唯一的 MinecraftClassLoader ───────────────────────────
        if (!(gameProvider instanceof MinecraftGameProvider mcProvider)) {
            throw new IllegalStateException(
                    "当前 GameProvider 不支持显式 ClassLoader 拓扑: "
                            + gameProvider.getClass().getName());
        }
        MinecraftClassLoader gameCL = mcProvider.createGameClassLoader(gameClasspath);
        classLoaderManager = new ModClassLoaderManager(gameCL, gameDir, config.getModsPath());
        this.mcProvider = mcProvider;
        timeline.mark("createGameClassLoader");

        // ── 4. 发现 Mod ──────────────────────────────────────────────────
        //
        // config 已由 main() 按--mili-mods 装配好 Mod 目录；这里必须复用
        // 它，不能重新 LoaderConfig.at(gameDir) —— 那会把 Mod 目录重置回
        // gameDir/mods，在分发包布局下指向一个不存在的目录。
        ModDiscovery modDiscovery = ModDiscovery.scan(config);
        timeline.mark("scanMods");

        List<ModManifest> discovered = modDiscovery.discover();
        timeline.mark("discoverManifests");

        List<ModManifest> resolved = modDiscovery.resolveDependencies(discovered);
        timeline.mark("resolveDependencies");

        reportDiscovery(resolved);
        // 单独打点：reportDiscovery 会 list() 一遍 Mod 目录，200 个 JAR 时
        // 不是零成本。混进 runtimeStart 会让人误以为运行时启动慢。
        timeline.mark("reportDiscovery");

        runtime.start();
        timeline.mark("runtimeStart");

        // ── 6. 严格平台版本校验 ───────────────────────────────────────────
        validateVersionBindings(resolved);
        timeline.mark("validateVersionBindings");

        // ── 7. 创建 ModClassLoader（parent = gameCL） ──────────────────────
        //
        // 显式传入发现阶段定位到的代码源：Mod 的 jar 文件名与 mod id 之间
        // 没有任何必然关系（`aurora-1.0.0.jar` 里装的是 `miliaurora`），
        // 靠文件名猜会让 Mod「被发现但加载不了」。
        Map<String, Path> modSources = modDiscovery.sources();
        for (ModManifest manifest : resolved) {
            Scope modScope = runtime.rootScope().createChild("mod:" + manifest.id());
            ModClassLoader mcl = classLoaderManager.create(
                    manifest, modSources.get(manifest.id()));
            // 归因边界在这里建立：ClassLoader 与 Mod 一一对应，之后任何由
            // 这个 CL 加载的类都能反查到 modId，Mod 侧不需要做任何事。
            ModOwners.register(mcl, manifest.id());
            Mod mod = new Mod(manifest, modScope, mcl);
            loadedMods.add(mod);
            modScope.registerResource(mod);
            // 交给生命周期管理器统一推进/关闭：这是 Scope 顺序确定性的唯一来源。
            runtime.lifecycleManager().registerScope(modScope);
            // Scope 的每次迁移都发到平台总线，让 Mod 之间能观察彼此的就绪。
            modScope.addListener((scope, from, to) ->
                    runtime.events().post(new org.loader.api.event.ModLifecycleEvent(
                            manifest.id(),
                            apiLifecycleState(from),
                            apiLifecycleState(to))));
        }
        timeline.mark("createModClassLoaders");

        // ── 7.5 装配运行时服务 ────────────────────────────────────────────
        //
        // 时序是硬约束：Mod 的 initialize() 里就会调用 storage()/metrics()，
        // 因此这两个服务必须在 invokeModEntrypoints() **之前**装配。
        // 之前把它们放在 MinecraftGameProvider.launch() 里 —— 那是 Mod
        // 初始化之后，于是任何用存储的 Mod 都会以
        // StorageUnavailableException 加载失败。
        installRuntimeServices();
        timeline.mark("installServices");

        // ── 8. 调用 Mod 入口 ─────────────────────────────────────────────
        //
        // 这一段自带计时（initializeMods 区间 + 每个 Mod 一行），
        // 因此这里不再额外打点 —— 否则同一段时间会被计两次，
        // 「未归因」会被算成负数。
        //
        // 线程基线必须在 Mod 初始化之前抓：之后抓等于把 Mod 新建的线程
        // 也算进基线，关闭期增量恒为 0，审计变成摆设。
        threadBaseline = ShutdownAudit.capture();
        invokeModEntrypoints();

        // 快照方块清单：创造栏标签在 EntryPointHook 里创建。清单经
        // MinecraftGameProvider 实例传递（见 mcProvider.declaredBlocks），
        // 不再走静态字段 —— 静态可变状态会让多次启动（测试/热重启）
        // 之间的清单互相泄漏。
        if (!declaredBlocksByMod.isEmpty() || !declaredItemsByMod.isEmpty()) {
            PlatformLog.info("Declared content: blocks=" + declaredBlocksByMod
                    + " items=" + declaredItemsByMod);
        }
        mcProvider().declaredBlocks(Map.copyOf(declaredBlocksByMod));
        mcProvider().declaredItems(Map.copyOf(declaredItemsByMod));

        // 契约门面接通：Mili.mod(...) / Mili.events() / Mili.modIds()
        //
        // 在此之前 Mili.registerProvider 从未被调用过 —— 整个
        // org.loader.api.Mili 类在生产路径上是死的，任何 Mod 调
        // Mili.mod(this) 都只会拿到 Optional.empty()。
        installMiliFacade();

        // 全部 Mod 初始化完成 —— 发布 READY，让 Mod 能在「大家都就绪」
        // 这个时刻做一次全局汇总。
        runtime.publishPlatformStage(
                org.loader.api.event.PlatformStage.READY, loadedMods.size());

        PlatformLog.info("ClassLoader 拓扑:\n" + classLoaderManager.diagnostics());
        timeline.mark("declareContent");

        // ── 9. 启动 Minecraft ────────────────────────────────────────────
        //
        // 报告放在这里而不是 shutdown 之后：gameProvider.launch() 会一直
        // 阻塞到游戏退出，等到那时才打印，等于在退出时才告诉用户
        // 「你三分钟前那次启动慢在哪」。这也是 TTCF 口径的终点 ——
        // 平台能让玩家看到的第一帧，取决于它把控制权交给游戏的速度。
        reportTimeline("平台启动完成");
        audit.record("platform.ready", null,
                "mods=" + loadedMods.size() + " bootstrap="
                        + (timeline.totalNanos() / 1_000_000L) + "ms");
        try {
            gameProvider.launch(gameDir, runtime, classLoaderManager, gameClasspath, mcArgs);
        } finally {
            shutdown();
        }
    }

    /**
     * 输出启动时间线。
     *
     * <p>为什么用一次 {@code info} 而不是逐行打印：这份报告的价值在于
     * <b>整体形状</b>（哪一段占了多大比例），拆成十几条带时间戳的日志反而
     * 会切断阅读。日志文件里它仍是一段连续文本，可以直接 grep 出来贴进 issue。
     */
    private void reportTimeline(String stage) {
        PlatformLog.info("启动时间线（" + stage + "）:\n" + timeline.render());
    }

    /**
     * 打印 Mod 发现结果 —— 并在「目录里有JAR 但一个都没被识别」时报警。
     *
     * <p>这个检查专门针对一种特别难查的失效：Mod 放错目录时
     * {@link ModDiscovery} 不抛异常、不打警告，只是安静地返回空列表，
     * 于是游戏正常启动、Mod 内容一片空白，而启动日志里看不出任何异常。
     * 用户能看到的唯一线索就是「我明明放了 Mod」。
     */
    private void reportDiscovery(List<ModManifest> mods) {
        Path modsDir = config.getModsPath();
        long jarCount;
        boolean dirExists = Files.isDirectory(modsDir);
        try (java.util.stream.Stream<Path> files = Files.list(modsDir)) {
            jarCount = files.filter(p -> p.toString().endsWith(".jar")
                    || p.toString().endsWith(".zip")).count();
        } catch (Exception e) {
            jarCount = -1;
        }

        PlatformLog.info("Mod 目录: " + modsDir
                + "  (JAR/ZIP=" + (jarCount < 0 ? "?" : jarCount)
                + ", 识别=" + mods.size() + ")");
        for (ModManifest m : mods) {
            PlatformLog.info("  发现 Mod: " + m.id() + " v" + m.version());
        }

        // 目录根本不存在 —— 分发包布局下最常见的失效：Mod 在<dist>/mods，
        // 而 gameDir 是 <dist>/game。不显式报出来，玩家只会看到
        // 「游戏正常启动了，但什么都没有」。
        if (!dirExists) {
            PlatformLog.warn("警告: Mod 目录不存在: " + modsDir
                    + "\n  这会让所有 Mod 被静默跳过（游戏仍会正常启动）。"
                    + "\n  分发包布局下 Mod 应放在 <dist>/mods，启动脚本需传 --mili-mods <dir>。");
            return;
        }

        if (jarCount > 0 && mods.isEmpty()) {
            PlatformLog.warn("警告: " + modsDir + " 里有 " + jarCount
                    + " 个 JAR，但一个 Mod 都没被识别。"
                    + "\n  Mod JAR 必须包含 META-INF/mod.json 或 mod.json，"
                    + "且 mod.json 里的 id / entrypoint 必填项不能为空。");
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
        timeline.open("initializeMods");
        for (Mod mod : loadedMods) {
            long modInitStart = System.nanoTime();
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
                // 装配模式：接平台总线、服务代理、权限、tick 容器与数据目录。
                // 独立模式（new ModContext(mod)）只给每个 Mod 一条私有总线，
                // 那样 Mod 收不到任何平台事件 —— 这里必须传 runtime。
                ModContext runtimeCtx = new ModContext(
                        mod, runtime, runtime.tickEngine(),
                        gameDir.resolve("config").resolve("mili").resolve("data"));
                // 环境必须在 Mod 的 initialize() 之前注入。
                // 旧实现让 runtime 自己兜底成 DEDICATED_SERVER，而构造时
                // 没人设置 —— 结果每个客户端 Mod 都以为自己在服务器上，
                // 静默跳过全部客户端功能。见 ModContext.setEnvironment。
                runtimeCtx.setEnvironment(gameProvider.getEnvironment());

                // 平台侧装入 Minecraft 注册表契约实现：这一步必须在 Mod
                // 的 initialize() 之前完成，否则 Mod 调 registry() 只会拿到 null。
                // 顺带把 Mod 自己的 jar 传下去：注册方块时 loader 据此从
                // blockstate 推断属性（BlockSpec 还没有属性 API）。
                RegistryBinder.bind(runtimeCtx, mod, firstModJar(mcl));

                // 传给 Mod 的是契约接口的实现，不是 runtime 的具体类。
                org.loader.api.ModContext apiCtx = new ApiModContext(runtimeCtx);
                // 缓存契约视图：ApiModContext 构造时会把自己作为 Scope 资源注册
                // （用于在 Mod 停止时注销平台总线上的监听器）。每次 Mili.mod(...)
                // 都新建一个，就会往同一个 Scope 里无限追加资源。
                apiContexts.put(mod.id(), apiCtx);
                ((org.loader.api.Mod) instance).initialize(apiCtx);

                modContexts.put(mod.id(), runtimeCtx);

                // 记下该 Mod 声明的方块/物品路径 —— 创造栏标签需要它，
                // 且这是唯一能拿到确切清单的时刻（见字段注释）。
                collectDeclaredBlocks(mod.id(), apiCtx);
                collectDeclaredItems(mod.id(), apiCtx);

                PlatformLog.info("Mod initialized: " + mod.id() + " -> " + entrypoint);

                // 初始化成功后把 Scope 推进到 RUNNING。
                //
                // 这一步以前完全缺失：Mod 的 Scope 永远停在 DISCOVERED，
                // 于是
                //   · lifecycle().state() 恒为 DISCOVERED，
                //     ABI 的 allowsNewWork() 对它返回 false；
                //   · 生命周期监听器一次都不会被调用（没有迁移就没有事件）；
                //   · LifecycleManager.startAll() 看不到任何受管 Scope。
                // 症状是「Mod 的 lifecycle 回调永远不触发」，且没有任何日志。
                mod.scope().driveToRunning();

                // 只记成功路径：失败的 Mod 会立刻抛出并终止整个启动，
                // 那时最重要的信息是异常本身，不是它花了多久。用 mark 而不是
                // open/close，是为了不给这段套 try/finally —— 逐 Mod 的
                // 失败点有四处，套上去只会把异常处理结构搅浑。
                timeline.mark("init:" + mod.id(), modInitStart);

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
        timeline.close();
    }

    /** 已装配的 runtime ModContext，供关闭阶段与诊断使用。 */
    private final Map<String, ModContext> modContexts = new LinkedHashMap<>();

    /**
     * 已装配的契约视图（每个 Mod 一个，构造时创建）。
     *
     * <p>必须缓存而不是每次现造：{@code ApiModContext} 会把事件视图注册为
     * Mod 的 Scope 资源，每次新建都会追加一条资源记录 ——
     * 一个频繁调用 {@code Mili.mod(...)} 的 Mod 会让 Scope 的资源表无限增长，
     * 且关闭时对同一批监听器反复尝试注销。
     */
    private final Map<String, org.loader.api.ModContext> apiContexts = new LinkedHashMap<>();

    /** per-mod 持久化存储；在 Mod 初始化之前装配，在关停时落盘。 */
    private org.loader.runtime.storage.ModStorageService storageService;

    /**
     * 装配运行时服务（存储 + 指标）。
     *
     * <p>幂等：重复调用只会重新注册同一批 provider。
     */
    private void installRuntimeServices() {
        storageService = org.loader.runtime.storage.ModStorageService.install(
                gameDir.resolve("config").resolve("mili").resolve("storage"));
        PlatformLog.info("[Mili] 存储服务: "
                + (storageService.persistent()
                ? storageService.directory().toString()
                : "仅内存（目录不可写）"));

        // 配置服务 —— 必须在 Mod 的 initialize() 之前装配。
        //
        // 以前它装在 MinecraftGameProvider.launch() 里，也就是 Mod 初始化
        // <b>之后</b>。于是任何在 initialize() 里调用 ConfigService.load()
        // 的 Mod 都会收到 ConfigUnavailableException，而错误信息说的是
        // 「平台尚未启动到运行期」—— 与实际原因（装配顺序）完全不符。
        org.loader.api.config.ConfigService.registerProvider(
                new org.loader.runtime.config.PropertiesConfigStore(
                        gameDir.resolve("config").resolve("mili")));
        PlatformLog.info("[Mili] 配置服务: "
                + gameDir.resolve("config").resolve("mili"));

        // 事件监听器异常接到平台日志 —— 否则「某个 Mod 的监听器每 tick 抛一次」
        // 只会在内存里留一个计数，日志里一条都没有。
        runtime.events().setErrorSink((busId, listener, event, error) ->
                PlatformLog.warn("事件监听器异常 [" + busId + "] "
                        + event.getClass().getName() + " -> "
                        + listener.getClass().getName() + ": " + error));

        // 客户端服务（屏幕 / 按键）也必须在这里装配。
        //
        // 它们的消费方同样是 Mod 的 initialize()：一个 Mod 想在初始化阶段
        // 注册快捷键或声明界面，调用点就在这里。之前这两个 bridge 装在
        // MinecraftGameProvider.launch() 里 —— Mod 初始化之后，于是
        // KeyBindingService 一直「未装配」，Mod 只看到一句
        // 「当前环境没有客户端输入系统」。而那与事实完全不符：
        // 环境检测早就确定了这是 CLIENT。
        if (gameProvider.getEnvironment() == org.loader.runtime.RuntimeEnvironment.CLIENT) {
            org.loader.runtime.minecraft.client.screen.ScreenServiceBridge.install();
            org.loader.runtime.minecraft.client.input.KeyBindingServiceBridge.install();
            PlatformLog.info("[Mili] 客户端服务已装配: Screen / KeyBinding");
        }

        org.loader.runtime.diagnostics.RuntimeMetrics.install(
                () -> loadedMods.size(),
                () -> {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    if (classLoaderManager != null) {
                        for (ModClassLoader mcl : classLoaderManager.all()) {
                            counts.put(mcl.modId(), mcl.loadedClasses().size());
                        }
                    }
                    return counts;
                },
                // 以前这里是 () -> 0L / () -> 0d：指标服务装上了，但 tick 数据
                // 永远是 0。Mod 在界面上显示「0 ticks / 0.00ms」，看起来像
                // 「游戏没跑」，实际只是没人接线。TickEngine 把每个 tick 写进
                // Runtime 的性能采样器，这里读同一份数据。
                () -> runtime.performance().tickCount(),
                () -> runtime.performance().averageTickMs());
        PlatformLog.info("[Mili] 指标服务已装配");
    }

    /**
     * 每个 Mod 声明的方块路径（相对其命名空间），供创造栏标签使用。
     *
     * <p><b>为什么在这里采集</b>：这是唯一能拿到确切清单的时刻 ——
     * {@code Mod.initialize()} 刚返回，Mod 声明过的方块都在
     * {@code ApiMinecraftRegistry} 里。事后反查游戏方块注册表做不到这一点，
     * 因为那里同时装着全部原版方块，无法区分来源。
     */
    private final Map<String, List<String>> declaredBlocksByMod = new LinkedHashMap<>();

    /**
     * 本次启动中各 Mod 声明的物品路径（modId → 相对命名空间的路径）。
     *
     * <p>与 {@link #declaredBlocksByMod} 分开收集：创造栏标签要同时填充
     * 方块（自动补 BlockItem）与显式物品（如 Aurora 的 {@code aurora_shard}）。
     * 只传方块会得到「页签里只有方块」的半成品。
     */
    private final Map<String, List<String>> declaredItemsByMod = new LinkedHashMap<>();

    /** 本次启动中各 Mod 声明的方块路径（modId → 相对命名空间的路径）。 */
    public Map<String, List<String>> declaredBlocksSnapshot() {
        return Map.copyOf(declaredBlocksByMod);
    }

    /** 某Mod 声明的方块路径；未声明过则返回空列表。 */
    public List<String> declaredBlocksOf(String modId) {
        List<String> paths = declaredBlocksByMod.get(modId);
        return paths == null ? List.of() : List.copyOf(paths);
    }

    /**
     * 取该 Mod 的第一个 jar 源，供 blockstate 属性推断用。
     *
     * <p>开发态目录布局（{@code mods/&lt;id&gt;/}）没有单一 jar，返回 null ——
     * 推断自动降级为无属性，不影响注册。jar 里有多个源时取第一个：
     * 标准布局下一个 Mod 只有一个 jar。
     */
    private static java.nio.file.Path firstModJar(ModClassLoader mcl) {
        if (mcl == null) {
            return null;
        }
        for (java.nio.file.Path p : mcl.modSources()) {
            if (p.toString().endsWith(".jar")) {
                return p;
            }
        }
        return null;
    }

    /**
     * 从契约上下文里取出该 Mod 声明的方块路径。
     *
     * <p>失败只记日志：清单只用于创造栏标签，缺了不影响方块本身可用。
     */
    private void collectDeclaredBlocks(String modId, org.loader.api.ModContext apiCtx) {
        try {
            org.loader.api.registry.MinecraftRegistry reg = apiCtx.registry();
            if (reg == null) {
                return;
            }
            List<String> paths = new ArrayList<>();
            for (org.loader.api.world.BlockHandle handle : reg.blocks()) {
                if (modId.equals(handle.modId())) {
                    paths.add(handle.path());
                }
            }
            if (!paths.isEmpty()) {
                declaredBlocksByMod.put(modId, paths);
            }
        } catch (RuntimeException e) {
            PlatformLog.warn("提示: 无法记录 " + modId
                    + " 的方块清单（创造栏将为空）: " + e);
        }
    }

    /**
     * 从契约上下文里取出该 Mod 声明的物品路径（含方块物品）。
     *
     * <p>与方块清单同理：只用于创造栏标签，失败不阻断 Mod 加载。
     */
    private void collectDeclaredItems(String modId, org.loader.api.ModContext apiCtx) {
        try {
            org.loader.api.registry.MinecraftRegistry reg = apiCtx.registry();
            if (reg == null) {
                return;
            }
            List<String> paths = new ArrayList<>();
            for (org.loader.api.registry.ItemHandle handle : reg.items()) {
                if (modId.equals(handle.modId())) {
                    paths.add(handle.path());
                }
            }
            if (!paths.isEmpty()) {
                declaredItemsByMod.put(modId, paths);
            }
        } catch (RuntimeException e) {
            PlatformLog.warn("提示: 无法记录 " + modId
                    + " 的物品清单（创造栏将缺少物品）: " + e);
        }
    }

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
        // 契约视图持有 Mod 的对象图（事件视图、服务代理视图），
        // 不清掉会让整条链在 Loader 实例上继续可达。
        apiContexts.clear();
        modContexts.clear();

        // Mod 全部关闭后再落盘：此时 Mod 的 shutdown 钩子已经把最终状态写进内存。
        if (storageService != null) {
            int flushed = storageService.flushAll();
            PlatformLog.info("[Mili] Mod 存储已落盘: " + flushed
                    + "/" + storageService.size());
        }

        // 已加载类数必须在 ClassLoader 关闭**之前**取：关掉之后
        // loadedClasses() 就是空集合，报告会变成"每个 Mod 0 个类"，
        // 看起来像一次完美回收，实际是没测。
        Map<String, Integer> classesPerMod = new LinkedHashMap<>();
        if (classLoaderManager != null) {
            for (ModClassLoader mcl : classLoaderManager.all()) {
                classesPerMod.put(mcl.modId(), mcl.loadedClasses().size());
            }
        }

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

        reportShutdownAudit(classesPerMod);
    }

    /**
     * 装配 {@link org.loader.api.Mili} 的 provider。
     *
     * <h2>为什么这一步不能省</h2>
     * {@code Mili} 是契约里声明的「Primary entry point to the Mili public API」，
     * 但 {@code Mili.registerProvider} 在生产代码里<b>零调用者</b> ——
     * 于是 {@code Mili.mod(this)} 恒为空、{@code Mili.apiVersion()} 之外的
     * 一切门面方法都不可用。这类「声明了但没接线」的 API 比没有更糟：
     * 调用方看到的是 {@code Optional.empty()}，会以为「我的 Mod 没被识别」，
     * 而实际是平台没装 provider。
     */
    private void installMiliFacade() {
        org.loader.api.Mili.registerProvider(new org.loader.api.Mili.MiliProvider() {
            @Override
            public java.util.Optional<org.loader.api.ModContext> findMod(Object modInstance) {
                if (modInstance == null) {
                    return java.util.Optional.empty();
                }
                for (Mod m : loadedMods) {
                    ModContext ctx = modContexts.get(m.id());
                    if (ctx == null) {
                        continue;
                    }
                    // 实例 → 上下文：Mod 的入口类由自己的 ClassLoader 定义，
                    // 因此按「加载器」匹配，而不是按对象身份
                    // （同一个 Mod 可能被多次实例化，例如测试）。
                    ClassLoader instanceLoader = modInstance.getClass().getClassLoader();
                    if (instanceLoader == ctx.classLoader()) {
                        return java.util.Optional.ofNullable(apiContexts.get(m.id()));
                    }
                }
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<org.loader.api.ModContext> findMod(String modId) {
                return modId == null
                        ? java.util.Optional.empty()
                        : java.util.Optional.ofNullable(apiContexts.get(modId));
            }

            @Override
            public org.loader.api.event.EventBus events() {
                return new PlatformBusView(runtime);
            }

            @Override
            public java.util.List<String> modIds() {
                return loadedMods.stream().map(Mod::id).toList();
            }
        });
        PlatformLog.info("[Mili] 契约门面已接通: " + loadedMods.size() + " 个 Mod");
    }

    /**
     * 无归属的平台总线视图 —— {@link org.loader.api.Mili#events()} 的返回值。
     *
     * <p>与 {@code ctx.events()} 的区别：这里注册的监听器<b>不随任何 Mod
     * 注销</b>。它面向的是「拿不到 context 的位置」（静态工具类、
     * 转换器回调），调用方必须自己负责生命周期。
     */
    private static final class PlatformBusView implements org.loader.api.event.EventBus {

        private final org.loader.runtime.service.EventBus bus;

        PlatformBusView(org.loader.runtime.kernel.Runtime runtime) {
            this.bus = runtime.events();
        }

        @Override
        public <T> void addListener(Class<T> eventType,
                                    org.loader.api.event.EventListener<T> listener) {
            bus.addListener(eventType, listener::onEvent);
        }

        @Override
        public <T> void removeListener(Class<T> eventType,
                                       org.loader.api.event.EventListener<T> listener) {
            // 无归属视图无法反查包装器 —— 如实说明限制，而不是假装移除了。
            throw new UnsupportedOperationException(
                    "Mili.events() 注册的监听器不支持按身份移除（无法反查包装器）。"
                            + "需要可退订时请用 ctx.events().subscribe(...) 拿 Subscription。");
        }

        @Override
        public <T> org.loader.api.event.Subscription subscribe(
                Class<T> eventType, org.loader.api.event.EventListener<T> listener) {
            org.loader.runtime.service.EventBus.EventListener<T> forward = listener::onEvent;
            bus.addListener(eventType, forward);
            return new org.loader.api.event.Subscription() {
                private volatile boolean active = true;

                @Override
                public void unsubscribe() {
                    if (active) {
                        active = false;
                        bus.removeListener(eventType, forward);
                    }
                }

                @Override
                public boolean isActive() {
                    return active;
                }
            };
        }

        @Override
        public <T> void post(T event) {
            bus.post(event);
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<Void> postAsync(T event) {
            return bus.postAsync(event);
        }

        @Override
        public boolean hasListeners(Class<?> eventType) {
            return bus.hasListeners(eventType);
        }

        @Override
        public int listenerCount() {
            return bus.listenerCount();
        }

        @Override
        public void close() {
            // 平台总线由 Runtime 关闭，这里不提供关闭入口
            throw new UnsupportedOperationException(
                    "平台总线由 Runtime 管理，不能由 Mod 关闭");
        }
    }

    /**
     * runtime 生命周期状态 → 契约状态。
     *
     * <p>两侧枚举同名同序，但仍显式 switch：用 {@code valueOf(name())}
     * 会在任一侧新增枚举值时静默抛 {@code IllegalArgumentException}
     * （发生在事件发布路径上，会被吞掉），而 switch 会编译失败。
     */
    private static org.loader.api.lifecycle.LifecycleState apiLifecycleState(
            org.loader.runtime.kernel.LifecycleState state) {
        return switch (state) {
            case DISCOVERED -> org.loader.api.lifecycle.LifecycleState.DISCOVERED;
            case RESOLVED -> org.loader.api.lifecycle.LifecycleState.RESOLVED;
            case LOADED -> org.loader.api.lifecycle.LifecycleState.LOADED;
            case INITIALIZED -> org.loader.api.lifecycle.LifecycleState.INITIALIZED;
            case REGISTERED -> org.loader.api.lifecycle.LifecycleState.REGISTERED;
            case RUNNING -> org.loader.api.lifecycle.LifecycleState.RUNNING;
            case STOPPING -> org.loader.api.lifecycle.LifecycleState.STOPPING;
            case STOPPED -> org.loader.api.lifecycle.LifecycleState.STOPPED;
            case FAILED -> org.loader.api.lifecycle.LifecycleState.FAILED;
        };
    }

    /**
     * 输出关闭期回收审计。
     *
     * <p>放在 {@code runtime.close()} 之后：此时调度器、事件总线、看门狗
     * 都已经有机会退出，剩下的线程才是真正的残留。放在之前测到的只是
     * "还没关完"，没有意义。
     */
    private void reportShutdownAudit(Map<String, Integer> classesPerMod) {
        if (threadBaseline == null) {
            return;
        }
        try {
            PlatformLog.info(ShutdownAudit.report(
                    threadBaseline, ShutdownAudit.capture(), classesPerMod));
            audit.record("platform.stop", null, "shutdown audit emitted");
        } catch (RuntimeException e) {
            // 审计失败不能影响关闭流程
            PlatformLog.warn("关闭期审计失败: " + e);
        }
    }

    // 平台参数的拆分已移到 parseArgs()：它同时要摘掉 --mili-mods，
    // 而不只是 args[0] 的 gameDir。

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