package org.loader.runtime.error;

/**
 * Errors related to permission policy violations or denied operations.
 */
public class PermissionError extends ModularRuntimeException {

    public PermissionError(String message) {
        super(message);
    }

    public PermissionError(String message, ErrorContext context) {
        super(message, context);
    }

    public PermissionError(String message, Throwable cause) {
        super(message, cause);
    }

    public PermissionError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
