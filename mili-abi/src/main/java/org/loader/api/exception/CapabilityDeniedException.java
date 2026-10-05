package org.loader.api.exception;

/**
 * Exception thrown when a capability operation is denied.
 * <p>
 * This may occur when a mod attempts to access or grant a capability
 * for which it lacks authorization.
 */
public class CapabilityDeniedException extends MiliException {

    private final String capabilityName;

    /**
     * Creates a new CapabilityDeniedException.
     *
     * @param message the error message
     */
    public CapabilityDeniedException(String message) {
        super(message);
        this.capabilityName = null;
    }

    /**
     * Creates a new CapabilityDeniedException with a capability name.
     *
     * @param capabilityName the name of the denied capability
     * @param message        the error message
     */
    public CapabilityDeniedException(String capabilityName, String message) {
        super(message);
        this.capabilityName = capabilityName;
    }

    /**
     * Creates a new CapabilityDeniedException with a cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public CapabilityDeniedException(String message, Throwable cause) {
        super(message, cause);
        this.capabilityName = null;
    }

    /**
     * Returns the name of the denied capability, or {@code null} if unspecified.
     */
    public String capabilityName() {
        return capabilityName;
    }
}
