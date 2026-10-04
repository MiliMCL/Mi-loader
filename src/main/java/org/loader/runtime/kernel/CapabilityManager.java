package org.loader.runtime.kernel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages capability validation and granting.
 * <p>
 * Enforces the principle of least authority: grant only the smallest capability sufficient.
 */
public class CapabilityManager implements Resource {

    public enum CapabilityState {
        REQUESTED,
        VALIDATED,
        GRANTED,
        ACTIVE,
        REVOKED
    }

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, CapabilityDefinition<?>> definitions = new ConcurrentHashMap<>();

    public CapabilityManager(String id, Scope owner) {
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
     * Registers a capability definition.
     */
    @SuppressWarnings("unchecked")
    public <T> void registerCapability(String name, Class<T> type, CapabilityValidator<T> validator) {
        if (closed.get()) {
            throw new IllegalStateException("CapabilityManager is closed");
        }
        definitions.put(name, new CapabilityDefinition<>(name, type, validator));
    }

    /**
     * Validates whether a capability can be granted.
     */
    public boolean canGrant(String capabilityName, Class<?> requestedType) {
        CapabilityDefinition<?> def = definitions.get(capabilityName);
        if (def == null) {
            return false;
        }
        return def.type().isAssignableFrom(requestedType);
    }

    /**
     * Creates a validated capability token.
     */
    @SuppressWarnings("unchecked")
    public <T> CapabilityToken<T> grantCapability(Scope targetScope, String capabilityName) {
        CapabilityDefinition<T> def = (CapabilityDefinition<T>) definitions.get(capabilityName);
        if (def == null) {
            throw new IllegalArgumentException("Unknown capability: " + capabilityName);
        }
        T implementation = def.validator().createImplementation(def.type());
        return targetScope.grantCapability(def.type(), implementation);
    }

    @Override
    public void close() {
        closed.set(true);
        definitions.clear();
    }

    /**
     * Capability definition record.
     */
    record CapabilityDefinition<T>(String name, Class<T> type, CapabilityValidator<T> validator) {
    }

    /**
     * Validator and factory for capability implementations.
     */
    @FunctionalInterface
    public interface CapabilityValidator<T> {
        T createImplementation(Class<T> type);
    }
}
