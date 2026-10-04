package org.loader.runtime.reference;

import org.loader.runtime.mod.*;
import org.loader.runtime.minecraft.RuntimeEnvironment;

/**
 * Reference implementation of a minimal Mod.
 * <p>
 * Demonstrates the full mod lifecycle per REFERENCE_MOD.md:
 * <ol>
 *   <li>Construct with a {@link ModContext}</li>
 *   <li>Initialize — register configs, listen to events, request capabilities</li>
 *   <li>Start — submit work to scheduler, publish services</li>
 *   <li>Activate — respond to events, interact with registry</li>
 *   <li>Deactivate — cancel tasks, unpublish</li>
 *   <li>Stop — release resources</li>
 * </ol>
 * <p>
 * This mod performs no real work — it exists to verify that the Runtime SDK
 * supports the complete mod lifecycle contract.
 */
public class ReferenceMod {

    private final ModContext context;
    private volatile boolean active = false;

    public ReferenceMod(ModContext context) {
        this.context = context;
    }

    public String name() {
        return "Reference Mod";
    }

    public String modId() {
        return context.modId();
    }

    /**
     * Initialize phase: register config and event listeners.
     */
    public void initialize() {
        // Register a configuration
        context.createConfig("general");
        context.getConfig("general").ifPresent(cfg -> cfg.set("enabled", "true"));

        // Register event listener via the SDK events API
        context.events().addListener(String.class, event -> {
            if (context.isActive()) {
                context.logger().info("Received event: " + event);
            }
        });

        context.logger().info("ReferenceMod initialized");
    }

    /**
     * Start phase: submit work, publish services.
     */
    public void start() {
        active = true;

        // Submit a task via mod scheduler
        context.submit(() -> context.logger().info("Mod task executed"));

        // Publish a service in the mod registry
        context.registry().register("hello-service", "Hello from " + modId());

        // Post an event
        context.events().post("mod-started");

        context.logger().info("ReferenceMod started");
    }

    /**
     * Activate phase (called when game enters running state).
     */
    public void activate() {
        active = true;
        context.logger().info("ReferenceMod active");
    }

    /**
     * Deactivate phase (called when game pauses).
     */
    public void deactivate() {
        active = false;
        context.logger().info("ReferenceMod deactivated");
    }

    /**
     * Stop phase: cancel tasks, unpublish, release resources.
     */
    public void stop() {
        active = false;
        context.registry().remove("hello-service");
        context.logger().info("ReferenceMod stopped");
    }

    /**
     * Whether the mod is currently active.
     */
    public boolean isActiveMod() {
        return active;
    }

    /**
     * Get the mod's context (for testing).
     */
    public ModContext context() {
        return context;
    }
}
