package org.loader.api;

import java.util.List;

/**
 * Simplified, immutable view of a mod's metadata.
 * <p>
 * Delegates to the underlying runtime {@code ModManifest} at construction time.
 */
public interface ModMetadata {

    /**
     * Returns the unique mod identifier (e.g. "mymod").
     */
    String id();

    /**
     * Returns the human-readable mod name.
     */
    String name();

    /**
     * Returns the mod version string (e.g. "1.0.0").
     */
    String version();

    /**
     * Returns the author name or an empty string if unspecified.
     */
    String author();

    /**
     * Returns the description or an empty string if unspecified.
     */
    String description();

    /**
     * Returns the list of declared dependencies.
     */
    List<? extends ModDependency> dependencies();

    /**
     * Returns the fully-qualified main class name, or empty string if unspecified.
     */
    String mainClass();
}
