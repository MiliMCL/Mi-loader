package org.loader.runtime.util;

/**
 * Version and release metadata.
 * <p>
 * Per DEFINITION_OF_DONE.md: "Must generate version, changelog/release notes, reproducible build metadata."
 */
public record VersionInfo(
        String project,
        String version,
        String abiVersion,
        String buildTimestamp,
        String targetJava,
        String description
) {
    public static final String CURRENT_VERSION = "0.1.0-SNAPSHOT";
    public static final String ABI_VERSION = "1.0";
    public static final String TARGET_JAVA = "21";

    public static VersionInfo current() {
        return new VersionInfo(
                "minecraft-runtime",
                CURRENT_VERSION,
                ABI_VERSION,
                java.time.Instant.now().toString(),
                TARGET_JAVA,
                "A new Minecraft runtime architecture"
        );
    }

    @Override
    public String toString() {
        return project + " v" + version + " (ABI " + abiVersion + ", Java " + targetJava + ")";
    }
}
