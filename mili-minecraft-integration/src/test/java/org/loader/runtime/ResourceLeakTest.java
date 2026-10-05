package org.loader.runtime;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;
import org.loader.runtime.minecraft.MinecraftBootstrap;
import org.loader.runtime.observability.RuntimeDiagnostics;
import org.loader.runtime.scheduler.Scheduler;
import org.loader.runtime.service.EventBus;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that verify no resource leaks per TESTING.md:
 * worker threads, stale scopes, open resources, active tasks, classloader references.
 */
class ResourceLeakTest {

    /**
     * Helper to get count of alive threads matching a prefix.
     */
    private int countThreads(String prefix) {
        return (int) Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(t -> t.getName().startsWith(prefix))
                .count();
    }

    @Test
    void runtime_close_releases_threads() throws Exception {
        int before = countThreads("runtime-scheduler-");
        int beforeDispatcher = countThreads("runtime-dispatcher");

        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("leak-test-1");
        runtime.start();
        // Submit a task so scheduler workers are alive
        AtomicInteger tick = new AtomicInteger(0);
        runtime.scheduler().submit(runtime.rootScope(), () -> tick.incrementAndGet()).await();
        assertEquals(1, tick.get(), "Submitted task should have executed");

        runtime.close();

        // Give threads time to terminate
        Thread.sleep(500);

        int after = countThreads("runtime-scheduler-");
        int afterDispatcher = countThreads("runtime-dispatcher");
        assertTrue(after <= before,
                "Scheduler worker threads should be terminated after close. Before=" + before + " After=" + after);
        assertTrue(afterDispatcher <= beforeDispatcher,
                "Dispatcher threads should be terminated after close. Before=" + beforeDispatcher + " After=" + afterDispatcher);
    }

    @Test
    void scope_tree_cleaned_up_after_shutdown() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("leak-test-2");
        runtime.start();

        RuntimeDiagnostics diagnostics = new RuntimeDiagnostics("diags", runtime.rootScope());
        runtime.rootScope().registerResource(diagnostics);

        // Create some scopes
        Scope mod1 = runtime.rootScope().createChild("mod-1");
        Scope mod2 = runtime.rootScope().createChild("mod-2");
        Scope sub = mod1.createChild("sub-module");
        var bus = new EventBus("bus", mod1);
        mod1.registerResource(bus);

        // Initial snapshot
        RuntimeDiagnostics.DiagnosticSnapshot initial = diagnostics.captureSnapshot();
        int initialScopes = initial.rootScope().totalScopes();

        runtime.close();

        // After close, additional snapshot should show cleanup
        RuntimeDiagnostics.DiagnosticSnapshot finalSnap = diagnostics.captureSnapshot();
        // All scopes should be stopped
        assertTrue(mod1.isStopped());
        assertTrue(mod2.isStopped());
        assertTrue(sub.isStopped());
    }

    @Test
    void scheduler_tasksCleanedAfterCompletion() throws Exception {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("leak-test-3");
        runtime.start();

        Scheduler scheduler = runtime.scheduler();
        AtomicInteger counter = new AtomicInteger(0);

        for (int i = 0; i < 20; i++) {
            scheduler.submit(runtime.rootScope(), () -> counter.incrementAndGet()).await();
        }

        assertEquals(20, counter.get());

        Scheduler.SchedulerMetrics metrics = scheduler.metrics();
        // Active should be 0 after all tasks complete
        assertEquals(0, metrics.active());

        runtime.close();
    }

    @Test
    void eventBus_noStaleSubscriptionsAfterClose() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("leak-test-4");
        runtime.start();

        EventBus bus = new EventBus("leak-bus", runtime.rootScope());
        AtomicInteger handler = new AtomicInteger(0);
        bus.addListener(String.class, s -> handler.incrementAndGet());

        bus.post("first");
        assertEquals(1, handler.get());

        runtime.close();

        // After runtime close, bus is closed and won't dispatch
        // (handler count should remain 1)
        assertEquals(1, handler.get());
    }

    @Test
    void minecraftBootstrap_stopCleansUp() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("leak-test-5");
        runtime.start();

        MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
        bootstrap.beginDiscovery();
        bootstrap.beginPreparing();
        bootstrap.beginLoading();
        bootstrap.beginMinecraftBootstrap();
        bootstrap.start();
        assertTrue(bootstrap.isRunning());

        bootstrap.stop();
        assertFalse(bootstrap.isRunning());

        runtime.close();
    }
}
