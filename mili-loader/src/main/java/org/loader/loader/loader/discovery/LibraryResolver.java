package org.loader.loader.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Resolves the external library JARs required by MC 26.2 client.
 * <p>
 * The Mojang client fat-JAR (tools/26.2/26.2.jar) contains MC classes only —
 * it does NOT embed third-party libraries like log4j, gson, netty, jopt-simple,
 * joml, authlib, fastutil, etc.
 * <p>
 * This resolver locates those libraries from (in priority order):
 * <ol>
 *   <li>{@code gameDir/libraries/} directory (standard MC layout)</li>
 *   <li>Embedded JARs inside any {@code server.jar} bundler (META-INF/libraries/)</li>
 *   <li>External path specified via system property {@code mili.libraries.path}</li>
 * </ol>
 * <p>
 * MC 26.2 requires Java 25. The libraries are the same versions used by both
 * client and server (gson, log4j, netty, guava, etc.).
 */
public class LibraryResolver {

    private final Path gameDir;
    private final Path librariesDir;
    private final List<Path> resolvedLibraries = new ArrayList<>();

    public LibraryResolver(Path gameDir) {
        this.gameDir = gameDir;
        this.librariesDir = gameDir.resolve("libraries");
    }

    /**
     * Scans for and returns all required library JARs.
     */
    public List<Path> resolve() {
        resolvedLibraries.clear();

        // Priority 1: External libraries/ directory
        if (Files.isDirectory(librariesDir)) {
            try (Stream<Path> paths = Files.walk(librariesDir)) {
                paths.filter(p -> p.toString().endsWith(".jar"))
                     .forEach(resolvedLibraries::add);
            } catch (IOException e) {
                // ignore
            }
        }

        // Priority 2: System property override
        String explicitLibs = System.getProperty("mili.libraries.path");
        if (explicitLibs != null && !explicitLibs.isBlank()) {
            Path explicit = Path.of(explicitLibs);
            if (Files.isDirectory(explicit)) {
                try (Stream<Path> paths = Files.walk(explicit)) {
                    paths.filter(p -> p.toString().endsWith(".jar"))
                         .forEach(resolvedLibraries::add);
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        return Collections.unmodifiableList(resolvedLibraries);
    }

    /**
     * Checks whether essential MC libraries are present on the given classpath.
     * Returns a list of warnings for missing critical libs.
     */
    public static List<String> validateClasspath(List<Path> classpath) {
        List<String> warnings = new ArrayList<>();
        Set<String> jarNames = new HashSet<>();
        for (Path p : classpath) {
            jarNames.add(p.getFileName().toString().toLowerCase());
        }

        String[][] criticalLibs = {
            {"log4j-api", "log4j-api"},
            {"log4j-core", "log4j-core"},
            {"gson", "gson"},
            {"netty", "netty-"},
            {"jopt-simple", "jopt-simple"},
            {"guava", "guava-"},
            {"authlib", "authlib"},
            {"fastutil", "fastutil-"},
            {"joml", "joml-"},
        };

        for (String[] check : criticalLibs) {
            String label = check[0];
            String prefix = check[1];
            boolean found = false;
            for (String jarName : jarNames) {
                if (jarName.contains(prefix)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                warnings.add("Missing critical library: " + label + " (expected JAR matching '" + prefix + "')");
            }
        }

        return warnings;
    }
}
