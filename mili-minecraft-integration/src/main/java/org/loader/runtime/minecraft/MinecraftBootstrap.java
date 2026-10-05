package org.loader.runtime.minecraft;

import org.loader.runtime.RuntimeEnvironment;
import org.loader.runtime.client.ClientCapabilities;
import org.loader.runtime.kernel.LifecycleState;
import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.scheduler.Scheduler;
import org.loader.runtime.service.EventBus;
import org.loader.runtime.tick.TickEngine;
import org.loader.runtime.minecraft.transform.TickCallbackDispatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minecraft 集成引导器 —— 带完整状态机与失败清理。
 *
 * <pre>
 *   CREATED → DISCOVERING → PREPARING → LOADING → BOOTSTRAPPING → RUNNING
 *                                                                  ↓
 *   FAILED ←─────────────────────────────────────────  STOPPING → STOPPED
 * </pre>
 *
 * <p><b>失败语义</b>：任何阶段抛出异常都会迁移到 {@link BootstrapState#FAILED}，
 * 并<b>逆序释放</b>已创建的资源（Scope → 子 Scope → ClassLoader → 线程）。
 * 不允许留下半初始化的运行时。
 */
public final class MinecraftBootstrap {

    /**
     * Minecraft 集成生命周期事件。
     *
     * <p>由 {@link MinecraftLifecycle} 在引导器状态迁移时发出，供宿主（loader
     * 与 Mod）观察集成层的启动与关闭过程。
     */
    public enum MinecraftLifecycleEvent {
        /** 引导开始，正在申请 Scope 与桥接资源。 */
        STARTING,
        /** 引导完成，Minecraft 已可被反射调用。 */
        STARTED,
        /** 关闭开始，正在释放 Scope 与外部资源。 */
        STOPPING,
        /** 关闭完成，全部资源已释放。 */
        STOPPED
    }

    private final Scope minecraftScope;
    private final Scope renderScope;
    private final RuntimeEnvironment environment;
    private final Scheduler scheduler;
    private final TickEngine tickEngine;
    private final TickBridge tickBridge;
    private final MinecraftLifecycle lifecycle;
    private final MinecraftEventBridge eventBridge;
    private final MinecraftRegistryBridge registryBridge;
    private final EntityBridge entityBridge;
    private final WorldBridge worldBridge;

    private final AtomicReference<BootstrapState> state =
            new AtomicReference<>(BootstrapState.CREATED);

    /** 运行期持有的可关闭外部资源（ClassLoader / 线程），失败时释放。 */
    private final List<AutoCloseable> externalResources =
            Collections.synchronizedList(new ArrayList<>());

    /** 失败原因。 */
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    public MinecraftBootstrap(Runtime runtime) {
        this(runtime, RuntimeEnvironment.DEDICATED_SERVER);
    }

    public MinecraftBootstrap(Runtime runtime, RuntimeEnvironment env) {
        this.environment = env;

        if (env.isClient()) {
            this.minecraftScope = runtime.rootScope().createChild("minecraft-client");
            this.renderScope = minecraftScope.createChild("render-scope");
            ClientCapabilities.grantRenderCapability(renderScope, env);
            ClientCapabilities.grantInputCapability(renderScope, env);
            ClientCapabilities.grantSoundCapability(renderScope, env);
        } else {
            this.minecraftScope = runtime.rootScope().createChild("minecraft");
            this.renderScope = null;
        }

        this.scheduler = new Scheduler("minecraft-scheduler", minecraftScope);
        this.tickEngine = new TickEngine("minecraft-tick-engine", minecraftScope);
        this.lifecycle = new MinecraftLifecycle(minecraftScope);
        this.tickBridge = new TickBridge(minecraftScope, tickEngine);
        this.eventBridge = new MinecraftEventBridge(minecraftScope);
        this.registryBridge = new MinecraftRegistryBridge(minecraftScope);
        this.entityBridge = new EntityBridge(minecraftScope, minecraftScope);
        this.worldBridge = new WorldBridge(minecraftScope, minecraftScope);

        minecraftScope.registerResource(scheduler);
        minecraftScope.registerResource(tickEngine);
        minecraftScope.registerResource(tickBridge);
        minecraftScope.registerResource(eventBridge);
        minecraftScope.registerResource(registryBridge);
        minecraftScope.registerResource(entityBridge);
        minecraftScope.registerResource(worldBridge);

        minecraftScope.grantCapability(RuntimeEnvironment.class, env);

        // 把 tick 桥接安装到注入分发器 —— 这是 tick 链闭合的最后一步。
        //
        // 审计发现：在装配这一步之前，TickBridge.beginTick()/endTick()
        // 在生产路径上**零调用者**。整条链是断的：
        //   MinecraftServer.tickServer()   ← 真实 tick
        //        ↓ （缺失这一环）
        //   TickBridge → TickEngine → TickContract → Mod 的 TickHandler
        // 表现是「Mod 的 tick 回调从不执行」，而平台日志里什么也没有。
        //
        // 现在 MiliTickTransformer 会在 MinecraftServer#tickServer 的方法头
        // 与每个返回路径前注入对 TickCallbackDispatch 的调用，
        // 由它转发到这里刚装配好的桥接。
        //
// 安装时机：必须在任何 net.minecraft.* 类被加载之前 ——
// 类一旦 defineClass 完成，字节码转换就再也来不及了。
        //
        // 用 register 而非裸 install：让分发器随 minecraftScope 一同释放，
        // 否则它的静态 activeBridge 引用会让整条 tick 对象图在游戏退出后仍可达。
        TickCallbackDispatch.register(minecraftScope);
        TickCallbackDispatch.install(this.tickBridge);
    }

    // ── 状态查询 ───────────────────────────────────────────────────────────

    public BootstrapState state() {
        return state.get();
    }

    public Throwable failure() {
        return failure.get();
    }

    public boolean isRunning() {
        return state.get() == BootstrapState.RUNNING;
    }

    public Scope scope() {
        return minecraftScope;
    }

    public Scope renderScope() {
        return renderScope;
    }

    public RuntimeEnvironment environment() {
        return environment;
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    public TickEngine tickEngine() {
        return tickEngine;
    }

    public TickBridge tickBridge() {
        return tickBridge;
    }

    public MinecraftLifecycle lifecycle() {
        return lifecycle;
    }

    public MinecraftEventBridge eventBridge() {
        return eventBridge;
    }

    public MinecraftRegistryBridge registryBridge() {
        return registryBridge;
    }

    public EntityBridge entityBridge() {
        return entityBridge;
    }

    public WorldBridge worldBridge() {
        return worldBridge;
    }

    // ── 状态迁移 ───────────────────────────────────────────────────────────

    /**
     * 迁移到指定状态（受 {@link BootstrapState#requireTransitionTo} 校验）。
     */
    private void transition(BootstrapState target) {
        BootstrapState current = state.get();
        current.requireTransitionTo(target);
        state.set(target);
    }

    /**
     * 进入 DISCOVERING —— 即将定位并校验 Minecraft。
     */
    public void beginDiscovery() {
        transition(BootstrapState.DISCOVERING);
    }

    /**
     * 进入 PREPARING —— 准备运行环境与 ClassLoader。
     */
    public void beginPreparing() {
        transition(BootstrapState.PREPARING);
    }

    /**
     * 进入 LOADING —— 加载 Mod 与解析依赖。
     */
    public void beginLoading() {
        transition(BootstrapState.LOADING);
    }

    /**
     * 进入 BOOTSTRAPPING —— Minecraft 自身初始化。
     */
    public void beginMinecraftBootstrap() {
        transition(BootstrapState.BOOTSTRAPPING);
    }

    /**
     * 进入 RUNNING —— Minecraft 主循环运行中，Tick 生效。
     *
     * <p><b>必须由 BOOTSTRAPPING 迁移而来。</b>本方法刻意<b>不</b>提供
     * 「从 CREATED 自动补齐中间阶段」的便利路径：那样做等于把跳步迁移
     * 伪装成合法迁移，于是「忘记调用 {@link #beginDiscovery()} /
     * {@link #beginLoading()}」这类集成错误永远不会暴露 —— 游戏会在
     * 根本没做发现、准备、加载的情况下启动起来，等到 Mod 加载失败时
     * 才暴露，且离真正的原因隔了三个阶段。
     *
     * <p>状态机存在的意义就是在这里拦住它。非法迁移会抛
     * {@link BootstrapState.IllegalStateTransitionException}。
     */
    public void start() {
        BootstrapState current = state.get();
        if (current == BootstrapState.RUNNING) {
            throw new IllegalStateException("MinecraftBootstrap 已启动");
        }
        // 先校验迁移：非法时必须在发事件之前就抛出去，否则监听方会先收到
        // 「服务器要启动了」，紧接着看到启动失败 —— 顺序颠倒比失败本身更糟。
        current.requireTransitionTo(BootstrapState.RUNNING);

        eventBridge.post(new MinecraftEventBridge.ServerStartingEvent());

        transition(BootstrapState.RUNNING);
        driveScopeToRunning();

        lifecycle.onStart();
        eventBridge.post(new MinecraftEventBridge.ServerStartedEvent());
    }

    private void driveScopeToRunning() {
        minecraftScope.transitionTo(LifecycleState.RESOLVED);
        minecraftScope.transitionTo(LifecycleState.LOADED);
        minecraftScope.transitionTo(LifecycleState.INITIALIZED);
        minecraftScope.transitionTo(LifecycleState.REGISTERED);
        minecraftScope.transitionTo(LifecycleState.RUNNING);

        if (renderScope != null) {
            renderScope.transitionTo(LifecycleState.RESOLVED);
            renderScope.transitionTo(LifecycleState.LOADED);
            renderScope.transitionTo(LifecycleState.INITIALIZED);
            renderScope.transitionTo(LifecycleState.REGISTERED);
            renderScope.transitionTo(LifecycleState.RUNNING);
        }
    }

    /**
     * 注册需要在失败/停止时释放的外部资源（ClassLoader、线程等）。
     */
    public void registerExternalResource(AutoCloseable closeable) {
        externalResources.add(closeable);
    }

    /**
     * 优雅停止。
     */
    public void stop() {
        BootstrapState current = state.get();
        if (current == BootstrapState.STOPPED || current == BootstrapState.FAILED) {
            return;
        }
        state.set(BootstrapState.STOPPING);
        eventBridge.post(new MinecraftEventBridge.ServerStoppingEvent());
        lifecycle.onStop();
        eventBridge.post(new MinecraftEventBridge.ServerStoppedEvent());
        releaseAll();
        state.set(BootstrapState.STOPPED);
    }

    /**
     * 标记启动失败并释放全部资源。
     *
     * @param cause 失败原因
     */
    public void fail(Throwable cause) {
        if (state.get() == BootstrapState.STOPPED) {
            return;
        }
        failure.set(cause);
        state.set(BootstrapState.FAILED);
        releaseAll();
    }

    /**
     * 逆序释放外部资源（后注册先释放），再关闭 Scope。
     * 每一步都尽力完成，单个失败不阻断其余清理。
     */
    private void releaseAll() {
        List<AutoCloseable> snapshot;
        synchronized (externalResources) {
            snapshot = new ArrayList<>(externalResources);
            externalResources.clear();
        }
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            try {
                snapshot.get(i).close();
            } catch (Exception ignored) {
                // 清理失败不阻断其余资源释放
            }
        }
        try {
            if (!minecraftScope.isStopped()) {
                minecraftScope.shutdown();
            }
        } catch (Exception ignored) {
            // 同上
        }
    }

    /** 诊断摘要。 */
    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("MinecraftBootstrap[state=").append(state.get());
        sb.append(", env=").append(environment);
        sb.append(", scope=").append(minecraftScope.id()).append("]\n");
        sb.append(tickEngine.diagnostics());
        Throwable f = failure.get();
        if (f != null) {
            sb.append("  failure=").append(f).append('\n');
        }
        return sb.toString();
    }
}