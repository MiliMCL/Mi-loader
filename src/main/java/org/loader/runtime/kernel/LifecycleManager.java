package org.loader.runtime.kernel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the lifecycle of Runtime components.
 * <p>
 * Ensures deterministic startup and shutdown ordering.
 */
public class LifecycleManager implements Resource {

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, Scope> managedScopes = new LinkedHashMap<>();

    public LifecycleManager(String id, Scope owner) {
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
     * Registers a scope with this lifecycle manager.
     */
    public synchronized void registerScope(Scope scope) {
        if (closed.get()) {
            throw new IllegalStateException("LifecycleManager is closed");
        }
        managedScopes.put(scope.id(), scope);
    }

    /**
     * Returns all registered scopes.
     */
    public Collection<Scope> scopes() {
        return Collections.unmodifiableCollection(managedScopes.values());
    }

    /**
     * Initiates sequential startup of all registered scopes.
     */
    public void startAll() {
        for (Scope scope : managedScopes.values()) {
            if (scope.state() == LifecycleState.DISCOVERED) {
                scope.transitionTo(LifecycleState.RESOLVED);
                scope.transitionTo(LifecycleState.LOADED);
                scope.transitionTo(LifecycleState.INITIALIZED);
                scope.transitionTo(LifecycleState.REGISTERED);
                scope.transitionTo(LifecycleState.RUNNING);
            }
        }
    }

    /**
     * Initiates graceful shutdown in reverse order.
     */
    public void stopAll() {
        List<Scope> reversed = new ArrayList<>(managedScopes.values());
        Collections.reverse(reversed);
        for (Scope scope : reversed) {
            if (!scope.isStopped()) {
                scope.shutdown();
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stopAll();
        }
    }
}
