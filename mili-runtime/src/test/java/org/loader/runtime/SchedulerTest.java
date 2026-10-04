package org.loader.runtime;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;
import org.loader.runtime.scheduler.*;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

    private static void startScope(Scope scope) {
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

    @Test
    void submit_executesTask() throws Exception {
        Scope scope = new Scope("scheduler-test", null);
        startScope(scope);

        Scheduler scheduler = new Scheduler("test-scheduler", scope);
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);

        TaskHandle handle = scheduler.submit(scope, () -> counter.incrementAndGet());
        handle.await();

        assertEquals(1, counter.get());
        scheduler.close();
    }

    @Test
    void cancel_preventsTaskExecution() {
        Scope scope = new Scope("cancel-test", null);
        startScope(scope);

        Scheduler scheduler = new Scheduler("cancel-scheduler", scope);

        TaskHandle handle = scheduler.submit(scope, () -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        boolean cancelled = handle.cancel();
        assertTrue(cancelled || handle.state() != TaskState.QUEUED);
        scheduler.close();
    }

    @Test
    void schedulerClose_preventsNewSubmissions() {
        Scope scope = new Scope("close-test", null);
        startScope(scope);

        Scheduler scheduler = new Scheduler("close-scheduler", scope);
        scheduler.close();

        assertThrows(IllegalStateException.class,
                () -> scheduler.submit(scope, () -> {}));
    }
}
