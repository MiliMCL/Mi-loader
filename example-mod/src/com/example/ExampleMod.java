package com.example;

import org.loader.api.Mod;
import org.loader.api.ModContext;
import org.loader.api.ModMetadata;
import org.loader.api.resource.Resource;
import org.loader.api.lifecycle.LifecycleState;
import org.loader.api.event.EventBus;
import org.loader.api.event.Subscription;
import org.loader.api.scheduler.Scheduler;
import org.loader.api.capability.CapabilityToken;
import org.loader.api.exception.ModException;
import org.loader.api.exception.CapabilityDeniedException;
import org.loader.api.Environment;
import org.loader.api.Logger;

/**
 * Mili Example Mod — only imports from org.loader.api.
 *
 * This demonstrates the Mili Public API:
 * - Mod entry point via initialize()
 * - ModContext as the main API surface
 * - EventBus subscription
 * - Scheduler (one-shot + repeating)
 * - Resource registration with automatic cleanup
 * - Capability request (deny-by-default)
 * - Environment query
 * - Lifecycle observation
 *
 * Does NOT import:
 * - Runtime internals (org.loader.runtime.*)
 * - Loader internals (org.loader.loader.*)
 * - Minecraft classes
 */
public class ExampleMod implements Mod {

    private Subscription tickSub;
    private Subscription startedSub;
    private Resource resource;
    private int tickCount = 0;

    @Override
    public void onInitialize(ModContext context) {
        ModMetadata meta = context.metadata();
        Logger log = context.logger();

        log.info("[" + meta.id() + "] Initializing v" + meta.version());
        log.info("  Environment: " + context.environment());

        // Lifecycle check
        if (context.scope() != null) {
            log.info("  Scope: " + context.scope().id());
            log.info("  Lifecycle: " + context.lifecycle().state());
        }

        // EventBus subscription
        EventBus events = context.events();

        startedSub = events.subscribe(String.class, msg -> {
            log.info("[Event] " + msg);
        });

        tickSub = events.subscribe(Long.class, tick -> {
            tickCount++;
            if (tickCount % 100 == 0) {
                log.info("[Tick] #" + tickCount);
            }
        });

        // Schedule a one-shot task
        Scheduler scheduler = context.scheduler();
        scheduler.submit(() -> {
            log.info("[Scheduler] one-shot task executed");
        });

        // Register a resource (auto-cleanup on mod stop)
        resource = new ExampleResource("example:resource-1");
        context.resources().register(resource);
        log.info("  Resource registered: " + resource.id() + " closed=" + resource.isClosed());

        // Environment
        Environment env = context.environment();
        log.info("  isClient=" + env.isClient());

        // Capability request (will likely be denied by default)
        try {
            var renderCap = context.capabilities().getCapability(Object.class);
            log.info("  render-capability: present=" + renderCap.isPresent());
        } catch (Exception e) {
            log.debug("  capability check: " + e.getMessage());
        }

        // Mark running
        if (context.scope() != null) {
            try {
                context.scope().transitionTo(LifecycleState.RUNNING);
                log.info("  -> RUNNING");
            } catch (Exception e) {
                log.warn("  lifecycle transition: " + e.getMessage());
            }
        }

        log.info("[" + mod.id() + "] Fully initialized");
    }

    /**
     * Example resource implementing the Public Resource interface.
     * When the mod stops, Mili Runtime will call close() automatically.
     */
    private static class ExampleResource implements Resource {
        private final String resId;
        private volatile boolean closed = false;

        ExampleResource(String id) { this.resId = id; }
        @Override public String id() { return resId; }
        @Override public org.loader.api.Scope owner() { return null; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() {
            closed = true;
        }
    }
}
