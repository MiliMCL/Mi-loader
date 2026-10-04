package org.loader.runtime;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;
import org.loader.runtime.scheduler.*;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeTest {

    @Test
    void runtime_startAndStop() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("test-runtime");

        assertFalse(runtime.isRunning());

        runtime.start();
        assertTrue(runtime.isRunning());
        assertEquals(LifecycleState.RUNNING, runtime.rootScope().state());

        runtime.close();
        assertFalse(runtime.isRunning());
        assertTrue(runtime.rootScope().isStopped());
    }

    @Test
    void runtime_doubleStart_throws() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("double-start");
        runtime.start();

        try {
            assertThrows(IllegalStateException.class, runtime::start);
        } finally {
            runtime.close();
        }
    }

    @Test
    void runtime_shutdownPropagatesToChildren() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("child-propagation");
        runtime.start();

        // Already running at root level, so children can transition through states
        Scope child1 = runtime.rootScope().createChild("child1");
        Scope child2 = runtime.rootScope().createChild("child2");

        // Start children through lifecycle
        for (Scope child : new Scope[]{child1, child2}) {
            child.transitionTo(LifecycleState.RESOLVED);
            child.transitionTo(LifecycleState.LOADED);
            child.transitionTo(LifecycleState.INITIALIZED);
            child.transitionTo(LifecycleState.REGISTERED);
            child.transitionTo(LifecycleState.RUNNING);
        }

        runtime.close();

        assertTrue(child1.isStopped());
        assertTrue(child2.isStopped());
    }

    @Test
    void runtime_schedulerIsAccessible() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("scheduler-access");
        runtime.start();

        Scheduler scheduler = new Scheduler("main-scheduler", runtime.rootScope());
        runtime.rootScope().registerResource(scheduler);

        var metrics = scheduler.metrics();
        assertNotNull(metrics);

        runtime.close();
        assertTrue(scheduler.isClosed());
    }

    @Test
    void runtime_multipleShutdownCalls_safe() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("multi-shutdown");
        runtime.start();

        runtime.close();
        assertDoesNotThrow(runtime::close);
    }
}
