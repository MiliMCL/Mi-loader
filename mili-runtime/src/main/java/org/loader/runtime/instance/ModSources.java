package org.loader.runtime.instance;

import org.loader.runtime.mod.ModDiscoverer;
import org.loader.runtime.mod.ModManifest;
import org.loader.runtime.mod.ModManifest.VersionBinding;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Built-in implementations of {@link ModDiscoverer.ModSource} for common use cases.
 */
public final class ModSources {

    private ModSources() {
        // Utility class
    }

    /**
     * A mod source that scans a directory for {@code .json} mod manifest files.
     * <p>
     * Each file should be a simple JSON representation of a ModManifest:
     * <pre>
     * {
     *   "id": "example-mod",
     *   "name": "Example Mod",
     *   "version": "1.0.0"
     * }
     * </pre>
     * The parser accepts a simplified format where each line in the file is treated as an individual mod.
     */
    public static ModDiscoverer.ModSource directory(Path directory) {
        return () -> {
            List<ModManifest> manifests = new ArrayList<>();
            if (!Files.isDirectory(directory)) {
                return manifests;
            }
            try (var paths = Files.list(directory)) {
                paths.filter(p -> p.toString().endsWith(".mod"))
                     .sorted()
                     .forEach(p -> {
                         try {
                             String content = Files.readString(p);
                             ModManifest result = parseSimpleFormat(p.getFileName().toString(), content);
                             if (result != null) {
                                 manifests.add(result);
                             }
                         } catch (IOException e) {
                             // Skip unreadable files
                         }
                     });
            } catch (IOException e) {
                // Directory not readable
            }
            return manifests;
        };
    }

    /**
     * A mod source that wraps a pre-built list of mod manifests.
     * Useful for testing or programmatic configuration.
     */
    public static ModDiscoverer.ModSource listOf(List<ModManifest> manifests) {
        List<ModManifest> snapshot = List.copyOf(manifests);
        return () -> snapshot;
    }

    /**
     * A mod source that combines multiple sources into one.
     * Duplicate IDs are deduplicated (first occurrence wins).
     */
    public static ModDiscoverer.ModSource composite(List<ModDiscoverer.ModSource> sources) {
        return () -> {
            Map<String, ModManifest> unique = new LinkedHashMap<>();
            for (ModDiscoverer.ModSource source : sources) {
                for (ModManifest manifest : source.scan()) {
                    unique.putIfAbsent(manifest.id(), manifest);
                }
            }
            return List.copyOf(unique.values());
        };
    }

    /**
     * Parses a simple text format where each non-empty line is either:
     * A single mod ID, or
     * A comma-separated triple: id,name,version
     */
    private static ModManifest parseSimpleFormat(String filename, String content) {
        String[] lines = content.split("\n");
        if (lines.length == 0) return null;

        // Use the first non-empty line
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(",");
            if (parts.length >= 3) {
                return new ModManifest(
                        parts[0].trim(),
                        parts[1].trim(),
                        parts[2].trim(),
                        "", "", List.of(), List.of(), "", List.of(),
                        VersionBinding.unbound()
                );
            } else if (parts.length == 1) {
                String id = parts[0].trim();
                return ModManifest.of(id, id, "1.0.0");
            }
        }
        return null;
    }
}
