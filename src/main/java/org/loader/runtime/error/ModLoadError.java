package org.loader.runtime.error;

/**
 * Errors related to mod loading, unloading, or initialization.
 * Replaces the raw {@link org.loader.runtime.mod.ModLoadException} with
 * a typed, contextual error per ERROR_MODEL.md.
 */
public class ModLoadError extends ModularRuntimeException {

    public ModLoadError(String message) {
        super(message);
    }

    public ModLoadError(String message, ErrorContext context) {
        super(message, context, context != null ? context.cause() : null);
    }

    public ModLoadError(String message, Throwable cause) {
        super(message, cause);
    }

    public ModLoadError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
