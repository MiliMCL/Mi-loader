package org.loader.api.capability;

/**
 * Manages capability validation and granting.
 * <p>
 * Enforces the principle of least authority: grant only the smallest capability sufficient.
 * <p>
 * This interface is primarily for internal use by the runtime. Mods receive
 * capabilities through their {@link org.loader.api.ModContext} rather than
 * granting capabilities themselves.
 */
public interface CapabilityManager {

    /**
     * Registers a capability definition with a name, type, and validator.
     *
     * @param name      the capability name
     * @param type      the capability interface type
     * @param validator the validator/factory for the capability
     * @param <T>       the capability type
     */
    <T> void registerCapability(String name, Class<T> type, CapabilityValidator<T> validator);

    /**
     * Checks whether a capability can be granted for the given type.
     *
     * @param capabilityName the capability name
     * @param requestedType  the requested interface type
     * @return {@code true} if the capability can be granted
     */
    boolean canGrant(String capabilityName, Class<?> requestedType);

    /**
     * Returns whether this manager is closed.
     */
    boolean isClosed();

    /**
     * Validator and factory for capability implementations.
     *
     * @param <T> the capability type
     */
    @FunctionalInterface
    interface CapabilityValidator<T> {
        T createImplementation(Class<T> type);
    }
}
