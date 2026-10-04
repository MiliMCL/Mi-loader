package org.loader.runtime.kernel;

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

        // Register kernel components as resources
        rootScope.registerResource(lifecycleManager);
        rootScope.registerResource(capabilityManager);
        rootScope.registerResource(permissionManager);
        rootScope.registerResource(resourceRegistry);
        rootScope.registerResource(scheduler);
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
            lifecycleManager.close();
            rootScope.shutdown();
            scheduler.close();
        }
    }
}
