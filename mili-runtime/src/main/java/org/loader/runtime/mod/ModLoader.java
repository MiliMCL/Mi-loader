package org.loader.runtime.mod;

import org.loader.runtime.kernel.*;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Loads Mods into the Runtime.
 */
public class ModLoader {

    private final org.loader.runtime.kernel.Runtime runtime;
    private final Map<String, Mod> loadedMods = new LinkedHashMap<>();

    public ModLoader(org.loader.runtime.kernel.Runtime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    /**
     * Loads a mod from its manifest.
     */
    public Mod load(ModManifest manifest, ClassLoader parentClassLoader) {
        if (loadedMods.containsKey(manifest.id())) {
            throw new IllegalStateException("Mod already loaded: " + manifest.id());
        }

        // Create mod scope as child of root
        Scope modScope = runtime.rootScope().createChild("mod:" + manifest.id());
        runtime.lifecycleManager().registerScope(modScope);

        // Create classloader
        ClassLoader modClassLoader;
        try {
            modClassLoader = createClassLoader(manifest, parentClassLoader);
        } catch (IOException e) {
            throw new ModLoadException("Failed to create classloader for mod: " + manifest.id(), e);
        }

        // Create Mod instance
        Mod mod = new Mod(manifest, modScope, modClassLoader);
        modScope.registerResource(mod);
        loadedMods.put(manifest.id(), mod);

        // Transition through lifecycle
        modScope.transitionTo(LifecycleState.RESOLVED);
        modScope.transitionTo(LifecycleState.LOADED);
        modScope.transitionTo(LifecycleState.INITIALIZED);
        modScope.transitionTo(LifecycleState.REGISTERED);
        modScope.transitionTo(LifecycleState.RUNNING);

        return mod;
    }

    /**
     * Unloads a mod.
     */
    public void unload(Mod mod) {
        loadedMods.remove(mod.id());
        mod.scope().shutdown();
        runtime.rootScope().removeChild(mod.scope().id());
    }

    /**
     * Returns all loaded mods.
     */
    public Collection<Mod> mods() {
        return Collections.unmodifiableCollection(loadedMods.values());
    }

    /**
     * Finds a loaded mod by ID.
     */
    public Optional<Mod> find(String modId) {
        return Optional.ofNullable(loadedMods.get(modId));
    }

    private ClassLoader createClassLoader(ModManifest manifest, ClassLoader parent) throws IOException {
        // In a real implementation, this would create a URLClassLoader from mod jars
        // For now, return the parent classloader
        return parent != null ? parent : getClass().getClassLoader();
    }
}
