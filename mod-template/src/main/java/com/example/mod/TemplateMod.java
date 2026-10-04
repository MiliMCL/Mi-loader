package com.example.mod;

import org.loader.api.Mod;
import org.loader.runtime.mod.ModContext;
import org.loader.api.ModMetadata;
import org.loader.api.Scope;
import org.loader.api.Environment;
import org.loader.api.Logger;
import org.loader.api.Lifecycle;
import org.loader.api.EventBus;
import org.loader.api.Scheduler;
import org.loader.api.CapabilityManager;
import org.loader.api.PermissionManager;
import org.loader.api.Subscription;
import org.loader.api.TaskHandle;
import org.loader.api.TaskPriority;
import org.loader.api.Resource;
import org.loader.api.LifecycleState;
import org.loader.api.Permission;
import org.loader.api.Mili;
import org.loader.api.ModException;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mili Mod 开发模板
 *
 * 演示所有 Mili Public API 的用法。
 */
public class TemplateMod implements Mod {

    private ModContext context;
    private Logger log;
    private final AtomicLong tickCounter = new AtomicLong(0);

    @Override
    public void onInitialize(ModContext ctx) {
        this.context = ctx;
        this.log = ctx.logger();
        log.info("[" + ctx.metadata().id() + "] 初始化中...");

        try {
            demonstrateMetadata(ctx);
            demonstrateLifecycle(ctx);
            demonstrateEnvironment(ctx);
            demonstrateEventBus(ctx);
            demonstrateScheduler(ctx);
            demonstrateResources(ctx);
            demonstrateCapabilities(ctx);
            demonstratePermissions(ctx);

            // RUNNING
            ctx.scope().transitionTo(LifecycleState.RUNNING);
            log.info("  -> RUNNING");

            // Fire demo event
            if (context.events() != null) {
                context.events().post("[TemplateMod] 初始化完成!");
            }
            log.info("[" + ctx.metadata().id() + "] 初始化完成!");
        } catch (Exception e) {
            log.error("初始化失败: " + e.getMessage());
            throw new ModException(ctx.metadata().id(), e);
        }
    }

    void demonstrateMetadata(ModContext ctx) {
        ModMetadata meta = ctx.metadata();
        log.info("===== Metadata =====");
        log.info("  ID:      " + meta.id());
        log.info("  Name:    " + meta.name());
        log.info("  Version: " + meta.version());
    }

    void demonstrateLifecycle(ModContext ctx) {
        if (ctx.scope() == null) return;
        log.info("===== Lifecycle =====");
        log.info("  Scope: " + ctx.scope().id());
        log.info("  State: " + ctx.scope().state());
        ctx.lifecycle().addListener(e -> log.info("[Lifecycle] " + e.from() + " -> " + e.to()));
    }

    void demonstrateEnvironment(ModContext ctx) {
        Environment env = ctx.environment();
        log.info("===== Environment =====");
        log.info("  Client: " + env.isClient());
        log.info("  Server: " + env.isServer());
        log.info("  API:    " + Mili.apiVersion());
    }

    void demonstrateEventBus(ModContext ctx) {
        if (ctx.events() == null) return;
        log.info("===== EventBus =====");
        ctx.events().subscribe(String.class, msg -> log.info("[Event] " + msg));
        ctx.events().subscribe(Long.class, tick -> {
            long n = tickCounter.incrementAndGet();
            if (n % 100 == 0) log.info("[Tick] #" + n);
        });
        log.info("  subscribed");
    }

    void demonstrateScheduler(ModContext ctx) {
        if (ctx.scheduler() == null) return;
        log.info("===== Scheduler =====");
        ctx.scheduler().submit(ctx.scope(), () -> log.info("[Sch] one-shot"), TaskPriority.NORMAL);
        ctx.scheduler().submitDelayed(ctx.scope(), () -> log.info("[Sch] 5s delay"), Duration.ofSeconds(5), TaskPriority.LOW);
        ctx.scheduler().submitRepeating(ctx.scope(), () -> log.info("[Sch] 1s beat"), Duration.ZERO, Duration.ofSeconds(1), TaskPriority.NORMAL);
        log.info("  tasks: one-shot + delayed + repeating");
    }

    void demonstrateResources(ModContext ctx) {
        if (ctx.resources() == null) return;
        log.info("===== Resources =====");
        ctx.scope().registerResource(new DemoRes("template:demo"));
        log.info("  resource registered");
    }

    void demonstrateCapabilities(ModContext ctx) {
        if (ctx.capabilities() == null) return;
        log.info("===== Capabilities (Deny-by-Default) =====");
        var unk = ctx.capabilities().getCapability(String.class);
        log.info("  String cap: " + (unk.isPresent() ? "GRANTED" : "DENIED (预期)"));
    }

    void demonstratePermissions(ModContext ctx) {
        if (ctx.permissions() == null) return;
        log.info("===== Permissions =====");
        log.info("  RES_READ:  " + ctx.permissions().hasPermission(Permission.RESOURCE_READ));
        log.info("  RES_WRITE: " + ctx.permissions().hasPermission(Permission.RESOURCE_WRITE));
        log.info("  NETWORK:   " + ctx.permissions().hasPermission(Permission.NETWORK_ACCESS));
        log.info("  NATIVE:    " + ctx.permissions().hasPermission(Permission.NATIVE_ACCESS));
    }

    private static class DemoRes implements Resource {
        private final String id;
        private volatile boolean closed = false;
        DemoRes(String id) { this.id = id; }
        public String id() { return id; }
        public Scope owner() { return null; }
        public boolean isClosed() { return closed; }
        public void close() { closed = true; }
    }
}
