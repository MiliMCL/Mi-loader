package org.loader.runtime.error;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the typed Error Model hierarchy.
 */
class ErrorModelTest {

    @Test
    void errorContext_validatesComponent() {
        assertThrows(IllegalArgumentException.class,
                () -> new ErrorContext("", null, null, null, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ErrorContext("  ", null, null, null, null, false));
    }

    @Test
    void errorContext_storesAllFields() {
        Throwable cause = new RuntimeException("root");
        var ctx = new ErrorContext("scheduler", "scope-1", "submit", "RUNNING", cause, true);

        assertEquals("scheduler", ctx.component());
        assertEquals("scope-1", ctx.owner());
        assertEquals("submit", ctx.operation());
        assertEquals("RUNNING", ctx.state());
        assertSame(cause, ctx.cause());
        assertTrue(ctx.retryable());
    }

    @Test
    void modularRuntimeException_defaultContext() {
        var error = new ModularRuntimeException("something failed");

        assertNotNull(error.context());
        assertEquals("unknown", error.component());
        assertFalse(error.isRetryable());
        assertNotNull(error.toString());
    }

    @Test
    void modularRuntimeException_withContextAndCause() {
        Throwable cause = new IllegalStateException("bad state");
        var ctx = new ErrorContext("kernel", "root", "init", "STARTING", cause, false);
        var error = new ModularRuntimeException("init failed", ctx);

        assertEquals("kernel", error.component());
        assertSame(cause, error.context().cause());
        assertFalse(error.isRetryable());
        assertTrue(error.toString().contains("ModularRuntimeException"));
    }

    @Test
    void specializedErrors_areTyped() {
        var lifecycleErr = new LifecycleError("bad transition");
        var depErr = new DependencyError("missing dep");
        var permErr = new PermissionError("denied");
        var capErr = new CapabilityError("no capability");
        var resErr = new ResourceError("resource missing");
        var schedErr = new SchedulerError("task failed");
        var mcErr = new MinecraftIntegrationError("bridge failure");
        var netErr = new NetworkError("connection lost");
        var configErr = new ConfigurationError("bad config");

        // All are RuntimeException (unchecked)
        assertTrue(lifecycleErr instanceof RuntimeException);
        assertTrue(lifecycleErr instanceof ModularRuntimeException);

        // Each has correct type
        assertEquals("LifecycleError", lifecycleErr.getClass().getSimpleName());
        assertEquals("DependencyError", depErr.getClass().getSimpleName());
        assertEquals("PermissionError", permErr.getClass().getSimpleName());
        assertEquals("CapabilityError", capErr.getClass().getSimpleName());
        assertEquals("ResourceError", resErr.getClass().getSimpleName());
        assertEquals("SchedulerError", schedErr.getClass().getSimpleName());
        assertEquals("MinecraftIntegrationError", mcErr.getClass().getSimpleName());
        assertEquals("NetworkError", netErr.getClass().getSimpleName());
        assertEquals("ConfigurationError", configErr.getClass().getSimpleName());
    }

    @Test
    void specializedErrors_withContext() {
        var ctx = new ErrorContext("mod-loader", "mod-a", "load", "LOADING", null, true);
        var error = new DependencyError("missing required dep", ctx);

        assertEquals("mod-loader", error.component());
        assertTrue(error.isRetryable());
    }

    @Test
    void specializedErrors_withCause() {
        Throwable cause = new NullPointerException("scope was null");
        var error = new LifecycleError("transition failed", cause);

        assertSame(cause, error.getCause());
    }

    @Test
    void errorsAreCatchableAsRuntimeException() {
        // Since ModularRuntimeException extends java.lang.RuntimeException,
        // existing catch (RuntimeException e) blocks still work
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            throw new CapabilityError("test");
        });
        assertTrue(thrown instanceof ModularRuntimeException);
    }
}
