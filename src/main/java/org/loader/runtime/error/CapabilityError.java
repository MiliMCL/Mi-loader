package org.loader.runtime.error;

/**
 * Errors related to capability grant, revocation, or ownership.
 */
public class CapabilityError extends ModularRuntimeException {

    public CapabilityError(String message) {
        super(message);
    }

    public CapabilityError(String message, ErrorContext context) {
        super(message, context);
    }

    public CapabilityError(String message, Throwable cause) {
        super(message, cause);
    }

    public CapabilityError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
