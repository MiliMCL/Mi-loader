package org.loader.runtime.util;

import org.loader.api.VersionInfo;

/**
 * Runtime diagnostics record for platform version information.
 * <p>
 * Delegates the canonical constants to {@link VersionInfo} in the abi module.
 */
public record RuntimeVersionInfo(
        String project,
        String version,
        String abiVersion,
        String buildTimestamp,
        String targetJava,
        String description
) {
    public static RuntimeVersionInfo current() {
        return new RuntimeVersionInfo(
                "mili-platform",
                VersionInfo.CURRENT_VERSION,
                VersionInfo.ABI_VERSION,
                java.time.Instant.now().toString(),
                VersionInfo.TARGET_JAVA,
                "Mili Platform — a from-scratch Fabric-like mod loader"
        );
    }

    @Override
    public String toString() {
        return project + " v" + version + " (ABI " + abiVersion + ", Java " + targetJava + ")";
    }
}
