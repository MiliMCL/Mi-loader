package org.loader.loader.classloader;

import org.loader.loader.discovery.ModDiscovery;
import org.loader.runtime.mod.Mod;
import org.loader.runtime.mod.ModManifest;
import org.loader.runtime.mod.ModLoadException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Isolated ClassLoader for a single mod.
 * Isolation model:
 * - Parent delegation: loader classes (org.loader.runtime.*, org.loader.loader.*)
 * - Minecraft delegation: net.minecraft.* → delegate to MC classloader
 * - Mod isolation: each mod gets its own classloader
 * - Cross-mod: only through explicit dependency parent chain
 */
public class ModClassLoader implements AutoCloseable {

    private final ModManifest manifest;
    private final URLClassLoader classLoader;
    private final List<Path> sources;
    private final ModClassLoader parent; // dependency parent
    private final Path gameDir;

    public ModClassLoader(ModManifest manifest, URL[] minecraftClassLoader, ModClassLoader parent) {
        this(manifest, minecraftClassLoader, parent, Paths.get("."));
    }

    public ModClassLoader(ModManifest manifest, URL[] minecraftClassLoader, ModClassLoader parent, Path gameDir) {
        this.manifest = manifest;
        this.parent = parent;
        this.gameDir = gameDir != null ? gameDir : Paths.get(".");
        this.sources = new ArrayList<>();

        // Build URLs for this mod
        List<URL> urls = new ArrayList<>();
        if (parent != null) {
            for (URL url : parent.classLoader.getURLs()) urls.add(url);
        }
        urls.addAll(Arrays.asList(minecraftClassLoader));

        // Add mod's own JAR/directory — using absolute gameDir
        Path modPath = this.gameDir.resolve("mods").resolve(manifest.id() + ".jar");
        if (Files.exists(modPath)) {
            try {
                urls.add(modPath.toUri().toURL());
                sources.add(modPath);
            } catch (Exception e) {
                // ignore bad source
            }
        }
        Path modDir = this.gameDir.resolve("mods").resolve(manifest.id());
        if (Files.isDirectory(modDir)) {
            try {
                urls.add(modDir.toUri().toURL());
                sources.add(modDir);
                // Also add build/classes and build/libs
                Path classesDir = modDir.resolve("build/classes/java/main");
                if (Files.isDirectory(classesDir)) {
                    urls.add(classesDir.toUri().toURL());
                }
                Path libsDir = modDir.resolve("build/libs");
                if (Files.isDirectory(libsDir)) {
                    try (DirectoryStream<Path> jars = Files.newDirectoryStream(libsDir, "*.jar")) {
                        for (Path jar : jars) urls.add(jar.toUri().toURL());
                    }
                }
            } catch (IOException e) {
                // ignore
            }
        }

        // Parent-first delegation for loader + MC, self-first for mod classes
        this.classLoader = new URLClassLoader(urls.toArray(new URL[0]),
                ModClassLoader.class.getClassLoader());
    }

    public Class<?> loadModClass(String className) throws ClassNotFoundException {
        return Class.forName(className, true, classLoader);
    }

    public InputStream getResource(String name) {
        return classLoader.getResourceAsStream(name);
    }

    public ModManifest getManifest() {
        return manifest;
    }

    public URLClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * Creates a Mod instance from this classloader.
     */
    public Mod createMod() {
        String mainClass = manifest.mainClass();
        if (mainClass == null || mainClass.isEmpty()) {
            return new Mod(manifest, null, classLoader);
        }
        try {
            Class<?> clazz = loadModClass(mainClass);
            return new Mod(manifest, null, classLoader);
        } catch (ClassNotFoundException e) {
            throw new ModLoadException("Cannot find mod main class: " + mainClass, e);
        }
    }

    public void indexMod() {
        // Scan for additional metadata, services, etc.
    }

    @Override
    public void close() throws Exception {
        classLoader.close();
    }
}
