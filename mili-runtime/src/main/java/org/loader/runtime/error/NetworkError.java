package org.loader.runtime.error;

/**
 * Errors related to network operations or resource handles.
 */
public class NetworkError extends ModularRuntimeException {

    public NetworkError(String message) {
        super(message);
    }

    public NetworkError(String message, ErrorContext context) {
        super(message, context);
    }

    public NetworkError(String message, Throwable cause) {
        super(message, cause);
    }

    public NetworkError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
