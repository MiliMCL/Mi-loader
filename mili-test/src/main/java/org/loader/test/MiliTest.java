package org.loader.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.loader.runtime.scheduler.TaskHandle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ── 常用断言（基于可观察行为）──────────────────────────────────────
    //
    // 为什么断言行为而不是只看 TaskHandle.state()：
    // 行为断言（await 不抛 / future 已取消 / await 抛）不依赖内部状态机的
    // 具体取值，对实现变更更稳健；而 state() 现在是实时读原子引用
    // （ABI 2 修复了「record 快照恒为 QUEUED」的缺陷），两者可以互相印证。
    // 需要精确断言状态时请直接用 state()。

    /** 断言任务已完成（await 正常返回）。 */
    protected static void assertCompleted(TaskHandle handle) {
        assertDoesNotThrow(() -> handle.await(),
                "任务应正常完成（await 不抛出）");
    }

    /** 断言任务已被取消（future 进入 cancelled 状态）。 */
    protected static void assertCancelled(TaskHandle handle) {
        assertTrue(handle.future().isCancelled(),
                "任务应取消（future.isCancelled）");
    }

    /** 断言任务已失败（await 以非取消异常收场）。 */
    protected static void assertFailed(TaskHandle handle) {
        assertThrows(RuntimeException.class, handle::await,
                "任务应失败（await 抛出执行异常）");
    }
}
