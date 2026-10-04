package org.loader.runtime.kernel;

/**
 * Exception thrown when a Scope fails to shutdown properly.
 */
public class ScopeShutdownException extends RuntimeException {

    public ScopeShutdownException(String message) {
        super(message);
    }

    public ScopeShutdownException(String message, Throwable cause) {
        super(message, cause);
    }
}
