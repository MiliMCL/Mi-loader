package com.example;

import org.loader.runtime.mod.ModContext;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.LifecycleState;
import java.util.concurrent.atomic.AtomicLong;

public class TestMod {
    static final AtomicLong tickCounter = new AtomicLong(0);
    static volatile ModContext context;
    static volatile boolean receivedStarted = false;

    public void initialize(ModContext ctx) {
        context = ctx;
        ctx.logger().info("TestMod initialized with full ModContext!");
        ctx.logger().info("  scope: " + ctx.scope().id());

        // Register resource
        ctx.scope().registerResource(new TestResource("testmod:resource"));
        ctx.logger().info("  resource registered");

        // Subscribe to events
        ctx.events().addListener(String.class, event -> {
            ctx.logger().info("[TestMod] event: " + event);
        });
        ctx.logger().info("  eventbus subscribed");

        // Transition to RUNNING
        ctx.scope().transitionTo(LifecycleState.RUNNING);
        ctx.logger().info("  scope -> RUNNING");

        // Try subscribing to MC events
        try {
            Class<?> clientStarted = Class.forName("org.loader.runtime.minecraft.MinecraftEventBridge$ClientStartedEvent");
            ctx.events().addListener(clientStarted, e -> {
                receivedStarted = true;
                ctx.logger().info("[TestMod] *** ClientStarted event received! ***");
            });

            Class<?> clientTick = Class.forName("org.loader.runtime.minecraft.MinecraftEventBridge$ClientTickEvent");
            ctx.events().addListener(clientTick, e -> {
                long count = tickCounter.incrementAndGet();
                if (count % 100 == 0) {
                    ctx.logger().info("[TestMod] ClientTick #" + count);
                }
            });

            Class<?> worldJoin = Class.forName("org.loader.runtime.minecraft.MinecraftEventBridge$ClientWorldJoinEvent");
            ctx.events().addListener(worldJoin, e -> {
                ctx.logger().info("[TestMod] WorldJoin event received!");
            });

            ctx.logger().info("  MC events subscribed");
        } catch (Exception e) {
            ctx.logger().info("  MC event classes not available (standalone test)");
        }

        // Deny-by-default capability test
        try {
            var renderCap = Class.forName("org.loader.runtime.client.ClientCapabilities$RenderCapability");
            var granted = ctx.getCapability(renderCap);
            ctx.logger().info("  capability test: render-cap granted=" + granted.isPresent());
        } catch (Exception e) {
            ctx.logger().info("  capability test skipped");
        }
    }

    static class TestResource implements Resource {
        final String id;
        volatile boolean closed = false;
        TestResource(String id) { this.id = id; }
        public String id() { return id; }
        public Scope owner() { return null; }
        public boolean isClosed() { return closed; }
        public void close() { closed = true; }
    }
}
