package org.loader.runtime.minecraft;

import org.loader.runtime.kernel.Scope;
import org.loader.runtime.service.Registry;

import java.util.*;

/**
 * Minecraft registry bridge.
 */
public class MinecraftRegistryBridge implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final Scope owner;
    private final Map<String, Registry<Object>> registries = new LinkedHashMap<>();
    private volatile boolean closed = false;

    public MinecraftRegistryBridge(Scope owner) {
        this.id = "minecraft-registry-bridge";
        this.owner = owner;
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
     * Gets or creates a named registry.
     */
    public Registry<Object> getRegistry(String name) {
        if (closed) {
            throw new IllegalStateException("RegistryBridge is closed");
        }
        return registries.computeIfAbsent(name,
                k -> owner.registerResource(new Registry<>(k, owner, k)));
    }

    /**
     * Registers an item in a named registry.
     */
    public Object register(String registryName, String key, Object value) {
        return getRegistry(registryName).register(key, value);
    }

    /**
     * Gets an item from a named registry.
     */
    public Optional<Object> get(String registryName, String key) {
        Registry<Object> reg = registries.get(registryName);
        if (reg == null) {
            return Optional.empty();
        }
        return reg.get(key);
    }

    /**
     * Returns all keys for a registry.
     */
    public Set<String> keys(String registryName) {
        Registry<Object> reg = registries.get(registryName);
        return reg != null ? reg.keys() : Set.of();
    }

    /**
     * Removes an item from a registry.
     */
    public Optional<Object> remove(String registryName, String key) {
        Registry<Object> reg = registries.get(registryName);
        if (reg == null) {
            return Optional.empty();
        }
        return reg.remove(key);
    }

    @Override
    public void close() {
        closed = true;
        registries.values().forEach(Registry::close);
        registries.clear();
    }
}
