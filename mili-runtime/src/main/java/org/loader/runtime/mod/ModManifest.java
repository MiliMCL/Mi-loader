package org.loader.runtime.mod;

import org.loader.api.VersionInfo;

import java.util.*;

/**
 * Mod manifest describing identity, dependencies, capabilities, and entry points.
 * <p>
 * A Mod is conceptually: Manifest + Scope + Modules + Capabilities + Tasks + Resources + Lifecycle.
 * <p>
 * The manifest now carries a platform version triple (platform + abi + minecraft) so the
 * loader can reject mods compiled against mismatched platforms at load time.
 */
public record ModManifest(
        String id,
        String name,
        String version,
        String author,
        String description,
        List<DependencyEntry> dependencies,
        List<String> capabilities,
        String mainClass,
        List<String> modules,
        VersionBinding versionBinding
) {
    public ModManifest {
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        capabilities = capabilities != null ? List.copyOf(capabilities) : List.of();
        modules = modules != null ? List.copyOf(modules) : List.of();
        versionBinding = versionBinding != null ? versionBinding : VersionBinding.unbound();
    }

    /**
     * Creates a minimal manifest with required fields only (legacy/no binding).
     */
    public static ModManifest of(String id, String name, String version) {
        return new ModManifest(id, name, version, "", "", List.of(), List.of(), "", List.of(), VersionBinding.unbound());
    }

    /**
     * Creates a manifest with explicit platform version binding.
     */
    public static ModManifest bound(String id, String name, String version,
                                     String platform, String abi, String minecraft) {
        return new ModManifest(id, name, version, "", "", List.of(), List.of(), "", List.of(),
                new VersionBinding(platform, abi, minecraft));
    }

    /**
     * Returns true if this mod carries a platform version binding.
     */
    public boolean isVersionBound() {
        return versionBinding.platform != null && !versionBinding.platform.isBlank();
    }

    /**
     * Validates that this mod's version binding matches the running platform exactly.
     *
     * @return a ValidationResult indicating success or a specific mismatch
     */
    public ValidationResult validateVersionBinding() {
        if (!isVersionBound()) {
            return ValidationResult.OK;
        }
        if (!versionBinding.platform.equals(VersionInfo.CURRENT_VERSION)) {
            return ValidationResult.platformMismatch(versionBinding.platform, VersionInfo.CURRENT_VERSION);
        }
        if (!versionBinding.abi.equals(VersionInfo.ABI_VERSION)) {
            return ValidationResult.abiMismatch(versionBinding.abi, VersionInfo.ABI_VERSION);
        }
        if (!versionBinding.minecraft.equals(VersionInfo.TARGET_MINECRAFT)) {
            return ValidationResult.minecraftMismatch(versionBinding.minecraft, VersionInfo.TARGET_MINECRAFT);
        }
        return ValidationResult.OK;
    }

    /**
     * Dependency entry.
     */
    public record DependencyEntry(
            String modId,
            String versionRange,
            boolean required
    ) {
    }

    /**
     * Platform version binding — the triple (platform, abi, minecraft) that this mod was built against.
     */
    public record VersionBinding(
            String platform,
            String abi,
            String minecraft
    ) {
        public static VersionBinding unbound() {
            return new VersionBinding("", "", "");
        }
    }

    /**
     * Result of a version-binding validation.
     */
    public sealed interface ValidationResult {
        ValidationResult OK = new Ok();

        record Ok() implements ValidationResult {}

        record PlatformMismatch(String expected, String actual) implements ValidationResult {}
        record AbiMismatch(String expected, String actual) implements ValidationResult {}
        record MinecraftMismatch(String expected, String actual) implements ValidationResult {}

        static ValidationResult platformMismatch(String expected, String actual) {
            return new PlatformMismatch(expected, actual);
        }

        static ValidationResult abiMismatch(String expected, String actual) {
            return new AbiMismatch(expected, actual);
        }

        static ValidationResult minecraftMismatch(String expected, String actual) {
            return new MinecraftMismatch(expected, actual);
        }

        default boolean isOk() {
            return this instanceof Ok;
        }
    }
}
