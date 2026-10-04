package org.loader.runtime.mod;

import org.loader.runtime.kernel.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovery system for inter-Mod communication.
 * <p>
 * Allows mods to publish and discover services in a capability-safe manner.
 * Services are scoped: when the publishing scope stops, services become unavailable.
 */
public class ServiceRegistry implements Resource {

    private final String id;
    private final Scope owner;
    private final Map<Class<?>, ServiceEntry<?>> services = new LinkedHashMap<>();
    private volatile boolean closed = false;

    public ServiceRegistry(String id, Scope owner) {
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
        return closed;
    }

    /**
     * Publishes a service for other mods to discover.
     */
    public <T> void publish(Class<T> serviceType, T implementation) {
        if (closed) {
            throw new IllegalStateException("ServiceRegistry is closed");
        }
        services.put(serviceType, new ServiceEntry<>(serviceType, implementation, owner));
    }

    /**
     * Discovers a published service.
     * Returns empty if the publishing scope has stopped.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> discover(Class<T> serviceType) {
        ServiceEntry<?> entry = services.get(serviceType);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.publisherScope().isStopped()) {
            services.remove(serviceType);
            return Optional.empty();
        }
        return Optional.of((T) entry.implementation());
    }

    /**
     * Unpublishes a service.
     */
    public boolean unpublish(Class<?> serviceType) {
        return services.remove(serviceType) != null;
    }

    /**
     * Returns all registered service types.
     */
    public Set<Class<?>> registeredTypes() {
        return Collections.unmodifiableSet(services.keySet());
    }

    @Override
    public void close() {
        closed = true;
        services.clear();
    }

    private record ServiceEntry<T>(Class<T> type, T implementation, Scope publisherScope) {
    }
}
