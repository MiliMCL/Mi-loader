package org.loader.runtime.error;

/**
 * Errors related to configuration access or value validation.
 */
public class ConfigurationError extends ModularRuntimeException {

    public ConfigurationError(String message) {
        super(message);
    }

    public ConfigurationError(String message, ErrorContext context) {
        super(message, context);
    }

    public ConfigurationError(String message, Throwable cause) {
        super(message, cause);
    }

    public ConfigurationError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
