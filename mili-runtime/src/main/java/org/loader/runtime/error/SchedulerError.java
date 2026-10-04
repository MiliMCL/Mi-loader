package org.loader.runtime.error;

/**
 * Errors related to task submission, execution, or cancellation.
 */
public class SchedulerError extends ModularRuntimeException {

    public SchedulerError(String message) {
        super(message);
    }

    public SchedulerError(String message, ErrorContext context) {
        super(message, context);
    }

    public SchedulerError(String message, Throwable cause) {
        super(message, cause);
    }

    public SchedulerError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
