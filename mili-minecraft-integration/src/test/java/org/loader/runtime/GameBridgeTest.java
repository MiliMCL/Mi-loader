package org.loader.runtime;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;
import org.loader.runtime.minecraft.*;
import org.loader.runtime.observability.ProfilerHooks;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EntityBridge, WorldBridge, and ProfilerHooks.
 */
class GameBridgeTest {

    @Test
    void entityBridge_spawnAndGetEntity() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("entity-test");
        runtime.start();

        MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
        bootstrap.beginDiscovery();
        bootstrap.beginPreparing();
        bootstrap.beginLoading();
        bootstrap.beginMinecraftBootstrap();
        bootstrap.start();

        EntityBridge entityBridge = bootstrap.entityBridge();
        long entityId = entityBridge.spawnEntity("player", "world");

        assertTrue(entityId > 0);
        assertEquals(1, entityBridge.entityCount());
        assertTrue(entityBridge.getEntity(entityId).isPresent());

        bootstrap.stop();
        runtime.close();
    }

    @Test
    void entityBridge_removeEntity() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("entity-remove");
        runtime.start();

        MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
        bootstrap.beginDiscovery();
        bootstrap.beginPreparing();
        bootstrap.beginLoading();
        bootstrap.beginMinecraftBootstrap();
        bootstrap.start();

        EntityBridge entityBridge = bootstrap.entityBridge();
        long entityId = entityBridge.spawnEntity("zombie", "world");

        assertTrue(entityBridge.removeEntity(entityId));
        assertEquals(0, entityBridge.entityCount());
        assertTrue(entityBridge.getEntity(entityId).isEmpty());

        bootstrap.stop();
        runtime.close();
    }

    @Test
    void worldBridge_createAndGetWorld() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("world-test");
        runtime.start();

        MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
        bootstrap.beginDiscovery();
        bootstrap.beginPreparing();
        bootstrap.beginLoading();
        bootstrap.beginMinecraftBootstrap();
        bootstrap.start();

        WorldBridge worldBridge = bootstrap.worldBridge();
        var worldScope = worldBridge.createWorld("overworld");

        assertNotNull(worldScope);
        assertTrue(worldBridge.getWorld("overworld").isPresent());
        assertFalse(worldBridge.getWorld("nether").isPresent());
        assertTrue(worldBridge.loadedWorlds().contains("minecraft:world:overworld"));

        bootstrap.stop();
        runtime.close();
    }

    @Test
    void profilerHooks_timing() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("profiler-test");
        runtime.start();

        ProfilerHooks profiler = new ProfilerHooks("test-profiler", runtime.rootScope());
        runtime.rootScope().registerResource(profiler);

        profiler.startTiming("tick");
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        long duration = profiler.endTiming("tick");

        assertTrue(duration > 0);
        assertTrue(duration >= 10_000_000); // At least 10ms in nanos

        runtime.close();
    }

    @Test
    void profilerHooks_counters() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("counter-test");
        runtime.start();

        ProfilerHooks profiler = new ProfilerHooks("test-profiler", runtime.rootScope());
        runtime.rootScope().registerResource(profiler);

        assertEquals(0L, profiler.getCounter("entities"));
        profiler.incrementCounter("entities");
        profiler.incrementCounter("entities");
        profiler.incrementCounter("entities");

        assertEquals(3L, profiler.getCounter("entities"));

        profiler.incrementCounter("chunks");
        assertEquals(1L, profiler.getCounter("chunks"));

        runtime.close();
    }
}
