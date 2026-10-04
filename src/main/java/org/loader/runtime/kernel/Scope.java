package org.loader.runtime.kernel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Scope is a lifecycle boundary.
 * <p>
 * A Scope may contain child scopes, tasks, resources, event subscriptions,
 * network handles, capabilities, and services.
 * Closing a Scope initiates coordinated cleanup.
 */
public class Scope implements Resource {

    private final String id;
    private final Scope parent;
    private final AtomicReference<LifecycleState> state = new AtomicReference<>(LifecycleState.DISCOVERED);
    private final Map<String, Scope> childScopes = new LinkedHashMap<>();
    private final List<Resource> ownedResources = new CopyOnWriteArrayList<>();
    private final Map<Class<?>, CapabilityToken<?>> capabilities = new ConcurrentHashMap<>();
    private final List<ScopeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    public Scope(String id, Scope parent) {
        this.id = Objects.requireNonNull(id, "scope id must not be null");
        this.parent = parent;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Scope owner() {
        return parent;
    }

    /**
     * Returns the parent scope, or null if this is the root.
     */
    public Scope parent() {
        return parent;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public boolean isStopped() {
        LifecycleState s = state.get();
        return s == LifecycleState.STOPPED || s == LifecycleState.FAILED || closed;
    }

    /**
     * Returns the current lifecycle state.
     */
    public LifecycleState state() {
        return state.get();
    }

    /**
     * Transitions to the next lifecycle state.
     *
     * @throws IllegalStateException if transition is invalid
     */
    public void transitionTo(LifecycleState target) {
        LifecycleState current;
        do {
            current = state.get();
            if (!current.canTransitionTo(target)) {
                throw new IllegalStateException(
                        "Invalid transition from " + current + " to " + target + " in scope " + id);
            }
        } while (!state.compareAndSet(current, target));
        notifyListeners(current, target);
    }

    /**
     * Adds a child scope.
     */
    public synchronized Scope createChild(String childId) {
        checkNotStopped();
        Scope child = new Scope(childId, this);
        childScopes.put(childId, child);
        return child;
    }

    /**
     * Removes a child scope.
     */
    public synchronized void removeChild(String childId) {
        childScopes.remove(childId);
    }

    /**
     * Registers a resource owned by this scope.
     */
    public <T extends Resource> T registerResource(T resource) {
        checkNotStopped();
        ownedResources.add(resource);
        return resource;
    }

    /**
     * Returns an immutable view of resources owned by this scope.
     */
    public List<Resource> getResources() {
        return List.copyOf(ownedResources);
    }

    /**
     * Returns the first resource owned by this scope matching the given id.
     */
    public Optional<Resource> getResourceById(String resourceId) {
        return ownedResources.stream()
                .filter(r -> r.id().equals(resourceId))
                .findFirst();
    }

    /**
     * Registers a capability for this scope.
     */
    @SuppressWarnings("unchecked")
    public <T> CapabilityToken<T> grantCapability(Class<T> capabilityType, T implementation) {
        checkNotStopped();
        CapabilityTokenImpl<T> token = new CapabilityTokenImpl<>(capabilityType, this, implementation, new AtomicBoolean(true));
        capabilities.put(capabilityType, token);
        return token;
    }

    /**
     * Returns a capability if granted.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<CapabilityToken<T>> getCapability(Class<T> capabilityType) {
        return Optional.ofNullable((CapabilityToken<T>) capabilities.get(capabilityType))
                .filter(CapabilityToken::isActive);
    }

    /**
     * Adds a scope lifecycle listener.
     */
    public void addListener(ScopeListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a scope lifecycle listener.
     */
    public void removeListener(ScopeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Returns all child scopes.
     */
    public Collection<Scope> children() {
        return Collections.unmodifiableCollection(childScopes.values());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        shutdown();
    }

    /**
     * Initiates graceful shutdown of this scope.
     * Can be called from any non-terminal state.
     */
    public void shutdown() {
        LifecycleState current = state.get();
        if (current == LifecycleState.STOPPED || current == LifecycleState.FAILED) {
            return;
        }
        LifecycleState prev = current;
        if (current != LifecycleState.STOPPING) {
            // Transition to STOPPING from any active state
            if (state.compareAndSet(current, LifecycleState.STOPPING)) {
                prev = current;
            } else {
                prev = state.get();
            }
        }
        notifyListeners(prev, LifecycleState.STOPPING);

        try {
            // Stop children first
            stopChildren();

            // Revoke all capabilities
            revokeCapabilities();

            // Release resources
            releaseResources();

            state.set(LifecycleState.STOPPED);
        } catch (Exception e) {
            state.set(LifecycleState.FAILED);
            throw new ScopeShutdownException("Failed to shutdown scope: " + id, e);
        } finally {
            closed = true;
            notifyListeners(state.get(), LifecycleState.STOPPED);
        }
    }

    private void stopChildren() {
        for (Scope child : childScopes.values()) {
            try {
                child.shutdown();
            } catch (Exception e) {
                // Continue cleanup even if child fails
                child.state.set(LifecycleState.FAILED);
            }
        }
        childScopes.clear();
    }

    private void revokeCapabilities() {
        for (CapabilityToken<?> token : capabilities.values()) {
            token.revoke();
        }
        capabilities.clear();
    }

    private void releaseResources() {
        List<Exception> errors = new ArrayList<>();
        // Close in reverse order
        List<Resource> reversed = new ArrayList<>(ownedResources);
        Collections.reverse(reversed);
        for (Resource res : reversed) {
            try {
                res.close();
            } catch (Exception e) {
                errors.add(e);
            }
        }
        ownedResources.clear();
        if (!errors.isEmpty()) {
            ScopeShutdownException ex = new ScopeShutdownException("Resource cleanup errors in scope: " + id);
            errors.forEach(ex::addSuppressed);
            throw ex;
        }
    }

    private void notifyListeners(LifecycleState from, LifecycleState to) {
        for (ScopeListener listener : listeners) {
            try {
                listener.onStateChange(this, from, to);
            } catch (Exception e) {
                // Listeners must not break lifecycle
            }
        }
    }

    private void checkNotStopped() {
        if (isStopped()) {
            throw new IllegalStateException("Scope is stopped: " + id);
        }
    }

    @Override
    public String toString() {
        return "Scope[" + id + ", state=" + state.get() + "]";
    }
}
