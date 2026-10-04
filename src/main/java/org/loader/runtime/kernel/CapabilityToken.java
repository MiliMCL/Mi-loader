package org.loader.runtime.kernel;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a granted capability.
 * A capability is both an API boundary and an authorization boundary.
 *
 * @param <T> the capability interface type
 */
public sealed interface CapabilityToken<T> permits CapabilityTokenImpl {

    /**
     * The capability type class.
     */
    Class<T> capabilityType();

    /**
     * The scope this capability belongs to.
     */
    Scope scope();

    /**
     * Returns the capability implementation.
     */
    T get();

    /**
     * Revokes this capability.
     */
    void revoke();

    /**
     * Returns whether this capability is still active.
     */
    boolean isActive();
}

record CapabilityTokenImpl<T>(
        Class<T> capabilityType,
        Scope scope,
        T implementation,
        AtomicBoolean active
) implements CapabilityToken<T> {

    @Override
    public T get() {
        return implementation;
    }

    @Override
    public void revoke() {
        active.set(false);
    }

    @Override
    public boolean isActive() {
        return active.get() && !scope().isStopped();
    }
}
