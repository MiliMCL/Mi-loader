package org.loader.api.capability;

/**
 * Represents a granted capability.
 * <p>
 * A capability is both an API boundary and an authorization boundary.
 * When revoked, the capability can no longer be used.
 *
 * @param <T> the capability interface type
 */
public interface CapabilityToken<T> {

    /**
     * Returns the capability type class.
     */
    Class<T> capabilityType();

    /**
     * Returns the capability implementation.
     * @throws IllegalStateException if the capability has been revoked
     */
    T get();

    /**
     * Returns whether this capability is still active (not revoked).
     */
    boolean isActive();
}
