package org.loader.runtime.mod;

import org.loader.runtime.kernel.Scope;
import org.loader.runtime.kernel.LifecycleState;
import org.loader.runtime.kernel.Runtime;

import java.util.*;

/**
 * Discovers Mods from various sources.
 */
public class ModDiscoverer {

    private final Runtime runtime;
    private final List<ModSource> sources = new ArrayList<>();

    public ModDiscoverer(Runtime runtime) {
        this.runtime = runtime;
    }

    /**
     * Adds a mod source.
     */
    public void addSource(ModSource source) {
        sources.add(source);
    }

    /**
     * Discovers all available mod manifests.
     */
    public List<ModManifest> discover() {
        List<ModManifest> manifests = new ArrayList<>();
        for (ModSource source : sources) {
            manifests.addAll(source.scan());
        }
        return Collections.unmodifiableList(manifests);
    }

    /**
     * Source of mod manifests.
     */
    @FunctionalInterface
    public interface ModSource {
        List<ModManifest> scan();
    }
}
