package org.loader.runtime.mod;

import org.loader.runtime.error.ModLoadError;
import org.loader.runtime.error.ErrorContext;

/**
 * Legacy mod loading exception.
 * Now extends the typed {@link ModLoadError} for backward compatibility.
 *
 * @deprecated Use {@link ModLoadError} directly for new code
 */
@Deprecated
public class ModLoadException extends ModLoadError {

    public ModLoadException(String message) {
        super(message);
    }

    public ModLoadException(String message, ErrorContext context) {
        super(message, context);
    }

    public ModLoadException(String message, Throwable cause) {
        super(message, cause);
    }

    public ModLoadException(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
