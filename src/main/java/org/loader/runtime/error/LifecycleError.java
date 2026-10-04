package org.loader.runtime.error;

/**
 * Errors related to lifecycle state transitions or violations.
 */
public class LifecycleError extends ModularRuntimeException {

    public LifecycleError(String message) {
        super(message);
    }

    public LifecycleError(String message, ErrorContext context) {
        super(message, context);
    }

    public LifecycleError(String message, Throwable cause) {
        super(message, cause);
    }

    public LifecycleError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
