package org.loader.runtime.error;

/**
 * Errors related to mod dependency resolution or conflicts.
 */
public class DependencyError extends ModularRuntimeException {

    public DependencyError(String message) {
        super(message);
    }

    public DependencyError(String message, ErrorContext context) {
        super(message, context);
    }

    public DependencyError(String message, Throwable cause) {
        super(message, cause);
    }

    public DependencyError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
