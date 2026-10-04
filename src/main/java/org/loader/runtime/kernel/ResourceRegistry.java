package org.loader.runtime.kernel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central registry for tracking all Runtime resources.
 * <p>
 * Ensures every resource has a known owner and lifecycle.
 */
public class ResourceRegistry implements Resource {

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, ResourceEntry> resources = new ConcurrentHashMap<>();

    public ResourceRegistry(String id, Scope owner) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
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
        return closed.get();
    }

    /**
     * Registers a resource with the registry.
     */
    public <T extends Resource> T register(T resource) {
        if (closed.get()) {
            throw new IllegalStateException("ResourceRegistry is closed");
        }
        ResourceEntry entry = new ResourceEntry(resource, resource.owner());
        resources.put(resource.id(), entry);
        return resource;
    }

    /**
     * Looks up a resource by ID.
     */
    public Optional<Resource> get(String resourceId) {
        return Optional.ofNullable(resources.get(resourceId)).map(ResourceEntry::resource);
    }

    /**
     * Removes a resource from the registry.
     */
    public Optional<Resource> unregister(String resourceId) {
        ResourceEntry entry = resources.remove(resourceId);
        return Optional.ofNullable(entry).map(ResourceEntry::resource);
    }

    /**
     * Returns all resources owned by a specific scope.
     */
    public List<Resource> getResourcesByOwner(Scope owner) {
        return resources.values().stream()
                .filter(e -> e.owner().equals(owner))
                .map(ResourceEntry::resource)
                .toList();
    }

    /**
     * Validates that all registered resources have valid owners.
     * Returns list of orphan resources (invariant violations).
     */
    public List<Resource> detectOrphans() {
        List<Resource> orphans = new ArrayList<>();
        for (ResourceEntry entry : resources.values()) {
            Resource res = entry.resource();
            if (res instanceof Scope scope && scope.owner() != null && !resources.containsKey(scope.owner().id())) {
                orphans.add(res);
            }
        }
        return Collections.unmodifiableList(orphans);
    }

    @Override
    public void close() {
        closed.set(true);
        resources.clear();
    }

    private record ResourceEntry(Resource resource, Scope owner) {
    }
}
