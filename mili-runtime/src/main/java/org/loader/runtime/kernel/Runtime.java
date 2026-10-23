package org.loader.runtime.kernel;

import org.loader.api.diagnostics.PerformanceService;
import org.loader.runtime.diagnostics.PerformanceMonitor;
import org.loader.runtime.scheduler.Scheduler;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The main entry point and bootstrap for the Runtime.
 * <p>
 * The Runtime owns execution. Mods do not own the Runtime.
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

        // Register kernel components as resources
        rootScope.registerResource(lifecycleManager);
        rootScope.registerResource(capabilityManager);
        rootScope.registerResource(permissionManager);
        rootScope.registerResource(resourceRegistry);
        rootScope.registerResource(scheduler);
        rootScope.registerResource(performance);

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
     * Starts the Runtime.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Runtime already started");
        }
        rootScope.transitionTo(LifecycleState.RESOLVED);
        rootScope.transitionTo(LifecycleState.LOADED);
        rootScope.transitionTo(LifecycleState.INITIALIZED);
        rootScope.transitionTo(LifecycleState.REGISTERED);
        rootScope.transitionTo(LifecycleState.RUNNING);
        lifecycleManager.startAll();
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
        if (closed.compareAndSet(false, true)) {
            // 先摘掉全局 provider，避免关闭中的实例继续被读到；
            // 按身份撤销，多个 Runtime 并存时不会误伤仍在运行的那个。
            PerformanceService.clearProvider(performance);
            try {
                lifecycleManager.close();
            } finally {
                // 即使生命周期管理器关闭失败，Scope 与调度器也必须尝试释放 ——
                // 否则一次异常会让线程池与资源图一直活到进程结束。
                try {
                    rootScope.shutdown();
                } finally {
                    scheduler.close();
                }
            }
        }
    }
}
