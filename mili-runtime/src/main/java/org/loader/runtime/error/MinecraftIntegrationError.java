package org.loader.runtime.error;

/**
 * Errors related to Minecraft integration bridge or lifecycle.
 */
public class MinecraftIntegrationError extends ModularRuntimeException {

    public MinecraftIntegrationError(String message) {
        super(message);
    }

    public MinecraftIntegrationError(String message, ErrorContext context) {
        super(message, context);
    }

    public MinecraftIntegrationError(String message, Throwable cause) {
        super(message, cause);
    }

    public MinecraftIntegrationError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
