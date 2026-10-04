package org.loader.runtime.error;

/**
 * Base class for all typed runtime errors.
 * Every runtime error carries an {@link ErrorContext} describing
 * component, owner, operation, state, and retry semantics.
 * <p>
 * Extends {@link RuntimeException} so it integrates with existing code
 * that uses unchecked exceptions while still providing typed hierarchy.
 */
public class ModularRuntimeException extends RuntimeException {

    private final ErrorContext context;

    public ModularRuntimeException(String message) {
        super(message);
        this.context = new ErrorContext("unknown", null, null, null, null, false);
    }

    public ModularRuntimeException(String message, ErrorContext context) {
        super(message, context.cause());
        this.context = context;
    }

    public ModularRuntimeException(String message, Throwable cause) {
        super(message, cause);
        this.context = new ErrorContext("unknown", null, null, null, cause, false);
    }

    public ModularRuntimeException(String message, ErrorContext context, Throwable cause) {
        super(message, cause);
        this.context = context;
    }

    public ErrorContext context() {
        return context;
    }

    public String component() {
        return context.component();
    }

    public boolean isRetryable() {
        return context.retryable();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + getMessage() + " " + context;
    }
}
