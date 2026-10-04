package org.loader.runtime.minecraft;

/**
 * Environment type for Minecraft runtime.
 * <p>
 * Per DEFINITION_OF_DONE.md: "If the project positioning supports both client and server, both environments must be verified. Unsupported capabilities must be explicitly denied."
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
