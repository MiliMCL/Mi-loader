package org.loader.runtime.service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Generic registry for named objects.
 * <p>
 * Provides a capability-safe way to register and look up game objects.
 *
 * @param <T> the type of objects in this registry
 */
public class Registry<T> implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final org.loader.runtime.kernel.Scope owner;
    private final String name;
    private final Map<String, T> entries = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    public Registry(String id, org.loader.runtime.kernel.Scope owner, String name) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        this.name = Objects.requireNonNull(name);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public org.loader.runtime.kernel.Scope owner() {
        return owner;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public String name() {
        return name;
    }

    /**
     * Registers an entry in the registry.
     */
    public T register(String key, T value) {
        if (closed) {
            throw new IllegalStateException("Registry is closed: " + id);
        }
        return entries.put(key, value);
    }

    /**
     * Looks up an entry by key.
     */
    public Optional<T> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * Removes an entry.
     */
    public Optional<T> remove(String key) {
        return Optional.ofNullable(entries.remove(key));
    }

    /**
     * Returns all registered keys.
     */
    public Set<String> keys() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    /**
     * Returns all registered values.
     */
    public Collection<T> values() {
        return Collections.unmodifiableCollection(entries.values());
    }

    @Override
    public void close() {
        closed = true;
        entries.clear();
    }
}
