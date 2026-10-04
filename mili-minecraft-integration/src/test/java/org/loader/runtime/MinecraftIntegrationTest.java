package org.loader.runtime;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;
import org.loader.runtime.minecraft.*;
import org.loader.runtime.observability.RuntimeDiagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Minecraft integration layer.
 */
class MinecraftIntegrationTest {

    @Test
    void minecraftBootstrap_startAndStop() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("mc-test");
        runtime.start();

        MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
        bootstrap.start();

        assertTrue(bootstrap.isRunning());
        assertEquals(LifecycleState.RUNNING, bootstrap.scope().state());

        bootstrap.stop();
        assertFalse(bootstrap.isRunning());
    }

    @Test
    void tickBridge_countsTicks() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("tick-test");
        runtime.start();

        MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
        bootstrap.start();

        TickBridge tickBridge = bootstrap.tickBridge();
        assertEquals(0L, tickBridge.currentTick());

        tickBridge.onTick();
        tickBridge.onTick();
        tickBridge.onTick();

        assertEquals(3L, tickBridge.currentTick());

        bootstrap.stop();
        runtime.close();
    }

    @Test
    void tickBridge_runsTaskAfterTicks() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("tick-task-test");
        runtime.start();

        MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
        bootstrap.start();

        TickBridge tickBridge = bootstrap.tickBridge();
        AtomicLong executedTick = new AtomicLong(-1);

        tickBridge.runAfterTicks(3, () -> executedTick.set(tickBridge.currentTick()));

        tickBridge.onTick();
        tickBridge.onTick();
        tickBridge.onTick();

        assertEquals(3L, executedTick.get());

        bootstrap.stop();
        runtime.close();
    }

    @Test
    void eventBridge_postsAndReceivesEvents() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("event-test");
        runtime.start();

        MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
        bootstrap.start();

        MinecraftEventBridge eventBridge = bootstrap.eventBridge();
        List<String> receivedEvents = new ArrayList<>();

        eventBridge.on(MinecraftEventBridge.TickEvent.class, event -> {
            receivedEvents.add("tick:" + event.tick());
        });
        eventBridge.on(MinecraftEventBridge.ServerStartedEvent.class, event -> {
            receivedEvents.add("started");
        });

        eventBridge.post(new MinecraftEventBridge.ServerStartedEvent());
        eventBridge.post(new MinecraftEventBridge.TickEvent(1));
        eventBridge.post(new MinecraftEventBridge.TickEvent(2));

        assertTrue(receivedEvents.contains("started"));
        assertTrue(receivedEvents.contains("tick:1"));
        assertTrue(receivedEvents.contains("tick:2"));

        bootstrap.stop();
        runtime.close();
    }

    @Test
    void registryBridge_registerAndGet() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("registry-test");
        runtime.start();

        MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
        bootstrap.start();

        MinecraftRegistryBridge registry = bootstrap.registryBridge();

        registry.register("blocks", "stone", "minecraft:stone");
        registry.register("blocks", "dirt", "minecraft:dirt");

        assertTrue(registry.get("blocks", "stone").isPresent());
        assertEquals("minecraft:stone", registry.get("blocks", "stone").get());
        assertTrue(registry.get("blocks", "missing").isEmpty());
        assertTrue(registry.keys("blocks").contains("stone"));

        bootstrap.stop();
        runtime.close();
    }

    @Test
    void diagnostics_capturesSnapshot() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("diag-test");
        runtime.start();

        RuntimeDiagnostics diagnostics = new RuntimeDiagnostics("test-diags", runtime.rootScope());
        runtime.rootScope().registerResource(diagnostics);

        diagnostics.setMetadata("test_run", "minecraft_integration");
        diagnostics.recordEvent("test", "snapshot_capture");

        RuntimeDiagnostics.DiagnosticSnapshot snapshot = diagnostics.captureSnapshot();

        assertNotNull(snapshot);
        assertTrue(snapshot.uptimeMillis() >= 0);
        assertEquals("minecraft_integration", snapshot.metadata().get("test_run"));
        assertNotNull(snapshot.threadInfo());
        assertTrue(snapshot.rootScope().totalScopes() >= 1);

        runtime.close();
    }

    @Test
    void minecraftLifecycle_receivesPhaseChanges() {
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
}
