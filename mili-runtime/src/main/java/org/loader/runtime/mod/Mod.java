package org.loader.runtime.mod;

import org.loader.runtime.kernel.*;

import java.util.*;

/**
 * Represents a loaded Mod instance.
 * <p>
 * A Mod is a managed execution unit with its own Scope.
 */
public class Mod implements Resource {

    private final ModManifest manifest;
    private final Scope modScope;
    private final ClassLoader classLoader;

    public Mod(ModManifest manifest, Scope modScope, ClassLoader classLoader) {
        this.manifest = Objects.requireNonNull(manifest);
        this.modScope = Objects.requireNonNull(modScope);
        this.classLoader = classLoader != null ? classLoader : getClass().getClassLoader();
    }

    @Override
    public String id() {
        return manifest.id();
    }

    @Override
    public Scope owner() {
        return modScope.owner();
    }

    public ModManifest manifest() {
        return manifest;
    }

    public Scope scope() {
        return modScope;
    }

    public ClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public boolean isClosed() {
        return modScope.isStopped();
    }

    @Override
    public void close() {
        // Note: Do NOT call modScope.shutdown() here. The Scope owns this Mod
        // as a resource; shutting down the scope iterates resources and calls
        // close() on each. Calling shutdown from here would be infinite recursion.
        // Mod specific cleanup (e.g., releasing classloader resources) goes here.
    }

    /**
     * Returns whether this mod is a large mod with multiple modules.
     */
    public boolean isLargeMod() {
        return !manifest.modules().isEmpty();
    }

    /**
     * Returns child modules as scopes.
     */
    public List<Scope> modules() {
        return manifest.modules().stream()
                .map(name -> modScope.createChild(id() + ":" + name))
                .toList();
    }

    @Override
    public String toString() {
        return "Mod[" + id() + " v" + manifest.version() + ", state=" + modScope.state() + "]";
    }
}
