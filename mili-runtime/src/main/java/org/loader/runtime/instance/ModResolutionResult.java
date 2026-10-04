package org.loader.runtime.instance;

import org.loader.runtime.mod.ModManifest;

import java.util.*;

/**
 * Result of mod dependency resolution.
 * <p>
 * Contains the ordered list of mods ready for loading, or information about
 * what went wrong (missing dependencies, version conflicts).
 */
public class ModResolutionResult {

    private final String instanceId;
    private final List<ModManifest> resolvedMods;
    private final List<String> missingDependencies;
    private final List<String> conflicts;
    private final boolean success;

    private ModResolutionResult(String instanceId,
                                List<ModManifest> resolvedMods,
                                List<String> missingDependencies,
                                List<String> conflicts,
                                boolean success) {
        this.instanceId = instanceId;
        this.resolvedMods = List.copyOf(resolvedMods);
        this.missingDependencies = List.copyOf(missingDependencies);
        this.conflicts = List.copyOf(conflicts);
        this.success = success;
    }

    /**
     * Creates a successful resolution result.
     */
    public static ModResolutionResult success(String instanceId, List<ModManifest> resolvedMods) {
        return new ModResolutionResult(instanceId, resolvedMods, List.of(), List.of(), true);
    }

    /**
     * Creates a failed resolution result.
     */
    public static ModResolutionResult failed(String instanceId,
                                             List<String> missingDependencies,
                                             List<String> conflicts) {
        return new ModResolutionResult(instanceId, List.of(), missingDependencies, conflicts, false);
    }

    /**
     * Whether resolution succeeded.
     */
    public boolean success() {
        return success;
    }

    /**
     * Returns the instance ID.
     */
    public String instanceId() {
        return instanceId;
    }

    /**
     * Returns the resolved mods in load order.
     */
    public List<ModManifest> resolvedMods() {
        return resolvedMods;
    }

    /**
     * Returns missing dependency descriptions (e.g. "modA requires modB").
     */
    public List<String> missingDependencies() {
        return missingDependencies;
    }

    /**
     * Returns conflict descriptions (e.g. "modA duplicates modB").
     */
    public List<String> conflicts() {
        return conflicts;
    }

    /**
     * Returns a human-readable summary.
     */
    public String summary() {
        if (success) {
            return "Resolved " + resolvedMods.size() + " mods for instance " + instanceId;
        }
        StringBuilder sb = new StringBuilder("Resolution failed for instance ").append(instanceId);
        if (!missingDependencies.isEmpty()) {
            sb.append("; missing: ").append(String.join(", ", missingDependencies));
        }
        if (!conflicts.isEmpty()) {
            sb.append("; conflicts: ").append(String.join(", ", conflicts));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ModResolutionResult[" + instanceId + ", " + (success ? "OK" : "FAILED") + "]";
    }
}
