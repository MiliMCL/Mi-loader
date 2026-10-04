package org.loader.loader.classloader;

import org.loader.runtime.mod.ModManifest;

import java.net.URL;
import java.util.*;

/**
 * Manages all ModClassLoaders.
 * Ensures proper isolation and cleanup.
 */
public class ModClassLoaderManager implements AutoCloseable {

    private final Map<String, ModClassLoader> classLoaders = new LinkedHashMap<>();
    private final List<ModClassLoader> order = new ArrayList<>();

    public ModClassLoader createModClassLoader(ModManifest manifest, List<?> minecraftClasspath) {
        URL[] urls = new URL[minecraftClasspath.size()];
        for (int i = 0; i < minecraftClasspath.size(); i++) {
            try {
                Object entry = minecraftClasspath.get(i);
                if (entry instanceof URL) {
                    urls[i] = (URL) entry;
                } else {
                    urls[i] = ((java.nio.file.Path) entry).toUri().toURL();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return createModClassLoaderManifest(manifest, java.util.Arrays.asList(urls));
    }

    /**
     * Creates a Mod ClassLoader from an explicit URL array.
     */
    public ModClassLoader createModClassLoaderManifest(ModManifest manifest, List<URL> urls) {
        return createModClassLoaderManifest(manifest, urls, java.nio.file.Paths.get("."));
    }

    /**
     * Creates a Mod ClassLoader with an explicit game directory for locating mod JARs.
     */
    public ModClassLoader createModClassLoaderManifest(ModManifest manifest, List<URL> urls, java.nio.file.Path gameDir) {
        URL[] urlArray = urls.toArray(new URL[0]);
        ModClassLoader mcl = new ModClassLoader(manifest, urlArray, null, gameDir);
        classLoaders.put(manifest.id(), mcl);
        order.add(mcl);
        return mcl;
    }

    public ModClassLoader getModClassLoader(String modId) {
        return classLoaders.get(modId);
    }

    public Collection<ModClassLoader> getAll() {
        return Collections.unmodifiableCollection(order);
    }

    public boolean hasMod(String modId) {
        return classLoaders.containsKey(modId);
    }

    @Override
    public void close() throws Exception {
        // Close in reverse order (LIFO)
        List<ModClassLoader> reverse = new ArrayList<>(order);
        Collections.reverse(reverse);
        for (ModClassLoader mcl : reverse) {
            mcl.close();
        }
        classLoaders.clear();
        order.clear();
    }
}
