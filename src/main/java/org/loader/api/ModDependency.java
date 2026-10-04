package org.loader.api;

/**
 * A simplified dependency declaration for a mod.
 */
public interface ModDependency {

    /**
     * The unique identifier of the depended-on mod.
     */
    String modId();

    /**
     * The version range specification (semantic versioning range, e.g. "[1.0,2.0)").
     * Returns an empty string if no version constraint is specified.
     */
    String versionRange();

    /**
     * Returns {@code true} if this dependency is required for the mod to load.
     * Optional or "soft" dependencies return {@code false}.
     */
    boolean required();
}
