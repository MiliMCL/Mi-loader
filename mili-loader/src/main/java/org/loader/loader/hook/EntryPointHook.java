package org.loader.loader.hook;

import org.loader.loader.classloader.ModClassLoader;
import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.runtime.RuntimeEnvironment;
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.minecraft.BootstrapState;
import org.loader.runtime.minecraft.MinecraftBootstrap;
import org.loader.runtime.minecraft.MinecraftEventBridge;
import org.loader.runtime.minecraft.resource.ModResourceInjectionScheduler;
import org.loader.runtime.tick.TickContract;
import org.loader.loader.PlatformLog;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把 Minecraft 生命周期桥接到 Mili Runtime。
 *
 * <p><b>策略</b>：
 * <ol>
 *   <li>Runtime 由 {@link org.loader.loader.LoaderMain} 先启动</li>
 *   <li>本 Hook 在 Runtime 下创建 {@link MinecraftBootstrap} 并走完状态机</li>
 *   <li>在<b>独立线程</b>上调用 MC main（游戏阻塞直到退出）</li>
 *   <li>MC 退出后停止 bootstrap，失败则标记 FAILED 并释放资源</li>
 * </ol>
 *
 * <p><b>关于 tick</b>：本 Hook <b>不</b>轮询 tick。真实 tick 由
 * {@link org.loader.runtime.minecraft.TickBridge} 在游戏 tick 循环中被调用，
 * 见 {@link org.loader.runtime.minecraft.MinecraftTickSource}。
 */
public class EntryPointHook {

    private final AtomicReference<MinecraftBootstrap> bootstrap = new AtomicReference<>();
    private final AtomicReference<Runtime> runtime = new AtomicReference<>();
    private final AtomicReference<ModClassLoaderManager> classLoaderManager = new AtomicReference<>();
    private final AtomicReference<Thread> mcMainThread = new AtomicReference<>();
    private final AtomicReference<Scope> minecraftScope = new AtomicReference<>();
    private final AtomicReference<ClassLoader> mcClassLoader = new AtomicReference<>();
    private volatile boolean installed = false;
    private volatile RuntimeEnvironment environment;
    private volatile boolean ownsRuntime = false;

    /**
     * 各 Mod 声明的方块路径（modId → paths），由 {@code LoaderMain} 在
     * 调用 Mod 入口后填入。用于创建创造模式标签。
     *
     * <p>26.2 的 {@code CreativeModeTabs} 全是硬编码 {@code accept(...)} 列表，
     * 物品注册本身不会让Mod 物品出现在创造栏 —— 必须由平台显式塞进某个标签。
     * 搜索页会自动聚合所有标签，所以进了任一标签就能被搜到。
     */
    private volatile java.util.Map<String, java.util.List<String>> declaredBlocks =
            org.loader.loader.LoaderMain.declaredBlocksSnapshot();

    /**
     * 设置各 Mod 声明的方块清单。必须在游戏启动前调用。
     *
     * @param byMod modId → 该 Mod 声明的方块路径（相对其命名空间）
     */
    public void declaredBlocks(java.util.Map<String, java.util.List<String>> byMod) {
        this.declaredBlocks = byMod == null ? java.util.Map.of() : java.util.Map.copyOf(byMod);
    }

    /**
     * 安装 Hook。
     *
     * @param mcClassLoader 能加载 Minecraft 类的 ClassLoader（唯一的 MC CL）
     * @param runtime       已启动的 Runtime
     * @param manager       Mod ClassLoader 管理器
     * @param env           运行环境
     */
    public void install(ClassLoader mcClassLoader, Runtime runtime,
                        ModClassLoaderManager manager, RuntimeEnvironment env) {
        if (installed) {
            return;
        }
        this.mcClassLoader.set(mcClassLoader);
        this.runtime.set(runtime);
        this.classLoaderManager.set(manager);
        this.environment = env;
        this.ownsRuntime = false;

        MinecraftBootstrap mcBootstrap = new MinecraftBootstrap(runtime, env);
        bootstrap.set(mcBootstrap);
        this.minecraftScope.set(mcBootstrap.scope());
        installed = true;
    }

    /**
     * 在 Runtime 生命周期控制下调用 Minecraft main。阻塞直到游戏退出。
     */
    public void invokeMinecraftMain(ClassLoader mcClassLoader, String[] args) throws Exception {
        MinecraftBootstrap mcBootstrap = bootstrap.get();
        Runtime rt = runtime.get();

        if (rt == null) {
            rt = Runtime.create("minecraft-runtime");
            runtime.set(rt);
            rt.start();
            ownsRuntime = true;
            if (mcBootstrap == null) {
                mcBootstrap = new MinecraftBootstrap(rt,
                        environment != null ? environment : RuntimeEnvironment.DEDICATED_SERVER);
                bootstrap.set(mcBootstrap);
            }
        }
        if (mcBootstrap == null) {
            throw new IllegalStateException("Hook 未安装，请先调用 install()");
        }

        String entrypoint = (environment == RuntimeEnvironment.CLIENT)
                ? "net.minecraft.client.main.Main"
                : "net.minecraft.server.Main";

        // ── 状态机推进：CREATED → DISCOVERING → PREPARING → LOADING → BOOTSTRAPPING
        mcBootstrap.beginDiscovery();
        mcBootstrap.beginPreparing();
        mcBootstrap.beginLoading();
        mcBootstrap.beginMinecraftBootstrap();

        // ── 打开注册窗口并执行全部 Mod 注册 ──────────────────────────
        //
        // 这一步以前根本不存在，于是整条注册链在生产路径上是死代码：
        // Mod 在 initialize() 里声明的方块被 RegistrationPhase.defer
        // 排队后，永远没有人去执行它们 —— 游戏照常启动，只是没有那些方块。
        //
        // 更糟的是它<b>看起来是好的</b>：Mod 加载成功、initialize 正常返回、
        // 没有一行报错，只是内容没进游戏。这类「静默失效」比崩溃难查得多。
        //
        // 顺序要求：必须在调用游戏 main 之前。游戏自己一旦 bootstrap 完成，
        // 注册表就冻结了，那时候再注册一个都进不去（26.2 的硬限制，
        // 推导见 RegistrationPhase 的类注释）。
        runModRegistrations();

        if (environment == RuntimeEnvironment.CLIENT) {
            mcBootstrap.eventBridge().post(new MinecraftEventBridge.ClientStartingEvent());
        } else {
            mcBootstrap.eventBridge().post(new MinecraftEventBridge.ServerStartingEvent());
        }

        // → RUNNING（必须在调用游戏 main 之前，Mod 才能在启动期看到 RUNNING）
        mcBootstrap.start();

        // 启动 Mod 资源注入。
        //
        // Mod 的方块/物品能注册，但模型与贴图在 JAR 的 assets/ 下，
        // 而游戏只从自己的资源包体系读这些目录 —— 不注入就是
        // "Missing model for variant: 'Block{...}'"。
        //
        // 本方法只登记 JAR 并起一个守护线程；真正的注入在线程里等
        // Minecraft 实例就绪后才发生 —— 此刻游戏 main 还没被调用，
        // Minecraft 对象尚不存在。
        //
        // 【不要改回 TickBridge.onTick】它在生产路径上无人驱动
        // （ReflectiveMinecraftTickSource 没有任何调用方），
        // 且 activeContract 为 null 时会静默 return —— 上一版就是这么
        // 静默失效的。详见 ModResourceInjectionScheduler 的类注释。
        scheduleResourceInjection();

        Throwable launchFailure = null;
        try {
            runMinecraftMain(mcClassLoader, entrypoint, args);
        } catch (Throwable t) {
            launchFailure = t;
            throw t;
        } finally {
            shutdownBootstrap(mcBootstrap, launchFailure);
        }
    }

    /**
     * 登记各 Mod 的 JAR 并把资源注入排到第一个 tick。
     *
     * <p>失败<b>不阻断启动</b>：资源缺失的表现是「模型没贴图」，
     * 游戏本身完全可玩。这与 {@link #runModRegistrations()} 的策略
     * 刻意不同 —— 那里失败意味着 Mod 内容根本不在游戏里，
     * 属于「静默缺失」，必须炸出来。
     */
    private void scheduleResourceInjection() {
        try {
            ModClassLoaderManager mgr = classLoaderManager.get();
            if (mgr == null) {
                return;
            }
            int registered = 0;
            for (ModClassLoader mcl : mgr.all()) {
                for (java.nio.file.Path source : mcl.modSources()) {
                    if (source != null && source.toString().endsWith(".jar")) {
                        ModResourceInjectionScheduler.register(
                                mcl.modId(), source, declaredBlocksOf(mcl.modId()));
                        registered++;
                        break; // 每个 Mod 只取第一个 JAR
                    }
                }
            }
            if (registered > 0) {
                ModResourceInjectionScheduler.start();
                PlatformLog.info("Mod resources armed for injection: "
                        + registered + " mod(s)");
            }
        } catch (RuntimeException e) {
            PlatformLog.warn("提示: 无法安排 Mod 资源注入（不影响游戏启动，"
                    + "但 Mod 的模型与贴图会缺失）: " + e);
        }
    }

    /**
     * 某 Mod 声明的方块路径；未声明过则返回空列表。
     *
     * <p>26.2 的 {@code CreativeModeTabs} 全部是硬编码 {@code accept(...)} 列表，
     * 物品注册本身<b>不会</b>让 Mod 物品出现在创造栏 —— 平台必须显式塞进
     * 某个标签。搜索页会自动聚合所有标签，所以进了任一标签就能被搜到。
     */
    private java.util.List<String> declaredBlocksOf(String modId) {
        java.util.List<String> paths = declaredBlocks.get(modId);
        return paths == null ? java.util.List.of() : paths;
    }

    /**
     * 打开注册窗口、执行全部 Mod 注册、关闭窗口（补原版内容 + 冻结）。
     *
     * <p>{@link org.loader.runtime.minecraft.BootstrapGate#ensureBootstrapped()}
     * 内部就是这三步，且是幂等的 —— 若 Mod 在更早的时机已经触发过（例如
     * 分步注册的场景），这里不会重复执行。
     *
     * <p><b>失败必须炸，不能吞</b>：注册失败意味着 Mod 内容没进游戏。
     * 若继续启动，玩家会看到一个「少了东西但没报错」的游戏，而排查线索
     * 已经随游戏启动日志滚走了。
     */
    private void runModRegistrations() {
        int pending = org.loader.runtime.minecraft.RegistrationPhase.pendingCount();
        try {
            // 创造栏标签必须在【窗口关闭之前】注册。
            //
            // 原因：26.2 的 BuiltInRegistries.bootStrap() 末尾会 freeze() 全部注册表，
            // 而 CREATIVE_MODE_TAB 也在其中；bootStrap() 正是 closeRegistryWindow()
            // 调用的东西。上一版把标签注册排在游戏启动之后，那时注册表已冻结，
            // 注册必然失败（表现为"创造栏里什么都没有"且无明显报错）。
            registerCreativeTabs();

            org.loader.runtime.minecraft.BootstrapGate.ensureBootstrapped();
            PlatformLog.info("Mod registrations applied ("
                    + pending + " pending before bootstrap)");
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            throw new IllegalStateException(
                    "Mod registration failed; refusing to start Minecraft without it."
                            + "\n  " + pending + " registration(s) were queued by mods in"
                            + " their initialize() methods."
                            + "\n  Starting anyway would give the player a game that is"
                            + " silently missing mod content."
                            + "\n  Real cause: " + cause, cause);
        }
    }

    /**
     * 为每个 Mod 创建创造模式标签，并填入它声明的方块。
     *
     * <p><b>按 mod id 自动分发</b>：标签 id 直接取 modId（小写化），
     * 标题用 {@code itemGroup.<modId>.<modId>} 交给 lang 翻译 ——
     * 零配置，Mod 只要有 id 就有自己的标签。
     *
     * <p>失败只记警告：创造栏是便利功能，缺了方块仍可用 {@code /give} 获得。
     */
    private void registerCreativeTabs() {
        java.util.Map<String, java.util.List<String>> blocks = declaredBlocks;
        if (blocks.isEmpty()) {
            return;
        }
        int created = 0;
        for (java.util.Map.Entry<String, java.util.List<String>> e : blocks.entrySet()) {
            java.util.List<String> paths = e.getValue();
            if (paths == null || paths.isEmpty()) {
                continue;
            }
            try {
                org.loader.runtime.minecraft.resource.ModCreativeTabs
                        .registerTab(e.getKey(), paths);
                created++;
            } catch (RuntimeException ex) {
                PlatformLog.warn("创造栏标签创建失败: " + e.getKey() + "（方块仍可用 /give 获得）", ex);
            }
        }
        if (created > 0) {
            PlatformLog.info("Creative tabs created for " + created + " mod(s)");
        }
    }

    /**
     * 在独立线程上调用游戏 main 并等待其退出。
     */
    private void runMinecraftMain(ClassLoader cl, String entrypoint, String[] args) throws Exception {
        Class<?> mainClass = Class.forName(entrypoint, true, cl);
        Method mainMethod = mainClass.getMethod("main", String[].class);

        CountDownLatch threadStarted = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        AtomicReference<Throwable> mainError = new AtomicReference<>();

        Thread mcThread = new Thread(() -> {
            mcMainThread.set(Thread.currentThread());
            threadStarted.countDown();
            try {
                mainMethod.invoke(null, (Object) args);
            } catch (Throwable t) {
                mainError.set(t);
                System.err.println("[Minecraft] main() 抛出异常: " + t);
                t.printStackTrace();
            } finally {
                exited.countDown();
            }
        }, "minecraft-main");

        mcThread.setContextClassLoader(cl);
        mcThread.setDaemon(false);
        mcThread.start();

        // 等待主线程真正启动
        if (!threadStarted.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Minecraft main 线程未能在 5s 内启动");
        }
        startLaunchWatchdog(exited);
        // 阻塞直到游戏退出
        exited.await();

        Throwable err = mainError.get();
        if (err != null) {
            Throwable cause = err.getCause() != null ? err.getCause() : err;
            throw new IllegalStateException("Minecraft main 异常退出: " + cause.getMessage(), cause);
        }
    }

    /**
     * 启动看门狗 —— 静默挂起的黑匣子。
     *
     * <h2>为什么需要它</h2>
     * 游戏 main 在独立线程上运行后，若它卡死（窗口不出现、日志无输出、
     * 无异常抛出），平台与用户都没有任何抓手：进程活着、Mod 线程还在
     * tick、控制台一片寂静。真实事故：客户端启动深入到 Minecraft 构造
     * 阶段（joml 已加载）后静默挂起数分钟，唯一可观测的只有 Mod 的
     * 季节时钟 —— 连线程卡在哪一行都无法得知。
     *
     * <h2>判定信号</h2>
     * 游戏主循环一旦运行，客户端 tick 会经注入代码进入
     * {@code KeyBindingDispatch.tick()}（计数 &gt; 0），服务端 tick 走
     * {@code TickCallbackDispatch}。两个计数全为 0 且超过时限 =
     * 主循环从未启动，输出全线程转储（含监视器锁信息，可诊断死锁）。
     * 转储写 stderr 与平台日志文件各一份；不干预进程，只观测。
     */
    private void startLaunchWatchdog(java.util.concurrent.CountDownLatch exited) {
        final long firstDumpMs = 120_000L;
        final long intervalMs = 120_000L;
        Thread watchdog = new Thread(() -> {
            long nextDump = System.currentTimeMillis() + firstDumpMs;
            while (true) {
                if (exited.getCount() == 0) {
                    return;   // 游戏 main 已退出，无需监控
                }
                boolean gameLoopAlive =
                        org.loader.runtime.minecraft.client.input.KeyBindingDispatch.tickCount() > 0
                        || org.loader.runtime.minecraft.transform.TickCallbackDispatch.totalTicks() > 0;
                if (gameLoopAlive) {
                    return;   // 主循环已运行，正常启动
                }
                long now = System.currentTimeMillis();
                if (now >= nextDump) {
                    nextDump = now + intervalMs;
                    dumpAllThreads();
                }
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "mili-launch-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** 全线程转储（含锁持有/等待信息）→ stderr + 平台日志文件。 */
    private void dumpAllThreads() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== [Mili] THREAD DUMP: 游戏 main 长时间未进入任何 tick =====")
          .append(System.lineSeparator());
        sb.append("时间: ").append(java.time.LocalDateTime.now())
          .append(System.lineSeparator());
        try {
            java.lang.management.ThreadMXBean mx =
                    java.lang.management.ManagementFactory.getThreadMXBean();
            sb.append("线程总数: ").append(mx.getThreadCount())
              .append(System.lineSeparator());
            for (java.lang.management.ThreadInfo ti : mx.dumpAllThreads(true, true)) {
                sb.append(ti.toString()).append(System.lineSeparator());
            }
        } catch (Throwable t) {
            sb.append("线程转储失败: ").append(t).append(System.lineSeparator());
        }
        sb.append("===== [Mili] THREAD DUMP END =====").append(System.lineSeparator());
        System.err.println("[Mili] 启动看门狗：游戏 main 超时未进入任何 tick，"
                + "全线程转储已写入 stderr 与 logs/mili-platform.log");
        PlatformLog.raw(sb.toString());
    }

    /**
     * 停止 bootstrap：失败则 fail()，成功则 stop()。释放全部资源。
     */
    private void shutdownBootstrap(MinecraftBootstrap mcBootstrap, Throwable launchFailure) {
        if (launchFailure != null) {
            mcBootstrap.fail(launchFailure);
        } else {
            if (environment == RuntimeEnvironment.CLIENT) {
                mcBootstrap.eventBridge().post(new MinecraftEventBridge.ClientStoppingEvent());
            } else {
                mcBootstrap.eventBridge().post(new MinecraftEventBridge.ServerStoppingEvent());
            }
            mcBootstrap.stop();
        }

        // 若我们创建了自己的 Runtime，独立路径下由本 Hook 关闭
        if (ownsRuntime) {
            Runtime rt = runtime.get();
            if (rt != null) {
                try {
                    rt.close();
                } catch (Exception ignored) {
                    // 同上
                }
            }
        }
    }

    // ── 查询 ───────────────────────────────────────────────────────────────

    public boolean isInstalled() {
        return installed;
    }

    public Thread getMainThread() {
        return mcMainThread.get();
    }

    public MinecraftBootstrap getBootstrap() {
        return bootstrap.get();
    }

    public Scope getMinecraftScope() {
        return minecraftScope.get();
    }

    public BootstrapState state() {
        MinecraftBootstrap b = bootstrap.get();
        return b != null ? b.state() : BootstrapState.CREATED;
    }

    /**
     * 供注入层使用：Minecraft 主线程句柄。
     * tick 接入必须在此线程上进行。
     */
    public Thread tickThread() {
        return mcMainThread.get();
    }

    /** 供注入层使用：当前 tick 契约（不在 tick 内返回 null）。 */
    public TickContract currentTickContract() {
        MinecraftBootstrap b = bootstrap.get();
        return b != null ? b.tickBridge().activeContract() : null;
    }
}