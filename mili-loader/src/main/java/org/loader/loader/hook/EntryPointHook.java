package org.loader.loader.hook;

import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.runtime.RuntimeEnvironment;
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.minecraft.BootstrapState;
import org.loader.runtime.minecraft.MinecraftBootstrap;
import org.loader.runtime.minecraft.MinecraftEventBridge;
import org.loader.runtime.tick.TickContract;

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
            org.loader.runtime.minecraft.BootstrapGate.ensureBootstrapped();
            System.out.println("[Mili] Mod registrations applied ("
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
        // 阻塞直到游戏退出
        exited.await();

        Throwable err = mainError.get();
        if (err != null) {
            Throwable cause = err.getCause() != null ? err.getCause() : err;
            throw new IllegalStateException("Minecraft main 异常退出: " + cause.getMessage(), cause);
        }
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