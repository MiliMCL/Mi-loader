package org.loader.runtime;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Scope lifecycle and resource management.
 */
class ScopeTest {

    /**
     * Helper to quickly bring a scope to RUNNING state.
     */
    private static void startScope(Scope scope) {
        if (scope.state() == LifecycleState.DISCOVERED || scope.state() == LifecycleState.RESOLVED
                || scope.state() == LifecycleState.LOADED || scope.state() == LifecycleState.INITIALIZED
                || scope.state() == LifecycleState.REGISTERED) {
            // Walk through all states up to RUNNING
            LifecycleState current = scope.state();
            while (current != LifecycleState.RUNNING) {
                LifecycleState next = switch (current) {
                    case DISCOVERED -> LifecycleState.RESOLVED;
                    case RESOLVED -> LifecycleState.LOADED;
                    case LOADED -> LifecycleState.INITIALIZED;
                    case INITIALIZED -> LifecycleState.REGISTERED;
                    case REGISTERED -> LifecycleState.RUNNING;
                    default -> throw new IllegalStateException("Unexpected state: " + current);
                };
                scope.transitionTo(next);
                current = next;
            }
        }
    }

    @Test
    void scopeLifecycle_transitionsThroughStates() {
        Scope scope = new Scope("test", null);

        assertEquals(LifecycleState.DISCOVERED, scope.state());

        scope.transitionTo(LifecycleState.RESOLVED);
        assertEquals(LifecycleState.RESOLVED, scope.state());

        scope.transitionTo(LifecycleState.LOADED);
        assertEquals(LifecycleState.LOADED, scope.state());

        scope.transitionTo(LifecycleState.INITIALIZED);
        assertEquals(LifecycleState.INITIALIZED, scope.state());

        scope.transitionTo(LifecycleState.REGISTERED);
        assertEquals(LifecycleState.REGISTERED, scope.state());

        scope.transitionTo(LifecycleState.RUNNING);
        assertEquals(LifecycleState.RUNNING, scope.state());
        assertFalse(scope.isStopped());

        scope.shutdown();
        assertEquals(LifecycleState.STOPPED, scope.state());
        assertTrue(scope.isStopped());
    }

    @Test
    void scopeShutdown_isIdempotent() {
        Scope scope = new Scope("idempotent-test", null);
        startScope(scope);

        scope.shutdown();
        assertDoesNotThrow(scope::shutdown);
        assertTrue(scope.isStopped());
    }

    @Test
    void invalidTransition_throws() {
        Scope scope = new Scope("invalid-test", null);
        assertThrows(IllegalStateException.class,
                () -> scope.transitionTo(LifecycleState.RUNNING));
    }

    @Test
    void childScope_cleanupOnParentStop() {
        Scope parent = new Scope("parent", null);
        startScope(parent);

        Scope child = parent.createChild("child");
        startScope(child);

        parent.shutdown();

        assertTrue(child.isStopped());
        assertTrue(parent.isStopped());
    }

    @Test
    void resourceCleanup_onScopeShutdown() {
        Scope scope = new Scope("resource-test", null);
        startScope(scope);

        List<String> cleanupOrder = new ArrayList<>();

        Resource res1 = new SimpleResource("res1", scope, cleanupOrder);
        Resource res2 = new SimpleResource("res2", scope, cleanupOrder);

        scope.registerResource(res1);
        scope.registerResource(res2);

        scope.shutdown();

        assertTrue(res1.isClosed());
        assertTrue(res2.isClosed());
        assertEquals(List.of("res2", "res1"), cleanupOrder); // Reverse order
    }

    @Test
    void capability_grantAndRevoke() {
        Scope scope = new Scope("capability-test", null);
        startScope(scope);

        CapabilityToken<String> token = scope.grantCapability(String.class, "test-value");
        assertTrue(token.isActive());

        token.revoke();
        assertFalse(token.isActive());
    }

    @Test
    void capability_invalidAfterScopeStop() {
        Scope scope = new Scope("cap-invalid-test", null);
        startScope(scope);

        CapabilityToken<String> token = scope.grantCapability(String.class, "value");
        assertTrue(token.isActive());

        scope.shutdown();
        assertFalse(token.isActive());
    }

    @Test
    void listenerReceivesStateChanges() {
        Scope scope = new Scope("listener-test", null);
        List<LifecycleState> transitions = new ArrayList<>();

        scope.addListener((s, from, to) -> transitions.add(to));

        scope.transitionTo(LifecycleState.RESOLVED);
        scope.transitionTo(LifecycleState.LOADED);

        assertEquals(List.of(LifecycleState.RESOLVED, LifecycleState.LOADED), transitions);
    }

    @Test
    void registerResource_afterStop_throws() {
        Scope scope = new Scope("stopped-test", null);
        scope.shutdown();

        assertTrue(scope.isStopped());
        assertThrows(IllegalStateException.class,
                () -> scope.registerResource(new SimpleResource("late", scope, new ArrayList<>())));
    }

    /**
     * Simple resource for testing.
     */
    static class SimpleResource implements Resource {
        private final String id;
        private final Scope owner;
        private final List<String> cleanupLog;
        private volatile boolean closed = false;

        SimpleResource(String id, Scope owner, List<String> cleanupLog) {
            this.id = id;
            this.owner = owner;
            this.cleanupLog = cleanupLog;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Scope owner() {
            return owner;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                cleanupLog.add(id);
            }
        }
    }
}
