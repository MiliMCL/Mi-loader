package org.loader.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.error.SchedulerError;
import org.loader.runtime.kernel.LifecycleState;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.scheduler.Scheduler;
import org.loader.runtime.scheduler.TaskHandle;
import org.loader.runtime.scheduler.TaskPriority;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scheduler 的排队/拒绝契约回归测试。
 *
 * <h2>这些测试在防什么</h2>
 *
 * <p>旧实现的队列是「无界 + offer 永远返回 true + CallerRunsPolicy」：
 * 线程数永远停在 corePoolSize，任务无限堆积，拒绝策略一次都不会触发，
 * 而代码注释却宣称「已施加背压」。同时优先级比较器是升序，导致
 * {@code LOW} 比 {@code CRITICAL} 先执行 —— 与两个 {@code TaskPriority}
 * 枚举上写明的「值越大优先级越高」正好相反。
 *
 * <p>本类用可观测的方式钉住四件事：优先级顺序、容量上限与显式拒绝、
 * 关闭/取消语义、以及「被接受的任务不会静默消失」。
 */
class SchedulerTest {

    private Scheduler scheduler;
    private Scope scope;

    private Scheduler newScheduler(String id, int corePoolSize, int capacity) {
        scope = new Scope(id + "-scope", null);
        startScope(scope);
        scheduler = new Scheduler(id, scope, corePoolSize, capacity);
        return scheduler;
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.close();
        }
    }

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

    // ── 基本语义 ────────────────────────────────────────────────────────────

    @Test
    void submit_executesTask() throws Exception {
        Scheduler s = newScheduler("basic", 1, 64);
        AtomicInteger counter = new AtomicInteger();

        s.submit(scope, counter::incrementAndGet).await();

        assertEquals(1, counter.get());
        assertEquals(1, s.metrics().completed());
        assertEquals(0, s.metrics().active());
    }

    @Test
    @DisplayName("优先级：值越大越先执行（旧实现是反的）")
    void higherPriorityRunsFirst() throws Exception {
        Scheduler s = newScheduler("priority", 1, 64);
        CountDownLatch blocker = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();

        // 先用一个阻塞任务占住唯一的工作线程，让后面的任务真正进入队列
        s.submit(scope, () -> await(blocker));

        s.submit(scope, () -> order.add("LOW"), TaskPriority.LOW);
        s.submit(scope, () -> order.add("NORMAL"), TaskPriority.NORMAL);
        s.submit(scope, () -> order.add("CRITICAL"), TaskPriority.CRITICAL);

        blocker.countDown();
        waitUntil(() -> order.size() == 3, "三个任务都应完成");

        assertEquals(List.of("CRITICAL", "NORMAL", "LOW"), order);
    }

    @Test
    @DisplayName("同优先级保持提交顺序（FIFO）")
    void samePriorityIsFifo() throws Exception {
        Scheduler s = newScheduler("fifo", 1, 64);
        CountDownLatch blocker = new CountDownLatch(1);
        List<Integer> order = new CopyOnWriteArrayList<>();

        s.submit(scope, () -> await(blocker));
        for (int i = 0; i < 10; i++) {
            final int n = i;
            s.submit(scope, () -> order.add(n), TaskPriority.NORMAL);
        }

        blocker.countDown();
        waitUntil(() -> order.size() == 10, "十个任务都应完成");

        for (int i = 0; i < 10; i++) {
            assertEquals(i, order.get(i));
        }
    }

    // ── 容量与拒绝 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("队列满 → 显式 SchedulerError，不静默丢弃")
    void queueOverflowIsRejectedExplicitly() throws Exception {
        // core=1 → max=2；capacity=1
        Scheduler s = newScheduler("overflow", 1, 1);
        CountDownLatch blocker = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();

        s.submit(scope, () -> {
            ran.incrementAndGet();
            await(blocker);
        });                                            // 线程 A 执行
        s.submit(scope, () -> {
            ran.incrementAndGet();
            await(blocker);
        });                                            // 进队列（size=1 = capacity）
        s.submit(scope, () -> {
            ran.incrementAndGet();
            await(blocker);
        });                                            // 队列满 → 扩容到线程 B 直接执行

        // A、B 都在执行阻塞任务、队列里还有 1 个 → 再提交必然被拒
        SchedulerError error = assertThrows(SchedulerError.class,
                () -> s.submit(scope, ran::incrementAndGet));
        assertTrue(error.getMessage().contains("拒绝"), error.getMessage());

        Scheduler.SchedulerMetrics m = s.metrics();
        assertEquals(1, m.rejected(), "拒绝次数必须可观测");
        assertEquals(1, m.queueCapacity());
        assertTrue(m.queueDepth() <= 1, "队列深度不得越界: " + m.queueDepth());

        blocker.countDown();
        waitUntil(() -> s.metrics().completed() == 3, "被接受的任务都必须执行");

        assertEquals(3, ran.get(), "被接受的三个任务都必须跑过");
        assertEquals(4, s.metrics().submitted(), "提交计数包含被拒的那一次");
        assertEquals(1, s.metrics().rejected());
        assertEquals(3, s.metrics().submitted() - s.metrics().rejected(),
                "接受数 = 提交数 - 拒绝数：没有任何任务凭空消失");
    }

    @Test
    @DisplayName("capacity <= 0 直接拒绝构造")
    void invalidCapacityRejected() {
        Scope s = new Scope("bad-capacity", null);
        assertThrows(IllegalArgumentException.class, () -> new Scheduler("bad", s, 1, 0));
    }

    // ── 关闭与取消 ──────────────────────────────────────────────────────────

    @Test
    void schedulerClose_preventsNewSubmissions() {
        Scheduler s = newScheduler("close", 1, 8);
        s.close();

        assertThrows(IllegalStateException.class, () -> s.submit(scope, () -> {
        }));
    }

    @Test
    @DisplayName("取消排队中的任务 → 它不会执行")
    void cancelRemovesQueuedTask() throws Exception {
        Scheduler s = newScheduler("cancel", 1, 64);
        CountDownLatch blocker = new CountDownLatch(1);
        AtomicInteger cancelledRan = new AtomicInteger();

        s.submit(scope, () -> await(blocker));
        TaskHandle queued = s.submit(scope, cancelledRan::incrementAndGet);

        assertTrue(queued.cancel(), "排队中的任务应可取消");
        assertEquals(org.loader.runtime.scheduler.TaskState.CANCELLED, queued.state());
        blocker.countDown();

        // 给工作线程充分时间去（不）执行它
        waitUntil(() -> s.metrics().active() == 0, "工作线程应回到空闲");
        Thread.sleep(50);
        assertEquals(0, cancelledRan.get(), "已取消的任务不得执行");
    }

    // ── 异常与并发 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("任务抛异常 → 计入 failed，future 异常完成，调度器继续可用")
    void taskFailureIsRecordedAndSchedulerSurvives() throws Exception {
        Scheduler s = newScheduler("failure", 1, 16);

        TaskHandle failing = s.submit(scope, () -> {
            throw new IllegalStateException("boom");
        });
        assertThrows(RuntimeException.class, failing::await);

        s.submit(scope, () -> {
        }).await();

        assertEquals(1, s.metrics().failed());
        assertEquals(1, s.metrics().completed());
    }

    @Test
    @DisplayName("并发提交：全部执行，无丢失、无死锁")
    void concurrentSubmissionsAllComplete() throws Exception {
        Scheduler s = newScheduler("concurrent", 4, 4096);
        int threads = 8;
        int perThread = 50;
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(threads * perThread);

        Thread[] producers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            producers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    s.submit(scope, () -> {
                        counter.incrementAndGet();
                        done.countDown();
                    }, TaskPriority.NORMAL);
                }
            });
            producers[t].start();
        }
        for (Thread p : producers) {
            p.join(10_000);
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "所有任务都应在超时前完成");
        assertEquals(threads * perThread, counter.get());
        waitUntil(() -> s.metrics().active() == 0, "最终应无活动任务");
        assertEquals(threads * perThread, s.metrics().completed());
    }

    // ── 周期任务 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("周期任务按周期执行，cancel 后停止且 future 完成")
    void repeatingTaskRunsAndStops() throws Exception {
        Scheduler s = newScheduler("repeating", 1, 16);
        AtomicInteger ticks = new AtomicInteger();

        TaskHandle handle = s.submitRepeating(scope, ticks::incrementAndGet,
                0, 20, TaskPriority.NORMAL);

        waitUntil(() -> ticks.get() >= 3, "周期任务应至少执行三次");
        assertTrue(handle.cancel(), "周期任务必须可取消");
        assertEquals(org.loader.runtime.scheduler.TaskState.CANCELLED, handle.state());

        int afterCancel = ticks.get();
        Thread.sleep(150);
        assertEquals(afterCancel, ticks.get(), "取消后不得再执行");

        // future 必须以「已取消」结束，否则等待方永久挂起
        assertTrue(handle.future().isDone(), "future 必须结束");
        assertTrue(handle.future().isCancelled(), "取消的语义是 CancellationException");
    }

    @Test
    @DisplayName("scope 停止 → 周期任务自行终止并释放计时器线程")
    void repeatingTaskStopsWithScope() throws Exception {
        Scheduler s = newScheduler("repeating-scope", 1, 16);
        AtomicInteger ticks = new AtomicInteger();

        TaskHandle handle = s.submitRepeating(scope, ticks::incrementAndGet,
                0, 20, TaskPriority.NORMAL);
        waitUntil(() -> ticks.get() >= 2, "周期任务应至少执行两次");

        scope.shutdown();

        int afterStop = ticks.get();
        Thread.sleep(150);
        assertEquals(afterStop, ticks.get(), "scope 停止后不得再执行");
        assertDoesNotThrow(() -> handle.future().get(2, TimeUnit.SECONDS));
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        fail("超时: " + message);
    }
}
