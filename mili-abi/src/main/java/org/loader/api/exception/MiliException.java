package org.loader.api.exception;

/**
 * Base exception for all Mili API errors.
 * <p>
 * This is the root of the Mili exception hierarchy. It extends
 * {@link RuntimeException} so that API consumers are not forced to
 * catch every Mili exception, consistent with the Runtime exception model.
 */
public class MiliException extends RuntimeException {

    /**
     * Creates a new MiliException with the specified message.
     */
    public MiliException(String message) {
        super(message);
    }

    /**
     * Creates a new MiliException with the specified message and cause.
     */
    public MiliException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new MiliException with the specified cause.
     */
    public MiliException(Throwable cause) {
        super(cause);
    }
}
