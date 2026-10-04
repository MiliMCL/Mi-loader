package org.loader.runtime.error;

/**
 * Structured error context attached to typed runtime errors.
 * Provides the diagnostic information required by ERROR_MODEL.md:
 * component_owner_operation_state_cause_retryable.
 */
public record ErrorContext(
        String component,
        String owner,
        String operation,
        String state,
        Throwable cause,
        boolean retryable
) {
    public ErrorContext {
        if (component == null || component.isBlank()) {
            throw new IllegalArgumentException("ErrorContext.component must not be blank");
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ErrorContext[");
        sb.append(component);
        if (owner != null) sb.append(", owner=").append(owner);
        if (operation != null) sb.append(", op=").append(operation);
        if (state != null) sb.append(", state=").append(state);
        if (cause != null) sb.append(", cause=").append(cause.getClass().getSimpleName());
        sb.append(", retryable=").append(retryable);
        sb.append(']');
        return sb.toString();
    }
}
