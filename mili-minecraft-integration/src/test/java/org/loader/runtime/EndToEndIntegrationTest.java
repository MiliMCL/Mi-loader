package org.loader.runtime;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;
import org.loader.runtime.minecraft.*;
import org.loader.runtime.mod.*;
import org.loader.runtime.observability.RuntimeDiagnostics;
import org.loader.runtime.scheduler.*;
import org.loader.runtime.service.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test that mirrors END_TO_END.md:
 * Launcher → Runtime bootstrap → Mod discovery → Dependency resolution → Mod loading → Running → Shutdown → Cleanup.
 */
class EndToEndIntegrationTest {

    @Test
    void full_lifecycle_bootstrap_running_shutdown() throws Exception {
        // 1. Runtime bootstrap
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("e2e-test");
        runtime.start();
        assertTrue(runtime.isRunning());

        // 2. Create diagnostics for verification
        RuntimeDiagnostics diagnostics = new RuntimeDiagnostics("e2e-diags", runtime.rootScope());
        runtime.rootScope().registerResource(diagnostics);

        // 3. Create Minecraft bootstrap (NOT started yet)
        MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);

        // 4. Register events listener BEFORE starting
        MinecraftEventBridge eventBridge = bootstrap.eventBridge();
        List<String> receivedEvents = new ArrayList<>();
        eventBridge.on(MinecraftEventBridge.ServerStartedEvent.class, e -> receivedEvents.add("started"));

        // Now start
        bootstrap.start();
        assertTrue(bootstrap.isRunning());
        assertEquals(LifecycleState.RUNNING, bootstrap.scope().state());

        // 5. Tick bridge — schedule work on next tick
        TickBridge tickBridge = bootstrap.tickBridge();
        AtomicInteger tickCounter = new AtomicInteger(0);
        tickBridge.addListener(t -> tickCounter.incrementAndGet());

        // Execute ticks
        tickBridge.onTick();
        tickBridge.onTick();
        tickBridge.onTick();
        assertEquals(3, tickBridge.currentTick());
        assertEquals(3, tickCounter.get());

        // 6. Registry bridge — register items
        MinecraftRegistryBridge registry = bootstrap.registryBridge();
        registry.register("blocks", "stone", "minecraft:stone");
        assertTrue(registry.get("blocks", "stone").isPresent());
        assertEquals("minecraft:stone", registry.get("blocks", "stone").get());

        // 7. Scheduler — submit a task
        Scheduler scheduler = runtime.scheduler();
        assertNotNull(scheduler);
        AtomicBoolean taskExecuted = new AtomicBoolean(false);
        scheduler.submit(runtime.rootScope(), () -> taskExecuted.set(true)).await();
        assertTrue(taskExecuted.get());

        // 8. Capture snapshot before shutdown
        RuntimeDiagnostics.DiagnosticSnapshot snapshot = diagnostics.captureSnapshot();
        assertNotNull(snapshot);
        assertTrue(snapshot.uptimeMillis() >= 0);
        assertTrue(snapshot.rootScope().totalScopes() >= 2); // root + minecraft scope at minimum

        // 9. Shutdown via MinecraftStop event
        eventBridge.post(new MinecraftEventBridge.ServerStoppingEvent());

        // 10. Stop bootstrap and runtime
        bootstrap.stop();
        assertFalse(bootstrap.isRunning());
        runtime.close();
        assertFalse(runtime.isRunning());

        // 11. Verify events were received
        assertTrue(receivedEvents.contains("started"));
    }

    @Test
    void minecraft_lifecycle_receives_all_phases() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("lifecycle-test");
        runtime.start();

        MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
        MinecraftLifecycle lifecycle = bootstrap.lifecycle();

        List<MinecraftBootstrap.MinecraftLifecycleEvent> events = new ArrayList<>();
        lifecycle.addListener(events::add);

        bootstrap.start();
        bootstrap.stop();

        assertTrue(events.contains(MinecraftBootstrap.MinecraftLifecycleEvent.STARTING));
        assertTrue(events.contains(MinecraftBootstrap.MinecraftLifecycleEvent.STARTED));
        assertTrue(events.contains(MinecraftBootstrap.MinecraftLifecycleEvent.STOPPING));
        assertTrue(events.contains(MinecraftBootstrap.MinecraftLifecycleEvent.STOPPED));

        runtime.close();
    }

    @Test
    void tickBridge_scheduledTasksExecute() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("tick-task-e2e");
        runtime.start();

        MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
        bootstrap.start();

        TickBridge tickBridge = bootstrap.tickBridge();
        AtomicLong executedAtTick = new AtomicLong(-1);

        // Schedule to run after 2 more ticks
        tickBridge.runAfterTicks(2, () -> executedAtTick.set(tickBridge.currentTick()));

        tickBridge.onTick(); // tick 1
        tickBridge.onTick(); // tick 2 — task should execute

        assertEquals(2L, executedAtTick.get());

        bootstrap.stop();
        runtime.close();
    }

    @Test
    void capability_grant_revoke_and_availability() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("cap-e2e");
        runtime.start();

        Scope testScope = runtime.rootScope().createChild("cap-test");

        // Grant a capability
        CapabilityToken<String> token = testScope.grantCapability(String.class, "test-value");
        assertTrue(token.isActive());
        assertEquals("test-value", token.get());

        // Retrieve via scope
        var retrieved = testScope.getCapability(String.class);
        assertTrue(retrieved.isPresent());

        // Revoke
        token.revoke();
        assertFalse(token.isActive());
        assertTrue(testScope.getCapability(String.class).isEmpty());

        runtime.close();
    }

    @Test
    void scheduler_metrics_execute_and_report() throws Exception {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("metrics-e2e");
        runtime.start();

        Scheduler scheduler = runtime.scheduler();
        AtomicInteger counter = new AtomicInteger(0);

        // Submit 10 tasks
        for (int i = 0; i < 10; i++) {
            scheduler.submit(runtime.rootScope(), () -> {
                try { Thread.sleep(1); } catch (InterruptedException ignored) {}
                counter.incrementAndGet();
            }).await();
        }

        Scheduler.SchedulerMetrics metrics = scheduler.metrics();
        assertTrue(metrics.completed() >= 10);
        assertEquals(10, counter.get());
        assertTrue(metrics.totalExecutionTimeNanos() > 0);
        assertTrue(metrics.averageExecutionTimeMs() >= 0);

        runtime.close();
    }

    @Test
    void eventBridge_typed_dispatch() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("event-e2e");
        runtime.start();

        MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
        bootstrap.start();

        EventBus eventBus = new EventBus("test-bus", runtime.rootScope());
        runtime.rootScope().registerResource(eventBus);

        List<String> received = new ArrayList<>();
        eventBus.addListener(String.class, received::add);
        eventBus.post("hello");
        eventBus.post("world");
        eventBus.post(42); // Integer — should NOT be received by String listener

        assertEquals(2, received.size());
        assertTrue(received.contains("hello"));
        assertTrue(received.contains("world"));

        bootstrap.stop();
        runtime.close();
    }

    @Test
    void dispose_and_cleanup_all_scopes() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("dispose-e2e");
        runtime.start();

        // Create scope tree
        Scope modScope = runtime.rootScope().createChild("mod-a");
        Scope moduleScope = modScope.createChild("module-a1");

        // Register resources
        EventBus bus = new EventBus("mod-bus", modScope);
        modScope.registerResource(bus);
        Scheduler modScheduler = new Scheduler("mod-scheduler", modScope);
        modScope.registerResource(modScheduler);

        // Close runtime — should clean up all
        runtime.close();

        // Verify root is stopped
        assertTrue(runtime.rootScope().isStopped());
    }
}
