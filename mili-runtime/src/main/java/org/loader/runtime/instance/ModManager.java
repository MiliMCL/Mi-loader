package org.loader.runtime.instance;

import org.loader.runtime.kernel.*;
import org.loader.runtime.mod.*;
import org.loader.runtime.util.ModLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages mod discovery, resolution, loading, and unloading for instances.
 * <p>
 * The ModManager orchestrates the full mod lifecycle:
 * <ol>
 *   <li><b>Discovery</b> — scans registered sources to find available mod manifests</li>
 *   <li><b>Resolution</b> — selects the mod set for an instance and resolves dependencies</li>
 *   <li><b>Loading</b> — loads resolved mods into a Runtime via ModLoader</li>
 *   <li><b>Unloading</b> — unloads mods in reverse order and cleans up scopes</li>
 * </ol>
 * <p>
 * Thread safety is provided through a ReentrantReadWriteLock.
 */
public class ModManager implements Resource {

    private final String id;
    private final Scope owner;
    private final ModDiscoverer discoverer;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, ModResolutionResult> resolutionCache = new ConcurrentHashMap<>();
    private volatile boolean closed = false;
    private final ModLogger logger;

    public ModManager(String id, Scope owner) {
        this.id = Objects.requireNonNull(id, "ModManager id must not be null");
        this.owner = Objects.requireNonNull(owner, "ModManager owner must not be null");
        this.discoverer = new ModDiscoverer(null);
        this.logger = new ModLogger(id, owner);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Scope owner() {
        return owner;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /**
     * Adds a mod source for discovery.
     */
    public void addSource(ModDiscoverer.ModSource source) {
        checkNotClosed();
        discoverer.addSource(source);
    }

    /**
     * Discovers all available mods from all registered sources.
     *
     * @return list of discovered mod manifests
     */
    public List<ModManifest> availableMods() {
        checkNotClosed();
        lock.readLock().lock();
        try {
            return discoverer.discover();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Finds an available mod by ID.
     */
    public Optional<ModManifest> findAvailable(String modId) {
        return availableMods().stream()
                .filter(m -> m.id().equals(modId))
                .findFirst();
    }

    /**
     * Resolves the mod set for an instance.
     * Takes the instance's requested mod IDs and resolves them against
     * available mods, checking dependencies and producing a load-ordered list.
     *
     * @param instance the instance definition
     * @return the resolution result
     */
    public ModResolutionResult resolve(Instance instance) {
        Objects.requireNonNull(instance, "instance must not be null");
        checkNotClosed();

        lock.readLock().lock();
        try {
            // Build lookup of available mods
            Map<String, ModManifest> available = new HashMap<>();
            for (ModManifest mod : discoverer.discover()) {
                available.put(mod.id(), mod);
            }

            List<String> requestedIds = instance.modIds();
            List<ModManifest> resolved = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            Set<String> visited = new HashSet<>();

            for (String modId : requestedIds) {
                resolveRecursive(modId, available, resolved, missing, visited);
            }

            if (!missing.isEmpty()) {
                ModResolutionResult result = ModResolutionResult.failed(
                        instance.instanceId(), missing, List.of());
                resolutionCache.put(instance.instanceId(), result);
                return result;
            }

            ModResolutionResult result = ModResolutionResult.success(
                    instance.instanceId(), resolved);
            resolutionCache.put(instance.instanceId(), result);
            logger.info("Resolved " + resolved.size() + " mods for instance: " + instance.instanceId());
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the cached resolution result for an instance, if any.
     */
    public Optional<ModResolutionResult> cachedResolution(String instanceId) {
        return Optional.ofNullable(resolutionCache.get(instanceId));
    }

    /**
     * Creates a resolved ModSet for an instance.
     * Convenience method that wraps resolve() and produces a ModSet.
     *
     * @throws ModManagerException if resolution fails
     */
    public ModSet createModSet(Instance instance) {
        ModResolutionResult result = resolve(instance);
        if (!result.success()) {
            throw new ModManagerException("Cannot create ModSet: " + result.summary());
        }
        return new ModSet(instance.instanceId(), result.resolvedMods());
    }

    /**
     * Loads all mods from a resolved set into a Runtime.
     *
     * @param runtime the runtime to load mods into
     * @param modSet  the resolved mod set
     * @return list of loaded Mod instances
     */
    public List<Mod> load(org.loader.runtime.kernel.Runtime runtime, ModSet modSet) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        Objects.requireNonNull(modSet, "modSet must not be null");
        checkNotClosed();

        lock.writeLock().lock();
        try {
            ModLoader loader = new ModLoader(runtime);
            List<Mod> loaded = new ArrayList<>();

            for (ModManifest manifest : modSet.mods()) {
                try {
                    Mod mod = loader.load(manifest, ModManager.class.getClassLoader());
                    loaded.add(mod);
                    logger.info("Loaded mod: " + manifest.id());
                } catch (Exception e) {
                    logger.info("Failed to load mod " + manifest.id() + ": " + e.getMessage());
                    // Rollback already loaded mods
                    for (Mod loadedMod : loaded) {
                        try {
                            loader.unload(loadedMod);
                        } catch (Exception ignored) {
                            // Best effort rollback
                        }
                    }
                    throw new ModManagerException(
                            "Failed to load mod: " + manifest.id(), e);
                }
            }

            logger.info("Loaded " + loaded.size() + " mods");
            return loaded;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Loads and resolves mods for an instance into a Runtime.
     * Convenience method combining resolve + load.
     */
    public List<Mod> loadForInstance(org.loader.runtime.kernel.Runtime runtime, Instance instance) {
        ModSet modSet = createModSet(instance);
        return load(runtime, modSet);
    }

    private void resolveRecursive(String modId,
                                  Map<String, ModManifest> available,
                                  List<ModManifest> resolved,
                                  List<String> missing,
                                  Set<String> visited) {
        if (visited.contains(modId)) return;

        ModManifest manifest = available.get(modId);
        if (manifest == null) {
            missing.add(modId);
            return;
        }

        visited.add(modId);

        // Resolve dependencies first
        for (ModManifest.DependencyEntry dep : manifest.dependencies()) {
            if (dep.required()) {
                resolveRecursive(dep.modId(), available, resolved, missing, visited);
            }
        }

        resolved.add(manifest);
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("ModManager is closed");
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            resolutionCache.clear();
            logger.info("ModManager closed");
        } finally {
            lock.writeLock().unlock();
        }
    }
}
