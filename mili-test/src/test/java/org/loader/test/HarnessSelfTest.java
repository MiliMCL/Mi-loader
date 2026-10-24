package org.loader.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.scheduler.TaskHandle;
import org.loader.runtime.scheduler.TaskState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试框架自身的验收测试（同时充当最小示例 —— A4 验收："最小示例 Mod、
 * Scope 自动清理、事件断言、任务取消、失败隔离、并发测试"）。
 */
class HarnessSelfTest extends MiliTest {

    @Test
    @DisplayName("最小示例：fixture 安装、Scope 到 RUNNING、任务在真实调度器上完成")
    void minimalModScenario() throws Exception {
        TestModFixture mod = mili().installMod("example");
        assertEquals("example", mod.manifest().id());

        CountDownLatch done = new CountDownLatch(1);
        TaskHandle handle = mod.scheduler().submit(mod.scope(), done::countDown);
        assertTrue(done.await(2, TimeUnit.SECONDS), "真实 Scheduler 应执行任务");
        assertCompleted(handle);

        mod.close();
    }

    @Test
    @DisplayName("Scope 自动清理：fixture 关闭后，排队任务被取消、新提交被拒绝")
    void scopeAutoCleanup() throws Exception {
        TestModFixture mod = mili().installMod("cleanup");
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();

        // 占满 2 个工作线程的任务 + 1 个排队任务
        TaskHandle blocker1 = mod.scheduler().submit(mod.scope(),
                () -> awaitQuietly(release));
        TaskHandle blocker2 = mod.scheduler().submit(mod.scope(),
                () -> awaitQuietly(release));
        TaskHandle queued = mod.scheduler().submit(mod.scope(), ran::incrementAndGet);

        mod.close(); // Mod 卸载语义：Scope 关闭

        // 排队任务最终应被取消（给调度器一点时间传播）
        long deadline = System.currentTimeMillis() + 2000;
        while (queued.state() != TaskState.CANCELLED
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(TaskState.CANCELLED, queued.state(),
                "Scope 关闭应取消未开始的任务");
        assertEquals(0, ran.get(), "被取消的任务不应执行");

        // 新提交被拒绝（Scope 已停止）
        assertThrows(Exception.class,
                () -> mod.scheduler().submit(mod.scope(), () -> {
                }));

        release.countDown(); // 释放阻塞线程，避免泄漏到其他测试
    }

    @Test
    @DisplayName("事件断言：post 被记录、顺序保持、退订后不再收到")
    void eventAssertions() {
        TestModFixture mod = mili().installMod("events");
        record ChatEvent(String msg) {
        }

        try (EventRecorder<ChatEvent> rec = EventRecorder.on(mod.eventBus(), ChatEvent.class)) {
            mod.eventBus().post(new ChatEvent("one"));
            mod.eventBus().post(new ChatEvent("two"));

            assertEquals(2, rec.count());
            assertEquals(List.of("one", "two"),
                    rec.events().stream().map(ChatEvent::msg).toList());
            assertEquals("two", rec.last().msg());
        }
        // 退订后不再接收
        mod.eventBus().post(new ChatEvent("three"));
        assertEquals(2, rec.count(), "退订后不应继续接收");
    }

    @Test
    @DisplayName("跨线程事件：postAsync 也能被 awaitCount 捕获")
    void asyncEvents() throws Exception {
        TestModFixture mod = mili().installMod("async-events");
        record Tick(int n) {
        }
        try (EventRecorder<Tick> rec = EventRecorder.on(mod.eventBus(), Tick.class)) {
            for (int i = 0; i < 5; i++) {
                mod.eventBus().postAsync(new Tick(i));
            }
            assertTrue(rec.awaitCount(5, 2000), "postAsync 的 5 个事件应全部到达");
        }
    }

    @Test
    @DisplayName("失败隔离：一个任务抛异常不影响其余任务")
    void failureIsolation() throws Exception {
        TestModFixture mod = mili().installMod("isolation");
        AtomicInteger good = new AtomicInteger();

        TaskHandle bad = mod.scheduler().submit(mod.scope(), () -> {
            throw new IllegalStateException("boom");
        });
        TaskHandle fine = mod.scheduler().submit(mod.scope(), good::incrementAndGet);

        awaitState(fine, TaskState.COMPLETED, 2000);
        awaitState(bad, TaskState.FAILED, 2000);
        assertEquals(1, good.get(), "坏任务不应拖垮好任务");
    }

    @Test
    @DisplayName("任务取消：未开始的任务可取消，取消后不再执行")
    void taskCancellation() throws Exception {
        TestModFixture mod = mili().installMod("cancel");
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();

        TaskHandle blocker = mod.scheduler().submit(mod.scope(), () -> awaitQuietly(gate));
        TaskHandle victim = mod.scheduler().submit(mod.scope(), ran::incrementAndGet);

        assertTrue(victim.cancel(), "排队任务应可取消");
        awaitState(victim, TaskState.CANCELLED, 2000);
        assertEquals(0, ran.get());

        gate.countDown();
        awaitState(blocker, TaskState.COMPLETED, 2000);
    }

    @Test
    @DisplayName("并发：100 个并发任务全部完成且计数准确")
    void concurrency() throws Exception {
        TestModFixture mod = mili().installMod("concurrent");
        CountDownLatch all = new CountDownLatch(100);
        List<TaskHandle> handles = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 100; i++) {
            handles.add(mod.scheduler().submit(mod.scope(), all::countDown));
        }
        assertTrue(all.await(5, TimeUnit.SECONDS), "100 个任务应在 5 秒内完成");
        for (TaskHandle h : handles) {
            assertEquals(TaskState.COMPLETED, h.state(), "任务 " + h.taskId());
        }
    }

    @Test
    @DisplayName("Runtime 级隔离：两个测试 Runtime 互不可见")
    void runtimeIsolation() {
        try (MiliTestRuntime other = MiliTestRuntime.create("other")) {
            assertTrue(other.rootScope() != mili().rootScope(),
                    "不同 Runtime 的根 Scope 必须相互独立");
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitState(TaskHandle handle, TaskState expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (handle.state() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, handle.state(),
                "等待状态超时，当前 " + handle.state());
    }
}
