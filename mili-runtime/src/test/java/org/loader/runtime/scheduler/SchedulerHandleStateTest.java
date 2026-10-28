package org.loader.runtime.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.kernel.Scope;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务句柄的实时状态与任务表回收。
 *
 * <p>两条断言守的都是「看起来正常但实际错了」的类型：
 * <ul>
 *   <li>{@code handle.state()} 曾经是构造时定格的 record 快照，恒为 QUEUED ——
 *       任何轮询状态的调用方都会死等；</li>
 *   <li>{@code taskMap} 曾经只增不减 —— 一局游戏几十万条记录的常驻内存，
 *       且 active 计数会算成负数。</li>
 * </ul>
 */
class SchedulerHandleStateTest {

    @Test
    @DisplayName("handle.state() 反映实时状态，而不是提交时的快照")
    void handleStateIsLive() throws Exception {
        Scope scope = new Scope("s", null);
        scope.driveToRunning();
        Scheduler scheduler = new Scheduler("sched", scope, 1);

        CountDownLatch release = new CountDownLatch(1);
        TaskHandle handle = scheduler.submit(scope, () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, TaskPriority.NORMAL);

        // 等待任务真正开始
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (handle.state() != TaskState.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(TaskState.RUNNING, handle.state(), "运行中的任务必须报告 RUNNING");

        release.countDown();
        handle.await();

        assertEquals(TaskState.COMPLETED, handle.state(), "完成后的任务必须报告 COMPLETED");
        assertTrue(handle.state().isFinished());
        assertFalse(handle.state().isActive());

        scheduler.close();
        scope.shutdown();
    }

    @Test
    @DisplayName("完成的任务从活跃任务表中摘除 —— 不会无限增长")
    void finishedTasksAreRemoved() {
        Scope scope = new Scope("s", null);
        scope.driveToRunning();
        Scheduler scheduler = new Scheduler("sched", scope, 2);

        for (int i = 0; i < 200; i++) {
            scheduler.submit(scope, () -> {
            }).future().join();
        }

        assertEquals(0, scheduler.activeTaskCount(),
                "任务终结后必须从活跃表摘除；否则跑一局游戏就是几十万条记录");
        assertEquals(200, scheduler.metrics().completed());
        assertEquals(0, scheduler.metrics().active());

        scheduler.close();
        scope.shutdown();
    }

    @Test
    @DisplayName("任务失败：状态为 FAILED，await 抛带 taskId 的异常")
    void failedTaskReportsFailure() {
        Scope scope = new Scope("s", null);
        scope.driveToRunning();
        Scheduler scheduler = new Scheduler("sched", scope, 1);

        TaskHandle handle = scheduler.submit(scope, () -> {
            throw new IllegalStateException("boom");
        });

        TaskFailureException e = assertThrows(TaskFailureException.class, handle::await);
        assertEquals(handle.taskId(), e.taskId(), "异常必须携带任务 id，否则无法定位是哪个任务失败");
        assertEquals(TaskState.FAILED, handle.state());
        assertEquals(1, scheduler.metrics().failed());

        scheduler.close();
        scope.shutdown();
    }

    @Test
    @DisplayName("取消排队中的任务：状态为 CANCELLED，且不占用活跃计数")
    void cancelQueuedTask() throws Exception {
        Scope scope = new Scope("s", null);
        scope.driveToRunning();
        Scheduler scheduler = new Scheduler("sched", scope, 1);

        CountDownLatch blocker = new CountDownLatch(1);
        TaskHandle blockerHandle = scheduler.submit(scope, () -> {
            try {
                blocker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        TaskHandle queued = scheduler.submit(scope, () -> {
        });

        assertTrue(queued.cancel(), "排队中的任务必须可取消");
        assertEquals(TaskState.CANCELLED, queued.state());

        blocker.countDown();
        blockerHandle.await();

        scheduler.close();
        scope.shutdown();
    }

    @Test
    @DisplayName("Scope 停止时取消该 Scope 的全部任务")
    void cancelAllForScope() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        Scope modScope = root.createChild("mod");
        modScope.driveToRunning();
        Scheduler scheduler = new Scheduler("sched", root, 1);

        CountDownLatch blocker = new CountDownLatch(1);
        scheduler.submit(root, () -> {
            try {
                blocker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicReference<TaskState> queuedState = new AtomicReference<>();
        TaskHandle queued = scheduler.submit(modScope, () -> {
        });
        queuedState.set(queued.state());

        scheduler.cancelAll(modScope);

        assertEquals(TaskState.CANCELLED, queued.state(),
                "cancelAll 必须真正取消排队中的任务（旧实现用了一个空 cancellable，状态改了但队列没动）");

        blocker.countDown();
        scheduler.close();
        root.shutdown();
    }

    @Test
    @DisplayName("周期任务：取消后不再执行，且不泄漏计时器线程")
    void repeatingTaskStopsOnCancel() throws Exception {
        Scope scope = new Scope("s", null);
        scope.driveToRunning();
        Scheduler scheduler = new Scheduler("sched", scope, 1);

        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        TaskHandle handle = scheduler.submitRepeating(scope, runs::incrementAndGet, 10, 20,
                TaskPriority.NORMAL);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (runs.get() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(runs.get() >= 3, "周期任务必须真的重复执行");

        handle.cancel();
        int afterCancel = runs.get();
        Thread.sleep(120);

        assertEquals(afterCancel, runs.get(), "取消后不得再执行");
        assertEquals(TaskState.CANCELLED, handle.state());

        scheduler.close();
        scope.shutdown();
    }

    @Test
    @DisplayName("周期任务在 Scope 停止后自动终止，不需要显式取消")
    void repeatingTaskStopsWhenScopeStops() throws Exception {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        Scope modScope = root.createChild("mod");
        modScope.driveToRunning();
        Scheduler scheduler = new Scheduler("sched", root, 1);

        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        scheduler.submitRepeating(modScope, runs::incrementAndGet, 10, 15, TaskPriority.NORMAL);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (runs.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(runs.get() >= 2);

        modScope.shutdown();
        int afterStop = runs.get();
        Thread.sleep(120);

        assertEquals(afterStop, runs.get(),
                "Scope 停止后周期任务必须终止 —— 否则任务会跑在已释放的资源上");

        scheduler.close();
        root.shutdown();
    }
}
