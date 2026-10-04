package org.loader.api;

/**
 * Mili platform version constants.
 * <p>
 * These are the canonical version strings that mods and tools rely on.
 * Defined in the ABI module so they are available without pulling in the runtime.
 */
public final class VersionInfo {

    private VersionInfo() {
    }

    /** Current platform/runtime implementation version. */
    public static final String CURRENT_VERSION = "0.1.0";

    /** ABI version — mods are compiled and validated against this. */
    public static final String ABI_VERSION = "1.0";

    /** Target Java major version. */
    public static final String TARGET_JAVA = "25";

    /** Target Minecraft version supported by this platform release. */
    public static final String TARGET_MINECRAFT = "26.2";
}
