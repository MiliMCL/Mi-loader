package org.loader.api;

/**
 * Simplified mod entrypoint interface.
 * <p>
 * Mods implement this interface to participate in the loader lifecycle.
 * The loader discovers implementations of this class and invokes
 * {@link #onInitialize(ModContext)} during mod startup.
 */
public interface Mod {

    /**
     * Called when the mod is being initialized.
     * <p>
     * This is the primary entrypoint for mod code. The provided context
     * gives access to the scheduler, event bus, resources, lifecycle, and
     * other runtime capabilities.
     *
     * @param context the mod's context for interacting with the runtime
     */
    void onInitialize(ModContext context);
}
