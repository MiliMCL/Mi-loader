package org.loader.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.loader.runtime.scheduler.TaskHandle;
import org.loader.runtime.scheduler.TaskState;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mod 测试基类 —— 管理 {@link MiliTestRuntime} 的生命周期。
 *
 * <p>每个测试方法获得一个全新的 Runtime（独立 Scope 树、独立调度器状态），
 * 测试结束无论成败都会关闭 —— 泄漏的测试会在这里暴露为关闭失败。
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * class MyModTest extends MiliTest {
 *     @Test void myScenario() {
 *         var mod = mili().installMod("mymod");
 *         // 用 mod.scope() / mod.scheduler() / mod.eventBus() 编排场景
 *     }
 * }
 * }</pre>
 */
public abstract class MiliTest {

    private MiliTestRuntime runtime;

    @BeforeEach
    final void setUpMiliRuntime() {
        runtime = MiliTestRuntime.create();
    }

    @AfterEach
    final void tearDownMiliRuntime() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }

    /** 当前测试的运行时。 */
    protected final MiliTestRuntime mili() {
        if (runtime == null) {
            throw new IllegalStateException("MiliTestRuntime 尚未创建或已关闭");
        }
        return runtime;
    }

    // ── 常用断言（来自真实 TaskState，不做包装）────────────────────────

    /** 断言任务已完成（COMPLETED）。 */
    protected static void assertCompleted(TaskHandle handle) {
        assertEquals(TaskState.COMPLETED, handle.state(),
                "任务应完成，实际 " + handle.state());
    }

    /** 断言任务已被取消（CANCELLED）。 */
    protected static void assertCancelled(TaskHandle handle) {
        assertEquals(TaskState.CANCELLED, handle.state(),
                "任务应取消，实际 " + handle.state());
    }

    /** 断言任务已失败（FAILED）。 */
    protected static void assertFailed(TaskHandle handle) {
        assertEquals(TaskState.FAILED, handle.state(),
                "任务应失败，实际 " + handle.state());
    }
}
