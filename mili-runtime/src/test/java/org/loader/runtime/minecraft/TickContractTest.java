package org.loader.runtime.minecraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.tick.TickContract;
import org.loader.runtime.tick.TickEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6：Single-thread Tick 正确性测试。
 *
 * <p><b>与旧版的区别</b>：旧版只测{@code TickBudget} 的减法算术
 * （断言 50/2=25、50/4=12），验证的是"字段能算"，不是"tick 能跑"。
 * 本版验证的是<b>真实执行协议</b>：阶段推进、任务执行、异常传播、
 * deadline、取消、生命周期。
 *
 * <p>这些测试不启动 Minecraft —— 通过 {@link TickEngine} 的窄接口验证
 * 语义正确性，真实 Minecraft 启动验证在 CI smoke test 中进行。
 */
@DisplayName("Tick 执行协议与单线程正确性")
class TickContractTest {

    // ── 阶段机 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("阶段严格按序推进 TICK_START→…→TICK_END")
    void phasesAdvanceInOrder() {
        TickContract c = TickContract.create(50);
        assertEquals(TickContract.TickPhase.TICK_START, c.phase());

        assertEquals(TickContract.TickPhase.PRE_TICK, c.advance());
        assertEquals(TickContract.TickPhase.SCHEDULE, c.advance());
        assertEquals(TickContract.TickPhase.CORE_TICK, c.advance());
        assertEquals(TickContract.TickPhase.ASYNC_COMPLETION, c.advance());
        assertEquals(TickContract.TickPhase.POST_TICK, c.advance());
        assertEquals(TickContract.TickPhase.TICK_END, c.advance());

        assertTrue(c.phase().isTerminal());
    }

    @Test
    @DisplayName("终阶段无法继续推进")
    void terminalPhaseCannotAdvance() {
        TickContract c = TickContract.create(50);
        c.advanceTo(TickContract.TickPhase.TICK_END);
        assertThrows(IllegalStateException.class, c::advance,
                "TICK_END 之后不应能继续推进");
    }

    @Test
    @DisplayName("advanceTo 不能回退阶段")
    void cannotRegressPhase() {
        TickContract c = TickContract.create(50);
        c.advanceTo(TickContract.TickPhase.CORE_TICK);
        assertThrows(IllegalStateException.class,
                () -> c.advanceTo(TickContract.TickPhase.PRE_TICK),
                "阶段不能回退");
    }

    @Test
    @DisplayName("已完成 tick 不能继续推进")
    void completedTickCannotAdvance() {
        TickContract c = TickContract.create(50);
        c.complete();
        assertTrue(c.isCompleted());
        assertThrows(IllegalStateException.class, c::advance);
    }

    @Test
    @DisplayName("取消后不能推进阶段")
    void cancelledTickCannotAdvance() {
        TickContract c = TickContract.create(50);
        c.cancel();
        assertTrue(c.isCancelled());
        assertThrows(TickContract.TickCancelledException.class, c::advance);
    }

    @Test
    @DisplayName("tickId 全局单调递增")
    void tickIdIsMonotonic() {
        long a = TickContract.create(50).tickId();
        long b = TickContract.create(50).tickId();
        long c = TickContract.create(50).tickId();
        assertTrue(b > a && c > b, "tickId 必须严格递增以保证顺序可追踪");
    }

    // ── 任务提交与执行 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("任务按提交顺序执行")
    void tasksExecuteInSubmissionOrder() {
        TickContract c = TickContract.create(50);
        List<String> order = new ArrayList<>();
        c.submitTask("t1", () -> order.add("t1"), TickContract.TaskPriority.NORMAL);
        c.submitTask("t2", () -> order.add("t2"), TickContract.TaskPriority.NORMAL);
        c.submitTask("t3", () -> order.add("t3"), TickContract.TaskPriority.NORMAL);

        assertEquals(3, c.taskCount());
        for (var task : c.tasks()) {
            task.run(c);
        }

        assertEquals(List.of("t1", "t2", "t3"), order,
                "单线程下任务必须严格按提交顺序执行");
    }

    @Test
    @DisplayName("任务执行后状态为 COMPLETED")
    void taskCompletesSuccessfully() {
        TickContract c = TickContract.create(50);
        AtomicBoolean ran = new AtomicBoolean(false);
        var task = c.submitTask("ok", () -> ran.set(true), TickContract.TaskPriority.NORMAL);

        assertEquals(TickContract.TaskState.CREATED, task.state());
        task.run(c);
        assertEquals(TickContract.TaskState.COMPLETED, task.state());
        assertTrue(ran.get());
        assertTrue(task.isFinished());
        assertNull(task.failure());
    }

    @Test
    @DisplayName("完成后拒绝提交新任务")
    void rejectsTaskAfterCompletion() {
        TickContract c = TickContract.create(50);
        c.complete();
        assertThrows(IllegalStateException.class,
                () -> c.submitTask("late", () -> {}, TickContract.TaskPriority.NORMAL));
    }

    @Test
    @DisplayName("末端阶段拒绝提交任务")
    void rejectsTaskInLatePhase() {
        TickContract c = TickContract.create(50);
        c.advanceTo(TickContract.TickPhase.POST_TICK);
        assertThrows(IllegalStateException.class,
                () -> c.submitTask("late", () -> {}, TickContract.TaskPriority.NORMAL));
    }

    // ── 异常传播 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("任务异常被记录并传播到 contract，不逃逸")
    void taskExceptionPropagatesToContract() {
        TickContract c = TickContract.create(50);
        RuntimeException boom = new RuntimeException("task failed");
        var task = c.submitTask("bad", () -> {
            throw boom;
        }, TickContract.TaskPriority.NORMAL);

        assertDoesNotThrow(() -> task.run(c), "任务异常不得逃逸出 run()");
        assertEquals(TickContract.TaskState.FAILED, task.state());
        assertSame(boom, task.failure());
        assertTrue(c.hasError());
        assertSame(boom, c.error());
    }

    @Test
    @DisplayName("一个任务失败不影响后续任务执行")
    void oneFailureDoesNotStopOthers() {
        TickContract c = TickContract.create(50);
        AtomicInteger survived = new AtomicInteger();
        c.submitTask("bad", () -> {
            throw new RuntimeException("boom");
        }, TickContract.TaskPriority.NORMAL);
        c.submitTask("good1", survived::incrementAndGet, TickContract.TaskPriority.NORMAL);
        c.submitTask("good2", survived::incrementAndGet, TickContract.TaskPriority.NORMAL);

        for (var t : c.tasks()) {
            t.run(c);
        }
        assertEquals(2, survived.get(), "失败任务不应影响其他任务");
        assertTrue(c.hasError());
    }

    @Test
    @DisplayName("多次异常保留首个并附加 suppressed")
    void multipleErrorsPreserveFirstWithSuppressed() {
        TickContract c = TickContract.create(50);
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        c.fail(first);
        c.fail(second);

        assertSame(first, c.error());
        assertEquals(1, first.getSuppressed().length);
        assertSame(second, first.getSuppressed()[0]);
    }

    // ── 取消 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("取消 tick 会取消未执行的任务")
    void cancelCancelsPendingTasks() {
        TickContract c = TickContract.create(50);
        var task = c.submitTask("pending", () -> fail("不应执行"),
                TickContract.TaskPriority.NORMAL);
        c.cancel();

        assertTrue(task.state() == TickContract.TaskState.CANCELLED);
        assertEquals(TickContract.TaskState.CANCELLED, task.run(c),
                "已取消任务不应执行");
    }

    @Test
    @DisplayName("取消后拒绝提交任务")
    void rejectsTaskAfterCancel() {
        TickContract c = TickContract.create(50);
        c.cancel();
        assertThrows(TickContract.TickCancelledException.class,
                () -> c.submitTask("late", () -> {}, TickContract.TaskPriority.NORMAL));
    }

    // ── deadline ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("deadline 未超时不标记 missed")
    void withinDeadlineNotMissed() {
        TickContract c = TickContract.create(500);
        assertFalse(c.isPastDeadline());
        var m = c.complete();
        assertFalse(m.missedDeadline());
    }

    @Test
    @DisplayName("超期 tick 被标记 missedDeadline")
    void overdueTickIsMissed() throws Exception {
        TickContract c = TickContract.create(1); // 1ms 周期，必然超期
        Thread.sleep(20);
        assertTrue(c.isPastDeadline());
        assertEquals(0, c.remainingMs());
        var m = c.complete();
        assertTrue(m.missedDeadline());
    }

    @Test
    @DisplayName("remainingMs 不为负")
    void remainingNeverNegative() {
        TickContract c = TickContract.create(50);
        assertTrue(c.remainingMs() >= 0);
        assertTrue(c.remainingMs() <= 50);
    }

    // ── awaitAllTasks 屏障 ─────────────────────────────────────────────────

    @Test
    @DisplayName("awaitAllTasks 在任务完成后返回 true")
    void awaitReturnsTrueWhenDone() throws Exception {
        TickContract c = TickContract.create(50);
        CountDownLatch done = new CountDownLatch(1);
        c.submitTask("async", done::countDown, TickContract.TaskPriority.NORMAL);

        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            c.tasks().get(0).run(c);
        });
        worker.start();

        assertTrue(c.awaitAllTasks(2000), "任务应能在超时前完成");
        done.await(1, TimeUnit.SECONDS);
        worker.join();
    }

    @Test
    @DisplayName("awaitAllTasks 超时返回 false 而非永久阻塞")
    void awaitTimesOut() {
        TickContract c = TickContract.create(50);
        // 提交一个永不结束的任务（不 run，故保持 CREATED）
        c.submitTask("stuck", () -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, TickContract.TaskPriority.NORMAL);

        long start = System.currentTimeMillis();
        boolean drained = c.awaitAllTasks(100);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(drained, "未完成的任务应返回 false");
        assertTrue(elapsed < 3000, "await 应在超时后返回，不得永久等待");
    }

    // ── TickEngine：单线程端到端 ───────────────────────────────────────────

    @Test
    @DisplayName("TickEngine 完整跑通一次 tick")
    void engineRunsFullTick() {
        var rt = org.loader.runtime.kernel.Runtime.create("tick-test");
        try {
            rt.start();
            var scope = rt.rootScope().createChild("minecraft");
            scope.transitionTo(org.loader.runtime.kernel.LifecycleState.RESOLVED);
            scope.transitionTo(org.loader.runtime.kernel.LifecycleState.LOADED);
            scope.transitionTo(org.loader.runtime.kernel.LifecycleState.INITIALIZED);
            scope.transitionTo(org.loader.runtime.kernel.LifecycleState.REGISTERED);
            scope.transitionTo(org.loader.runtime.kernel.LifecycleState.RUNNING);

            List<String> stages = Collections.synchronizedList(new ArrayList<>());
            try (var engine = new TickEngine("engine", scope, 50)) {
                engine.onPreTick((c, p) -> stages.add("PRE"));
                engine.onSchedule((c, p) -> stages.add("SCHEDULE"));
                engine.onCoreTick((c, p) -> stages.add("CORE"));
                engine.onAsyncCompletion((c, p) -> stages.add("ASYNC"));
                engine.onPostTick((c, p) -> stages.add("POST"));
                engine.onTickEnd((c, p) -> stages.add("END"));

                var contract = engine.beginTick();
                engine.advanceToSchedule(contract);
                engine.advanceToCoreTick(contract);
                engine.runPendingTasks(contract);
                var metrics = engine.endTick(contract);

                assertEquals(List.of("PRE", "SCHEDULE", "CORE", "ASYNC", "POST", "END"), stages);
                assertEquals(TickContract.TickPhase.TICK_END, metrics.finalPhase());
                assertFalse(metrics.missedDeadline());
                assertEquals(1, engine.currentTick());
            }
        } finally {
            try {
                rt.close();
            } catch (Exception ignored) {
                // 测试清理
            }
        }
    }

    @Test
    @DisplayName("TickEngine 拒绝跨线程推进 tick")
    void engineRejectsCrossThreadAdvance() throws Exception {
        var rt = org.loader.runtime.kernel.Runtime.create("tick-thread-test");
        try {
            rt.start();
            var scope = rt.rootScope().createChild("minecraft");
            try (var engine = new TickEngine("engine", scope, 50)) {
                engine.beginTick(); // 绑定当前线程

                AtomicBoolean rejected = new AtomicBoolean(false);
                Thread other = new Thread(() -> {
                    try {
                        engine.beginTick();
                    } catch (IllegalStateException e) {
                        rejected.set(true);
                    }
                });
                other.start();
                other.join();

                assertTrue(rejected.get(),
                        "tick 必须绑定单一驱动线程（并行化前的不变量）");
            }
        } finally {
            try {
                rt.close();
            } catch (Exception ignored) {
                // 测试清理
            }
        }
    }

    @Test
    @DisplayName("runOnNextTick 在下一个 tick 执行")
    void runOnNextTickExecutesNextTick() {
        var rt = org.loader.runtime.kernel.Runtime.create("tick-next-test");
        try {
            rt.start();
            var scope = rt.rootScope().createChild("minecraft");
            AtomicInteger ran = new AtomicInteger();
            try (var engine = new TickEngine("engine", scope, 50)) {
                engine.runOnNextTick(ran::incrementAndGet);

                //第一个 tick 执行排队的 runOnNextTick
                var c1 = engine.beginTick();
                engine.endTick(c1);
                assertEquals(1, ran.get(), "排队工作应在下一个 tick 执行");

                // 第二个 tick 不应重复执行
                var c2 = engine.beginTick();
                engine.endTick(c2);
                assertEquals(1, ran.get(), "runOnNextTick 只执行一次");
            }
        } finally {
            try {
                rt.close();
            } catch (Exception ignored) {
                // 测试清理
            }
        }
    }

    @Test
    @DisplayName("引擎记录 tick 指标")
    void engineRecordsMetrics() {
        var rt = org.loader.runtime.kernel.Runtime.create("tick-metrics-test");
        try {
            rt.start();
            var scope = rt.rootScope().createChild("minecraft");
            try (var engine = new TickEngine("engine", scope, 50)) {
                for (int i = 0; i < 5; i++) {
                    var c = engine.beginTick();
                    engine.advanceToSchedule(c);
                    engine.runPendingTasks(c);
                    engine.endTick(c);
                }

                assertEquals(5, engine.currentTick());
                assertEquals(5, engine.recentMetrics().size());
                assertNotNull(engine.diagnostics());
                assertTrue(engine.diagnostics().contains("tickCount=5"));
            }
        } finally {
            try {
                rt.close();
            } catch (Exception ignored) {
                // 测试清理
            }
        }
    }

    @Test
    @DisplayName("关闭引擎后拒绝 beginTick")
    void closedEngineRejectsTick() {
        var rt = org.loader.runtime.kernel.Runtime.create("tick-closed-test");
        try {
            rt.start();
            var scope = rt.rootScope().createChild("minecraft");
            var engine = new TickEngine("engine", scope, 50);
            engine.close();
            assertTrue(engine.isClosed());
            assertThrows(IllegalStateException.class, engine::beginTick);
        } finally {
            try {
                rt.close();
            } catch (Exception ignored) {
                // 测试清理
            }
        }
    }

    // ── 常量与预算 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("TPS 与周期常量自洽")
    void constantsAreConsistent() {
        assertEquals(20, TickContract.DEFAULT_TPS);
        assertEquals(50, TickContract.DEFAULT_TICK_PERIOD_MS);
        assertEquals(1000L / TickContract.DEFAULT_TPS, TickContract.DEFAULT_TICK_PERIOD_MS);
    }

    @Test
    @DisplayName("TickMetrics 可计算耗时与deadline")
    void metricsComputeDuration() {
        var m = new TickContract.TickMetrics(
                1L, 0L, 35_000_000L, TickContract.TickPhase.TICK_END,
                2, 2, false, null);
        assertEquals(35, m.durationMs());
        assertTrue(m.metDeadline(50));
        assertFalse(m.metDeadline(30));
    }
}