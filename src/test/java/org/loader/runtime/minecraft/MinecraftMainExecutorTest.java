package org.loader.runtime.minecraft;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MinecraftMainExecutor.
 */
class MinecraftMainExecutorTest {

    @Test
    void processQueue_executesSubmittedTasks() {
        MinecraftMainExecutor executor = new MinecraftMainExecutor();
        AtomicInteger counter = new AtomicInteger(0);

        CompletableFuture<Void> f1 = executor.submitToMainAsync(() -> counter.incrementAndGet());
        CompletableFuture<Void> f2 = executor.submitToMainAsync(() -> counter.incrementAndGet());

        // Not processed yet
        assertEquals(0, counter.get());
        assertEquals(2, executor.pendingCount());

        // Process queue (called from main thread)
        int processed = executor.processQueue();
        assertEquals(2, processed);
        assertEquals(2, counter.get());

        // Verify futures completed
        assertDoesNotThrow(() -> f1.get(1, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> f2.get(1, TimeUnit.SECONDS));

        executor.close();
    }

    @Test
    void submitToMain_blocksUntilDone() throws Exception {
        MinecraftMainExecutor executor = new MinecraftMainExecutor();
        AtomicInteger value = new AtomicInteger(0);

        // Main thread processes the queue
        CompletableFuture<Void> mainPoller = CompletableFuture.runAsync(() -> {
            // Wait briefly then process
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            executor.processQueue();
        });

        // Submit and block
        executor.submitToMain(() -> value.set(42));

        mainPoller.get(2, TimeUnit.SECONDS);
        assertEquals(42, value.get());

        executor.close();
    }

    @Test
    void supplyToMainAsync_returnsValue() {
        MinecraftMainExecutor executor = new MinecraftMainExecutor();

        CompletableFuture<Integer> result = executor.supplyToMainAsync(() -> 123);
        executor.processQueue();

        assertEquals(123, result.join());
        executor.close();
    }

    @Test
    void close_rejectsNewSubmissions() {
        MinecraftMainExecutor executor = new MinecraftMainExecutor();
        executor.close();

        CompletableFuture<Void> rejected = executor.submitToMainAsync(() -> {});
        assertTrue(rejected.isCompletedExceptionally());
    }

    @Test
    void close_failsPendingTasks() {
        MinecraftMainExecutor executor = new MinecraftMainExecutor();

        CompletableFuture<Void> pending = executor.submitToMainAsync(() -> {});
        executor.close();

        assertTrue(pending.isCompletedExceptionally());
    }

    @Test
    void exceptionIn_task_failsFuture() {
        MinecraftMainExecutor executor = new MinecraftMainExecutor();

        CompletableFuture<Void> f = executor.submitToMainAsync(() -> {
            throw new RuntimeException("simulated failure");
        });

        executor.processQueue();
        assertTrue(f.isCompletedExceptionally());

        executor.close();
    }

    @Test
    void submitToScheduler_returnsTaskHandle() throws Exception {
        MinecraftMainExecutor executor = new MinecraftMainExecutor();
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("exec-test");
        runtime.start();

        AtomicInteger counter = new AtomicInteger(0);
        org.loader.runtime.scheduler.Scheduler scheduler = runtime.scheduler();

        executor.submitToScheduler(runtime.rootScope(), scheduler, () -> counter.incrementAndGet(),
                org.loader.runtime.scheduler.TaskPriority.NORMAL).await();

        assertTrue(counter.get() >= 1);

        executor.close();
        runtime.close();
    }
}
