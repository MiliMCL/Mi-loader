package org.loader.api.permission;

/**
 * Standard permissions that may be granted to mods.
 * <p>
 * This enum defines the built-in permissions. Custom permissions can be
 * registered by the runtime as string-based permissions.
 */
public enum Permission {

    /**
     * Permission to read mods' own resource files.
     */
    RESOURCE_READ("resource.read"),

    /**
     * Permission to write to mods' own config/data directories.
     */
    RESOURCE_WRITE("resource.write"),

    /**
     * Permission to access the network (outbound connections).
     */
    NETWORK_ACCESS("network.access"),

    /**
     * Permission to execute native code or access native libraries.
     */
    NATIVE_ACCESS("native.access"),

    /**
     * Permission to register custom capabilities.
     */
    CAPABILITY_REGISTER("capability.register"),

    /**
     * Permission to access inter-mod communication channels.
     */
    INTERMOD_COMMUNICATION("intermod.communication");

    private final String id;

    Permission(String id) {
        this.id = id;
    }

    /**
     * Returns the string identifier for this permission.
     */
    public String id() {
        return id;
    }
}
