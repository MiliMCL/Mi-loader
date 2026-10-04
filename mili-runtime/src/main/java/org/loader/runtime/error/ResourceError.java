package org.loader.runtime.error;

/**
 * Errors related to resource registration, lookup, or cleanup.
 */
public class ResourceError extends ModularRuntimeException {

    public ResourceError(String message) {
        super(message);
    }

    public ResourceError(String message, ErrorContext context) {
        super(message, context);
    }

    public ResourceError(String message, Throwable cause) {
        super(message, cause);
    }

    public ResourceError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
