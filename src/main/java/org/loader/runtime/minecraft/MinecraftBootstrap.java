package org.loader.runtime.minecraft;

import org.loader.runtime.kernel.*;
import org.loader.runtime.scheduler.Scheduler;
import org.loader.runtime.scheduler.TaskPriority;
import org.loader.runtime.service.EventBus;
import org.loader.runtime.client.ClientCapabilities;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minecraft integration bootstrap.
 * <p>
 * Bridges the Runtime with Minecraft server/client lifecycle.
 * Per ARCHITECTURE.md, Minecraft integration must remain outside the Kernel.
 * <p>
 * For CLIENT environments, creates the hierarchy:
 * <pre>
 * Runtime
 * └── Minecraft Client Scope
 *     ├── Render Scope
 *     └── (Client Mod Scopes added later)
 * </pre>
 * For SERVER environments, creates a single minecraft scope as before.
 */
public class MinecraftBootstrap {

    private final Scope minecraftScope;
    private final Scope renderScope;
    private final RuntimeEnvironment environment;
    private final Scheduler scheduler;
    private final MinecraftLifecycle lifecycle;
    private final TickBridge tickBridge;
    private final MinecraftEventBridge eventBridge;
    private final MinecraftRegistryBridge registryBridge;
    private final EntityBridge entityBridge;
    private final WorldBridge worldBridge;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates a bootstrap for the given runtime, auto-detecting environment from capabilities.
     */
    public MinecraftBootstrap(org.loader.runtime.kernel.Runtime runtime) {
        this(runtime, detectEnvironment(runtime));
    }

    /**
     * Creates a bootstrap with explicit environment.
     */
    public MinecraftBootstrap(org.loader.runtime.kernel.Runtime runtime, RuntimeEnvironment env) {
        this.environment = env;

        if (env.isClient()) {
            // CLIENT hierarchy per CLIENT_RENDERING.md:
            // Runtime → Minecraft Client Scope → Render Scope
            this.minecraftScope = runtime.rootScope().createChild("minecraft-client");
            this.renderScope = minecraftScope.createChild("render-scope");

            // Grant client-only capabilities on the render scope
            ClientCapabilities.grantRenderCapability(renderScope, env);
            ClientCapabilities.grantInputCapability(renderScope, env);
            ClientCapabilities.grantSoundCapability(renderScope, env);
        } else {
            // SERVER: single scope, no render scope
            this.minecraftScope = runtime.rootScope().createChild("minecraft");
            this.renderScope = null;
        }

        this.scheduler = new Scheduler("minecraft-scheduler", minecraftScope);
        this.lifecycle = new MinecraftLifecycle(minecraftScope);
        this.tickBridge = new TickBridge(minecraftScope, scheduler);
        this.eventBridge = new MinecraftEventBridge(minecraftScope);
        this.registryBridge = new MinecraftRegistryBridge(minecraftScope);
        this.entityBridge = new EntityBridge(minecraftScope, minecraftScope);
        this.worldBridge = new WorldBridge(minecraftScope, minecraftScope);

        // Register all components as managed resources
        minecraftScope.registerResource(scheduler);
        minecraftScope.registerResource(tickBridge);
        minecraftScope.registerResource(eventBridge);
        minecraftScope.registerResource(registryBridge);
        minecraftScope.registerResource(entityBridge);
        minecraftScope.registerResource(worldBridge);

        // Register environment as a capability for downstream resolution
        minecraftScope.grantCapability(RuntimeEnvironment.class, env);
    }

    private static RuntimeEnvironment detectEnvironment(org.loader.runtime.kernel.Runtime runtime) {
        // Default to dedicated server; can be overridden via constructor
        return RuntimeEnvironment.DEDICATED_SERVER;
    }

    public Scope scope() {
        return minecraftScope;
    }

    /**
     * Returns the Render Scope (CLIENT only), or null on SERVER.
     */
    public Scope renderScope() {
        return renderScope;
    }

    /**
     * Returns the runtime environment for this bootstrap.
     */
    public RuntimeEnvironment environment() {
        return environment;
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    public MinecraftLifecycle lifecycle() {
        return lifecycle;
    }

    public TickBridge tickBridge() {
        return tickBridge;
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

    /**
     * Starts the Minecraft bootstrap sequence.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("MinecraftBootstrap already started");
        }

        // Post start event to event bridge (for mod listeners)
        eventBridge.post(new MinecraftEventBridge.ServerStartingEvent());

        // Transition scope through lifecycle
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

        lifecycle.onStart();

        // Post started event
        eventBridge.post(new MinecraftEventBridge.ServerStartedEvent());
    }

    /**
     * Gracefully stops the Minecraft bootstrap.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            // Post stopping event
            eventBridge.post(new MinecraftEventBridge.ServerStoppingEvent());
            lifecycle.onStop();
            // Post stopped event
            eventBridge.post(new MinecraftEventBridge.ServerStoppedEvent());
            // Shutdown propagates to all children (including render scope)
            minecraftScope.shutdown();
        }
    }

    /**
     * Returns whether the Minecraft integration is currently running.
     */
    public boolean isRunning() {
        return running.get() && !minecraftScope.isStopped();
    }

    /**
     * Minecraft lifecycle events.
     */
    public enum MinecraftLifecycleEvent {
        STARTING,
        STARTED,
        STOPPING,
        STOPPED
    }
}
