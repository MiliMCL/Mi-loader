package org.loader.loader.discovery;

import org.loader.loader.config.LoaderConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Discovers the Minecraft JAR (client or server) and its libraries.
 *
 * <p>Three modes of operation:
 * <ol>
 *   <li><b>Fat client:</b> A JAR with {@code net/minecraft/client/main/Main.class}
 *       at root level (no bundler wrapping). This is the Mojang-signed client JAR.</li>
 *   <li><b>Mojang bundler:</b> A {@code server.jar} that wraps MC classes + embedded
 *       libraries in {@code META-INF/versions/...} and {@code META-INF/libraries/}</li>
 *   <li><b>Simple:</b> A {@code server.jar} with classes directly at root</li>
 * </ol>
 *
 * <p>The discoverer auto-detects which mode applies and builds the proper
 * classpath list so the GameProvider can load MC classes.
 */
public class MinecraftDiscovery {

    private final LoaderConfig config;
    private Path gameJar;
    private Path extractedServerJar; // non-null when extracted from bundler
    private final List<Path> libraries = new ArrayList<>();
    private boolean found = false;
    private String version;
    private Path bundlerTempDir;
    private GameType detectedType = GameType.UNKNOWN;

    public enum GameType {
        CLIENT,
        SERVER,
        UNKNOWN
    }

    public MinecraftDiscovery(LoaderConfig config) {
        this.config = config;
    }

    public static MinecraftDiscovery scan(LoaderConfig config) {
        MinecraftDiscovery discovery = new MinecraftDiscovery(config);
        discovery.scan();
        return discovery;
    }

    private void scan() {
        // 1. Check configured minecraft path
        if (Files.exists(config.getMinecraftPath())) {
            gameJar = config.getMinecraftPath();
            found = true;
        }

        // 2. Search for server.jar / client.jar in game dir
        if (!found) {
            Path defaultJar = config.getGameDir().resolve("server.jar");
            if (Files.exists(defaultJar)) {
                gameJar = defaultJar;
                found = true;
            }
        }
        if (!found) {
            Path clientJar = config.getGameDir().resolve("client.jar");
            if (Files.exists(clientJar)) {
                gameJar = clientJar;
                found = true;
            }
        }

        // 3. Search for any .jar that contains MC classes
        if (!found) {
            scanForMCJar();
        }

        if (!found) return;

        // 4. Determine game type (client vs server)
        detectGameType();

        // 5. Handle Mojang bundler nested JARs if applicable
        if (isBundlerFormat(gameJar)) {
            extractBundlerContents(gameJar);
        }

        // 6. Resolve external libraries
        resolveLibraries();
    }

    private void scanForMCJar() {
        try (Stream<Path> paths = Files.walk(config.getGameDir(), 3)) {
            paths.filter(p -> p.getFileName().toString().endsWith(".jar"))
                 .filter(p -> {
                     try {
                         return Files.size(p) > 500_000; // MC jar is large
                     } catch (IOException e) {
                         return false;
                     }
                 })
                 .filter(p -> containsAnyClass(p,
                         "net.minecraft.server.Main",
                         "net.minecraft.client.main.Main",
                         "net.minecraft.client.Main"))
                 .findFirst()
                 .ifPresent(p -> {
                     gameJar = p;
                     found = true;
                 });
        } catch (IOException e) {
            // ignore scan failure
        }
    }

    private void detectGameType() {
        if (containsClass(gameJar, "net.minecraft.client.main.Main") ||
            containsClass(gameJar, "net.minecraft.client.Main")) {
            detectedType = GameType.CLIENT;
        } else if (containsClass(gameJar, "net.minecraft.server.Main")) {
            detectedType = GameType.SERVER;
        }
    }

    /**
     * Resolves library JARs via LibraryResolver and adds default external lookup paths.
     */
    private void resolveLibraries() {
        LibraryResolver libResolver = new LibraryResolver(config.getGameDir());
        libraries.addAll(libResolver.resolve());

        // Also check if a bundler server.jar is available nearby to extract libraries
        Path bundlerJar = findBundlerWithLibraries();
        if (bundlerJar != null && isBundlerFormat(bundlerJar)) {
            extractLibrariesFromBundler(bundlerJar);
        }
    }

    /**
     * Finds a bundler server.jar (a JAR containing META-INF/libraries/) near the game dir.
     */
    private Path findBundlerWithLibraries() {
        // Search for any bundler jar in the game dir tree
        try (Stream<Path> paths = Files.walk(config.getGameDir(), 3)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(p -> !p.equals(gameJar)) // not the game jar itself
                        .filter(p -> isBundlerFormat(p))
                        .findFirst()
                        .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Extracts libraries from an external bundler JAR into a temp directory
     * and adds them to the classpath. Used when the game jar itself is NOT
     * a bundler (e.g. client fat-JAR) that still needs embedded libraries.
     */
    private void extractLibrariesFromBundler(Path bundlerJar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(bundlerJar.toFile())) {
            Path tempDir = Files.createTempDirectory("mili-client-libs-");
            tempDir.toFile().deleteOnExit();

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (entry.isDirectory()) continue;

                // Extract embedded library JARs
                if (name.startsWith("META-INF/libraries/") && name.endsWith(".jar")) {
                    String relativePath = name.substring("META-INF/libraries/".length());
                    Path libJar = tempDir.resolve(relativePath);
                    Files.createDirectories(libJar.getParent());
                    try (InputStream is = zip.getInputStream(entry)) {
                        Files.copy(is, libJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    libraries.add(libJar);
                    libJar.toFile().deleteOnExit();
                }
            }
        } catch (IOException e) {
            // ignore
        }
    }

    private static boolean hasBundlerVersions(Path jarPath) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            return zip.getEntry("META-INF/versions/") != null;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isBundlerFormat(Path jarPath) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            return zip.getEntry("META-INF/versions/") != null;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean containsClass(Path jarPath, String className) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            return zip.getEntry(className.replace('.', '/') + ".class") != null;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean containsAnyClass(Path jarPath, String... classNames) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            for (String cn : classNames) {
                if (zip.getEntry(cn.replace('.', '/') + ".class") != null) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private void extractBundlerContents(Path outerJar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(outerJar.toFile())) {
            bundlerTempDir = Files.createTempDirectory("mili-bundler-");
            bundlerTempDir.toFile().deleteOnExit();

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (entry.isDirectory()) continue;

                // Server JAR from versions/
                if (name.startsWith("META-INF/versions/") && name.contains("/server-") && name.endsWith(".jar")) {
                    String fileName = name.substring(name.lastIndexOf('/') + 1);
                    extractedServerJar = bundlerTempDir.resolve(fileName);
                    try (InputStream is = zip.getInputStream(entry)) {
                        Files.copy(is, extractedServerJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    extractedServerJar.toFile().deleteOnExit();
                }

                // Embedded library JARs
                if (name.startsWith("META-INF/libraries/") && name.endsWith(".jar")) {
                    String relativePath = name.substring("META-INF/libraries/".length());
                    Path libJar = bundlerTempDir.resolve(relativePath);
                    Files.createDirectories(libJar.getParent());
                    try (InputStream is = zip.getInputStream(entry)) {
                        Files.copy(is, libJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    libraries.add(libJar);
                    libJar.toFile().deleteOnExit();
                }
            }
        } catch (IOException e) {
            extractedServerJar = null;
        }
    }

    public boolean found() { return found; }
    public GameType getGameType() { return detectedType; }
    public Path getGameJar() { return extractedServerJar != null ? extractedServerJar : gameJar; }
    public List<Path> getLibraries() { return Collections.unmodifiableList(libraries); }

    public String getVersion() {
        return version != null ? version : "unknown";
    }

    /**
     * Builds a full classpath: [gameJar] + all resolved libraries.
     */
    public List<Path> getClasspath() {
        List<Path> cp = new ArrayList<>();
        Path jar = getGameJar();
        if (jar != null) cp.add(jar);
        cp.addAll(libraries);
        return cp;
    }
}
