package org.loader.runtime.instance;

/**
 * Exception thrown when mod management operations fail.
 */
public class ModManagerException extends RuntimeException {

    public ModManagerException(String message) {
        super(message);
    }

    public ModManagerException(String message, Throwable cause) {
        super(message, cause);
    }
}
