package org.loader.api;

import org.loader.api.lifecycle.Lifecycle;

import java.util.Optional;

/**
 * Facade interface for a Mod's interaction surface with the Runtime.
 * <p>
 * This interface delegates to the internal runtime implementation
 * ({@code org.loader.runtime.mod.ModContext}) and presents a simplified,
 * stable API to mods.
 */
public interface ModContext {

    /**
     * Returns the mod's unique identifier.
     */
    String modId();

    /**
     * Returns simplified metadata for this mod.
     */
    ModMetadata metadata();

    /**
     * Returns the observable lifecycle for this mod.
     */
    Lifecycle lifecycle();

    /**
     * Returns the mod's scheduler.
     * Mods should use this to submit async work instead of creating their own threads.
     */
    org.loader.api.scheduler.Scheduler scheduler();

    /**
     * Returns the mod's event bus.
     * Events are automatically cleaned up when the mod stops.
     */
    org.loader.api.event.EventBus events();

    /**
     * Returns the Minecraft registry, for declaring blocks and items.
     *
     * <p>Registration is only open while the mod is initializing; the platform
     * closes it before the world loads, because Minecraft's registries are
     * frozen at that point. Registering later fails loudly rather than
     * corrupting the registry.
     *
     * @return the registry bound to this mod's namespace
     */
    org.loader.api.registry.MinecraftRegistry registry();

    /**
     * Returns a world view bound to this mod.
     *
     * <p>Returns {@code null} before a world is loaded and after shutdown.
     * Mods must null-check rather than assume a world is always present.
     */
    org.loader.api.world.WorldView world();

    /**
     * Returns the mod's resource manager.
     * Provides path-based resource loading with security checks.
     */
    org.loader.api.resource.ResourceManager resources();

    /**
     * Returns the mod's logger.
     */
    Logger logger();

    /**
     * Returns this mod's persistent key–value storage.
     *
     * <p>Backed by a properties file under
     * {@code <gameDir>/config/mili/storage/<modId>.properties}. Intended for
     * small state (a few dozen keys): window position, first-run flags,
     * last scan results. Mods that need to persist bulk data should manage
     * their own files instead — this store keeps everything in memory.
     *
     * <p>Writes are visible immediately but only guaranteed on disk after
     * {@link org.loader.api.storage.ModStorage#flush()}; the platform
     * flushes automatically when the mod's scope shuts down.
     *
     * @return the storage bound to this mod's id (same instance every call)
     * @throws org.loader.api.storage.StorageUnavailableException if the
     *         platform has not reached its runtime phase yet
     */
    default org.loader.api.storage.ModStorage storage() {
        return org.loader.api.storage.StorageService.forMod(modId());
    }

    /**
     * Returns a live snapshot of platform runtime metrics (heap, GC, threads).
     *
     * <p>Cheap enough to call once per second from a UI, but not per frame —
     * sample it at a sensible interval and cache the result yourself.
     * Returns a zeroed snapshot if the platform has not installed a provider.
     */
    default org.loader.api.diagnostics.PlatformMetrics metrics() {
        return org.loader.api.diagnostics.MetricsService.current();
    }

    /**
     * Returns a windowed TPS / tick-latency / FPS snapshot.
     *
     * <p>Unlike {@link #metrics()}, this is sampled every tick and answers
     * "is the game stuttering right now". Both are cheap enough for a
     * panel refreshing at 4 Hz; neither should be called per frame.
     *
     * <p>Returns an empty snapshot when the platform has no tick source
     * (dedicated server without a world, tests, early startup).
     */
    default org.loader.api.diagnostics.PerformanceSnapshot performance() {
        return org.loader.api.diagnostics.PerformanceService.current();
    }

    /**
     * Returns the environment type (CLIENT, SERVER, DEDICATED_SERVER).
     */
    Environment environment();

    /**
     * Returns whether this mod's scope is still active and accepting new work.
     */
    default boolean isActive() {
        return lifecycle().state().allowsNewWork();
    }

    /**
     * Gets a capability if the mod has been granted one.
     *
     * @param capabilityType the capability interface type
     * @return an Optional containing the capability implementation if granted and active
     */
    <T> Optional<T> getCapability(Class<T> capabilityType);

    /**
     * Submits a task to the mod's scheduler with default priority.
     * Equivalent to {@code scheduler().submit(work)}.
     */
    default void submit(Runnable work) {
        scheduler().submit(work);
    }

    /**
     * Submits a task to the mod's scheduler with the specified priority.
     */
    default void submit(Runnable work, org.loader.api.scheduler.TaskPriority priority) {
        scheduler().submit(work, priority);
    }
}
