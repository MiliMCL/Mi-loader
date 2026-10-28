package org.loader.runtime.kernel;

import org.loader.api.diagnostics.PerformanceService;
import org.loader.api.event.PlatformEvent;
import org.loader.api.event.PlatformStage;
import org.loader.api.VersionInfo;
import org.loader.runtime.diagnostics.PerformanceMonitor;
import org.loader.runtime.scheduler.Scheduler;
import org.loader.runtime.service.EventBus;
import org.loader.runtime.service.ServiceBrokerImpl;
import org.loader.runtime.tick.TickEngine;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The main entry point and bootstrap for the Runtime.
 * <p>
 * The Runtime owns execution. Mods do not own the Runtime.
 *
 * <h2>平台总线（本类新增的核心装配）</h2>
 * 所有跨组件通信都走 {@link #events()} 这一条总线：
 * <ul>
 *   <li>Tick 事件 —— {@link TickEngine} 在每个 tick 的 START / END 发布；</li>
 *   <li>Mod 生命周期事件 —— 每个 Mod 的 Scope 迁移时发布；</li>
 *   <li>平台阶段事件 —— 本类的 {@link #start()} / {@link #close()} 发布；</li>
 *   <li>Mod 自定义事件 —— {@code ctx.events().post(...)}。</li>
 * </ul>
 * 在它之前，每个组件各持一条总线：{@code MinecraftEventBridge} 有自己的、
 * 每个 Mod 有自己的、{@code ModCommunication}（已删除）又有自己的。结果是
 * 「Mod 订阅了 TickEvent 但永远收不到」——因为发布方在另一条总线上。
 * 这不是配置问题，是<b>架构断链</b>。
 */
public class Runtime implements AutoCloseable {

    private final String id;
    private final Scope rootScope;
    private final LifecycleManager lifecycleManager;
    private final CapabilityManager capabilityManager;
    private final PermissionManager permissionManager;
    private final ResourceRegistry resourceRegistry;
    private final DependencyResolver dependencyResolver;
    private final Scheduler scheduler;
    private final PerformanceMonitor performance;
    private final EventBus eventBus;
    private final ServiceBrokerImpl serviceBroker;
    /** tick 引擎迟绑定容器 —— 引擎由集成层在游戏启动时装配。 */
    private final org.loader.runtime.tick.TickEngineHolder tickEngine =
            new org.loader.runtime.tick.TickEngineHolder();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private Runtime(String id) {
        this.id = Objects.requireNonNull(id, "Runtime id must not be null");
        this.rootScope = new Scope(id + ":root", null);
        this.lifecycleManager = new LifecycleManager(id + ":lifecycle", rootScope);
        this.capabilityManager = new CapabilityManager(id + ":capabilities", rootScope);
        this.permissionManager = new PermissionManager(id + ":permissions", rootScope);
        this.resourceRegistry = new ResourceRegistry(id + ":resources", rootScope);
        this.dependencyResolver = new DependencyResolver();
        this.scheduler = new Scheduler(id + "-scheduler", rootScope);
        this.performance = new PerformanceMonitor(id + ":performance", rootScope);
        this.eventBus = new EventBus(id + ":events", rootScope);
        this.serviceBroker = new ServiceBrokerImpl(rootScope);

        // Register kernel components as resources
        rootScope.registerResource(lifecycleManager);
        rootScope.registerResource(capabilityManager);
        rootScope.registerResource(permissionManager);
        rootScope.registerResource(resourceRegistry);
        rootScope.registerResource(scheduler);
        rootScope.registerResource(performance);
        rootScope.registerResource(eventBus);
        rootScope.registerResource(serviceBroker);

        // 内核组件的默认权限策略：默认拒绝，仅放行「读写自己的资源」。
        // 网络 / 原生库 / 字节码覆盖必须由用户配置显式放行 —— 见 PermissionManager。
        permissionManager.installDefaults();

        // TPS/FPS 是可选的诊断能力：装配失败不该阻断运行时启动。
        // 读取方拿不到数据时得到的是空快照，而不是异常。
        PerformanceService.registerProvider(performance);
    }

    /**
     * Creates a new Runtime instance.
     */
    public static Runtime create(String id) {
        return new Runtime(id);
    }

    public String id() {
        return id;
    }

    public Scope rootScope() {
        return rootScope;
    }

    public LifecycleManager lifecycleManager() {
        return lifecycleManager;
    }

    public CapabilityManager capabilityManager() {
        return capabilityManager;
    }

    public PermissionManager permissionManager() {
        return permissionManager;
    }

    public ResourceRegistry resourceRegistry() {
        return resourceRegistry;
    }

    public DependencyResolver dependencyResolver() {
        return dependencyResolver;
    }

    /**
     * Returns the Runtime's unified Scheduler.
     */
    public Scheduler scheduler() {
        return scheduler;
    }

    /**
     * 本 Runtime 的性能采样器 —— tick 引擎与客户端帧采样共同写入。
     */
    public PerformanceMonitor performance() {
        return performance;
    }

    /**
     * 平台总线 —— 全平台唯一的事件入口。
     *
     * <p>Mod 的 {@code ctx.events()} 是它的带归属视图，不是另一条总线。
     */
    public EventBus events() {
        return eventBus;
    }

    /**
     * 跨 Mod 服务代理。
     */
    public ServiceBrokerImpl services() {
        return serviceBroker;
    }

    /**
     * tick 引擎迟绑定容器。
     *
     * <p>Mod 的 {@code initialize()} 早于 tick 引擎创建，因此不能把
     * 引擎直接塞进 {@code ModContext}。集成层在创建引擎后调用
     * {@code tickEngine().set(engine)}，此前提交 tick 任务的 Mod
     * 会在引擎就绪后自动生效。
     */
    public org.loader.runtime.tick.TickEngineHolder tickEngine() {
        return tickEngine;
    }

    /**
     * Starts the Runtime.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Runtime already started");
        }
        rootScope.driveToRunning();
        lifecycleManager.startAll();
        eventBus.post(new PlatformEvent(PlatformStage.BOOTSTRAPPING,
                VersionInfo.CURRENT_VERSION, 0));
    }

    /**
     * 发布平台阶段事件。供 loader 在关键节点调用。
     *
     * @param stage    阶段
     * @param modCount 当前已加载 Mod 数
     */
    public void publishPlatformStage(PlatformStage stage, int modCount) {
        eventBus.post(new PlatformEvent(stage, VersionInfo.CURRENT_VERSION, modCount));
    }

    /**
     * Returns whether the Runtime is running.
     */
    public boolean isRunning() {
        return started.get() && !closed.get();
    }

    /**
     * Gracefully shuts down the Runtime.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 先摘掉全局 provider，避免关闭中的实例继续被读到；
        // 按身份撤销，多个 Runtime 并存时不会误伤仍在运行的那个。
        PerformanceService.clearProvider(performance);
        try {
            // 关闭前先发 STOPPING：此时 Mod 的 Scope 还没释放，
            // 监听器仍能安全地访问自己的资源。
            eventBus.post(new PlatformEvent(PlatformStage.STOPPING,
                    VersionInfo.CURRENT_VERSION, 0));
        } catch (RuntimeException ignored) {
            // 事件发布失败不得阻断关闭
        }
        try {
            lifecycleManager.close();
        } catch (RuntimeException ignored) {
            // 生命周期管理器关闭失败时仍必须尝试释放 Scope 与调度器
        } finally {
            try {
                rootScope.shutdown();
            } catch (RuntimeException ignored) {
                // 同上
            } finally {
                scheduler.close();
            }
        }
    }
}
