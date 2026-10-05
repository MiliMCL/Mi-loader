package org.loader.api.exception;

/**
 * Exception related to mod loading, initialization, or execution errors.
 */
public class ModException extends MiliException {

    private final String modId;

    /**
     * Creates a new ModException with the specified message.
     */
    public ModException(String message) {
        super(message);
        this.modId = null;
    }

    /**
     * Creates a new ModException with the specified message and cause.
     */
    public ModException(String message, Throwable cause) {
        super(message, cause);
        this.modId = null;
    }

    /**
     * Creates a new ModException associated with a specific mod.
     *
     * @param modId   the mod identifier
     * @param message the error message
     */
    public ModException(String modId, String message) {
        super(message);
        this.modId = modId;
    }

    /**
     * Creates a new ModException associated with a specific mod, with a cause.
     *
     * @param modId   the mod identifier
     * @param message the error message
     * @param cause   the underlying cause
     */
    public ModException(String modId, String message, Throwable cause) {
        super(message, cause);
        this.modId = modId;
    }

    /**
     * Returns the mod identifier associated with this exception, or {@code null} if unspecified.
     */
    public String modId() {
        return modId;
    }
}
