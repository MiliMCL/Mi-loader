package org.loader.runtime.minecraft;

import org.loader.runtime.kernel.Scope;

import java.util.*;

/**
 * World bridge for Minecraft world management.
 * <p>
 * Each Minecraft world is represented as a separate Scope under the Minecraft scope.
 */
public class WorldBridge implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final Scope minecraftScope;
    private final Scope owner;
    private final Map<String, Scope> worlds = new LinkedHashMap<>();
    private volatile boolean closed = false;

    public WorldBridge(Scope owner, Scope minecraftScope) {
        this.id = "minecraft-world-bridge";
        this.owner = owner;
        this.minecraftScope = minecraftScope;
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
     * Creates and registers a new world scope.
     */
    public Scope createWorld(String worldName) {
        if (closed) {
            throw new IllegalStateException("WorldBridge is closed");
        }
        String scopeId = "minecraft:world:" + worldName;
        if (worlds.containsKey(scopeId)) {
            throw new IllegalStateException("World already exists: " + worldName);
        }
        Scope worldScope = minecraftScope.createChild(scopeId);
        worlds.put(scopeId, worldScope);
        return worldScope;
    }

    /**
     * Gets an existing world scope.
     */
    public Optional<Scope> getWorld(String worldName) {
        return Optional.ofNullable(worlds.get("minecraft:world:" + worldName));
    }

    /**
     * Unloads a world, triggering cleanup of all its resources.
     */
    public boolean unloadWorld(String worldName) {
        Scope world = worlds.remove("minecraft:world:" + worldName);
        if (world != null) {
            world.shutdown();
            minecraftScope.removeChild(world.id());
            return true;
        }
        return false;
    }

    /**
     * Returns all loaded world scope IDs.
     */
    public Set<String> loadedWorlds() {
        return Collections.unmodifiableSet(worlds.keySet());
    }

    @Override
    public void close() {
        closed = true;
        // Unload all worlds
        var worldScopes = new ArrayList<>(worlds.values());
        for (Scope world : worldScopes) {
            try {
                world.shutdown();
            } catch (Exception e) {
                // Continue cleanup even if world fails
            }
        }
        worlds.clear();
    }
}
