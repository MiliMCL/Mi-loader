package org.loader.runtime.instance;

import org.loader.runtime.mod.ModManifest;

import java.util.*;

/**
 * Represents a resolved set of mods for an instance.
 * <p>
 * A ModSet contains the ordered list of mod manifests that have been resolved
 * through dependency analysis, ready for loading.
 */
public class ModSet {

    private final String instanceId;
    private final List<ModManifest> resolvedMods;
    private final Map<String, ModManifest> modIndex;

    public ModSet(String instanceId, List<ModManifest> resolvedMods) {
        this.instanceId = Objects.requireNonNull(instanceId);
        this.resolvedMods = List.copyOf(resolvedMods);
        this.modIndex = new LinkedHashMap<>();
        for (ModManifest mod : resolvedMods) {
            this.modIndex.put(mod.id(), mod);
        }
    }

    /**
     * Returns the instance this mod set belongs to.
     */
    public String instanceId() {
        return instanceId;
    }

    /**
     * Returns all mods in load order.
     */
    public List<ModManifest> mods() {
        return resolvedMods;
    }

    /**
     * Returns the number of mods.
     */
    public int size() {
        return resolvedMods.size();
    }

    /**
     * Returns whether this mod set is empty.
     */
    public boolean isEmpty() {
        return resolvedMods.isEmpty();
    }

    /**
     * Finds a mod by ID.
     */
    public Optional<ModManifest> findById(String modId) {
        return Optional.ofNullable(modIndex.get(modId));
    }

    /**
     * Returns whether the set contains a specific mod.
     */
    public boolean contains(String modId) {
        return modIndex.containsKey(modId);
    }

    /**
     * Returns all mod IDs.
     */
    public List<String> modIds() {
        List<String> ids = new ArrayList<>(resolvedMods.size());
        for (ModManifest mod : resolvedMods) {
            ids.add(mod.id());
        }
        return ids;
    }

    @Override
    public String toString() {
        return "ModSet[instance=" + instanceId + ", mods=" + resolvedMods.size() + "]";
    }
}

