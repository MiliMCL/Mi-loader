package org.loader.runtime.minecraft;

import org.loader.runtime.kernel.Scope;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entity bridge manages Minecraft entity lifecycle through Runtime scopes.
 * <p>
 * Each entity gets its own child scope under a world scope, ensuring
 * proper cleanup when the entity is removed.
 */
public class EntityBridge implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final Scope owner;
    private final Scope minecraftScope;
    private final Map<Long, Scope> entityScopes = new ConcurrentHashMap<>();
    private final AtomicLong entityIdCounter = new AtomicLong(1);
    private volatile boolean closed = false;

    public EntityBridge(Scope owner, Scope minecraftScope) {
        this.id = "minecraft-entity-bridge";
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
     * Spawns an entity and returns its unique ID.
     */
    public long spawnEntity(String entityType, String worldName) {
        if (closed) {
            throw new IllegalStateException("EntityBridge is closed");
        }
        long entityId = entityIdCounter.getAndIncrement();
        String scopeId = "minecraft:entity:" + worldName + ":" + entityId;

        Scope entityScope = minecraftScope.createChild(scopeId);
        entityScopes.put(entityId, entityScope);
        return entityId;
    }

    /**
     * Gets the scope for an entity.
     */
    public Optional<Scope> getEntity(long entityId) {
        return Optional.ofNullable(entityScopes.get(entityId));
    }

    /**
     * Removes an entity, triggering cleanup of its scope.
     */
    public boolean removeEntity(long entityId) {
        Scope scope = entityScopes.remove(entityId);
        if (scope != null) {
            scope.shutdown();
            minecraftScope.removeChild(scope.id());
            return true;
        }
        return false;
    }

    /**
     * Returns the count of active entities.
     */
    public int entityCount() {
        return entityScopes.size();
    }

    /**
     * Returns all active entity IDs.
     */
    public Set<Long> activeEntities() {
        return Collections.unmodifiableSet(entityScopes.keySet());
    }

    @Override
    public void close() {
        closed = true;
        // Remove all entities
        var ids = new ArrayList<>(entityScopes.keySet());
        for (long id : ids) {
            removeEntity(id);
        }
        entityScopes.clear();
    }
}
