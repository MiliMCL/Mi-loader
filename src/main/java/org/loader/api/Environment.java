package org.loader.api;

/**
 * Environment type for the Minecraft runtime.
 * <p>
 * Delegates to the underlying {@code RuntimeEnvironment} concept from the runtime layer.
 */
public enum Environment {

    SERVER,
    CLIENT,
    DEDICATED_SERVER;

    /**
     * Returns {@code true} if this environment is the integrated client.
     */
    public boolean isClient() {
        return this == CLIENT;
    }

    /**
     * Returns {@code true} if this is any server environment (dedicated or integrated).
     */
    public boolean isServer() {
        return this == SERVER || this == DEDICATED_SERVER;
    }
}
