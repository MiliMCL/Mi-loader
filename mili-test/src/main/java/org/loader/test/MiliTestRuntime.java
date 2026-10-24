package org.loader.test;

import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.mod.ModManifest;
import org.loader.runtime.scheduler.Scheduler;
import org.loader.runtime.service.EventBus;

import java.util.Objects;

/**
 * 纯 JVM 测试运行时 —— Mod 测试的入口夹具。
 *
 * <h2>测试哲学（对应 MD A4）</h2>
 * <ul>
 *   <li><b>真实实现优先</b>：{@link Runtime}/{@link Scope}/{@link Scheduler}/
 *       {@link EventBus} 全部是生产实现，不用 Mock —— Mock 掉的调度器测试不出
 *       取消传播，Mock 掉的 Scope 测试不出资源泄漏。只有 MC 桥接边界允许
 *       Stub，而本框架根本不触碰 MC 桥接（无 Minecraft 依赖）；</li>
 *   <li><b>无 Minecraft</b>：不启动游戏、不解析 MC JAR、不需要图形会话，
 *       任何装了 JDK 25 的机器都能跑；</li>
 *   <li><b>不进入 ABI</b>：本模块只作为测试依赖引入（{@code testImplementation}），
 *       生产模块不依赖它。</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * class MyModTest extends MiliTest {           // 基类管理生命周期
 *     @Test void modTaskDiesWithScope() {
 *         var fixture = mili().installMod("mymod");
 *         var handle = fixture.scheduler().submit(fixture.scope(), () -> heavyWork());
 *         fixture.close();                      // Scope 关闭 → 任务自动取消
 *         assertEquals(TaskState.CANCELLED, handle.state());
 *     }
 * }
 * }</pre>
 *
 * <p><b>虚拟 tick 的现状说明</b>：{@code TickEngine} 当前是真实墙钟驱动、
 * 单线程绑定模型，没有可步进的虚拟时间 API（审计 §0.2.5）。因此本框架
 * v1 不提供 tick 推进；tick 相关测试请直接在测试线程上驱动真实引擎，
 * 或等待动画时间基（C2）引入虚拟时钟后统一解决。
 */
public final class MiliTestRuntime implements AutoCloseable {

    private final Runtime runtime;

    private MiliTestRuntime(Runtime runtime) {
        this.runtime = runtime;
    }

    /** 创建并启动一个隔离的测试 Runtime。 */
    public static MiliTestRuntime create() {
        return create("mili-test");
    }

    /** 指定 id 创建并启动。 */
    public static MiliTestRuntime create(String id) {
        Runtime r = Runtime.create(Objects.requireNonNull(id, "id"));
        r.start();
        return new MiliTestRuntime(r);
    }

    /** 底层 Runtime（用于高级断言）。 */
    public Runtime runtime() {
        return runtime;
    }

    /** 根 Scope。 */
    public Scope rootScope() {
        return runtime.rootScope();
    }

    /** 建一个根下的测试 Scope（自动走完到 RUNNING 的合法迁移）。 */
    public Scope newScope(String id) {
        Scope scope = rootScope().createChild(Objects.requireNonNull(id, "id"));
        TestModFixture.toRunning(scope);
        return scope;
    }

    /**
     * 注册一个 fixture Mod：独立子 Scope + 绑定该 Scope 的真实
     * Scheduler 与 EventBus。关闭 fixture 即复现"Mod 卸载"的完整语义。
     */
    public TestModFixture installMod(String modId) {
        Objects.requireNonNull(modId, "modId");
        ModManifest manifest = ModManifest.of(modId, modId, "0.0.0.test");
        Scope scope = rootScope().createChild("test-mod:" + modId);
        return new TestModFixture(manifest, scope);
    }

    /** 绑定根 Scope 的共享 EventBus。 */
    public EventBus eventBus(String id) {
        return new EventBus(Objects.requireNonNull(id, "id"), rootScope());
    }

    /** 绑定根 Scope 的共享 Scheduler。 */
    public Scheduler scheduler(String id) {
        return new Scheduler(Objects.requireNonNull(id, "id"), rootScope());
    }

    /** 关闭 Runtime：逆序关闭所有资源（含各 fixture 的 Scope）。 */
    @Override
    public void close() {
        runtime.close();
    }
}
