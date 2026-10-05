package org.loader.api;

import org.loader.api.VersionInfo;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Primary entry point to the Mili public API.
 * <p>
 * Provides static accessors for runtime version information and
 * mod context discovery.
 *
 * <pre>{@code
 *   // Get the API version
 *   String version = Mili.apiVersion();
 *
 *   // Get the current mod context (from within a mod)
 *   ModContext ctx = Mili.mod(MyMod.class).orElseThrow();
 * }</pre>
 */
public final class Mili {

    private Mili() {
        throw new UnsupportedOperationException("Mili is a facade class and cannot be instantiated.");
    }

    /**
     * Returns the Mili ABI version as an integer (the public contract mods bind against).
     * <p>
     * Mili uses integer-based ABI版本 (1, 2, 3, ...)。Platform version 使用语义化 ("0.1.0")。
     *
     * @return the current ABI version (e.g. 1)
     */
    public static int apiVersion() {
        return VersionInfo.ABI_VERSION;
    }

    /**
     * Returns the platform semantic version string.
     *
     * @return the platform version (e.g. "0.1.0")
     */
    public static String platformVersion() {
        return VersionInfo.CURRENT_VERSION;
    }

    /**
     * Returns the underlying runtime implementation version.
     * This may differ from the platform version during rapid development.
     *
     * @return the runtime version string (e.g. "0.1.0-SNAPSHOT")
     * @deprecated Use {@link #platformVersion()} instead.
     */
    @Deprecated
    public static String runtimeVersion() {
        return VersionInfo.CURRENT_VERSION;
    }

    /**
     * Looks up the {@link ModContext} for a given mod instance.
     * <p>
     * The actual resolution is delegated to a {@link MiliProvider}
     * that is registered by the loader during startup.
     *
     * @param modInstance the mod instance (must implement {@link Mod})
     * @return an {@link Optional} containing the mod's context if available
     */
    public static Optional<ModContext> mod(Object modInstance) {
        if (modInstance == null) {
            return Optional.empty();
        }
        MiliProvider provider = Holder.getProvider();
        if (provider == null) {
            return Optional.empty();
        }
        return provider.findMod(modInstance);
    }

    /**
     * Looks up the {@link ModContext} for a mod by its unique identifier.
     *
     * @param modId the mod's unique identifier
     * @return an {@link Optional} containing the mod's context if available
     */
    public static Optional<ModContext> mod(String modId) {
        if (modId == null || modId.isBlank()) {
            return Optional.empty();
        }
        MiliProvider provider = Holder.getProvider();
        if (provider == null) {
            return Optional.empty();
        }
        return provider.findMod(modId);
    }

    /**
     * Registers a {@link MiliProvider} for mod context resolution.
     * <p>
     * This is called by the loader during startup and should not be
     * called by mods.
     *
     * @param provider the provider implementation
     */
    public static void registerProvider(MiliProvider provider) {
        Holder.setProvider(provider);
    }

    /**
     * Service provider interface used by the loader to bridge to the runtime.
     * <p>
     * This interface is implemented by the loader and is not intended
     * for mod authors to implement.
     */
    public interface MiliProvider {
        Optional<ModContext> findMod(Object modInstance);
        Optional<ModContext> findMod(String modId);
    }

    /**
     * Internal holder for the provider reference.
     */
    private static final class Holder {
        private static volatile MiliProvider provider;

        static MiliProvider getProvider() {
            return provider;
        }

        static void setProvider(MiliProvider p) {
            provider = p;
        }
    }
}
