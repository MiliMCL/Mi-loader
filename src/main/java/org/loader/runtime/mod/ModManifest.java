package org.loader.runtime.mod;

import java.util.*;

/**
 * Mod manifest describing identity, dependencies, capabilities, and entry points.
 * <p>
 * A Mod is conceptually: Manifest + Scope + Modules + Capabilities + Tasks + Resources + Lifecycle
 */
public record ModManifest(
        String id,
        String name,
        String version,
        String author,
        String description,
        List<DependencyEntry> dependencies,
        List<String> capabilities,
        String mainClass,
        List<String> modules
) {
    public ModManifest {
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        capabilities = capabilities != null ? List.copyOf(capabilities) : List.of();
        modules = modules != null ? List.copyOf(modules) : List.of();
    }

    /**
     * Creates a minimal manifest with required fields only.
     */
    public static ModManifest of(String id, String name, String version) {
        return new ModManifest(id, name, version, "", "", List.of(), List.of(), "", List.of());
    }

    /**
     * Dependency entry.
     */
    public record DependencyEntry(
            String modId,
            String versionRange,
            boolean required
    ) {
    }
}
