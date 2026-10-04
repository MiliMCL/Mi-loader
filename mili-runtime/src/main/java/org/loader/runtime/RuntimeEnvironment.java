package org.loader.runtime;

/**
 * Environment type for a running game instance.
 * <p>
 * Whether we are on the client, an integrated server, or a dedicated server
 * is a pure runtime concern — not specific to Minecraft. Therefore this type
 * lives in the runtime module, not the minecraft-integration module.
 */
public enum RuntimeEnvironment {
    SERVER,
    CLIENT,
    DEDICATED_SERVER;

    public boolean isClient() {
        return this == CLIENT;
    }

    public boolean isServer() {
        return this == SERVER || this == DEDICATED_SERVER;
    }
}
