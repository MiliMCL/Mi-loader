package org.loader.test;

import org.loader.runtime.kernel.Scope;
import org.loader.runtime.mod.Mod;
import org.loader.runtime.mod.ModManifest;
import org.loader.runtime.scheduler.Scheduler;
import org.loader.runtime.service.EventBus;

import java.util.Objects;

/**
 * 一个已安装的测试 Mod 夹具：Manifest + 独立 Scope + 绑定 Scope 的
 * Scheduler/EventBus。
 *
 * <h2>为什么 close() 有真实语义</h2>
 * 生产语义里 Mod 卸载 = Scope 关闭 = 任务取消 + 事件退订 + 资源释放。
 * 本夹具的 {@link #close()} 走的就是这条真实路径 —— 测试里
 * "close 后提交任务必须被拒绝、挂起任务必须被取消"不再是假设，
 * 而是被同一套代码保证的行为（A4 验收：Scope 自动清理）。
 */
public final class TestModFixture implements AutoCloseable {

    private final ModManifest manifest;
    private final Scope scope;
    private final Mod mod;
    private final Scheduler scheduler;
    private final EventBus eventBus;

    TestModFixture(ModManifest manifest, Scope scope) {
        this.manifest = manifest;
        this.scope = scope;
        TestModFixture.toRunning(scope);
        this.mod = new Mod(manifest, scope, getClass().getClassLoader());
        this.scheduler = new Scheduler("test-sched:" + manifest.id(), scope, 2);
        this.eventBus = new EventBus("test-bus:" + manifest.id(), scope);
    }

    /** 把 Scope 沿合法链推到 RUNNING（与 ModLoader.load 相同的迁移序列）。 */
    static void toRunning(Scope scope) {
        scope.transitionTo(org.loader.runtime.kernel.LifecycleState.RESOLVED);
        scope.transitionTo(org.loader.runtime.kernel.LifecycleState.LOADED);
        scope.transitionTo(org.loader.runtime.kernel.LifecycleState.INITIALIZED);
        scope.transitionTo(org.loader.runtime.kernel.LifecycleState.REGISTERED);
        scope.transitionTo(org.loader.runtime.kernel.LifecycleState.RUNNING);
    }

    public ModManifest manifest() {
        return manifest;
    }

    public Mod mod() {
        return mod;
    }

    public Scope scope() {
        return scope;
    }

    /** 绑定本 Mod Scope 的 Scheduler（2 线程，与生产 ModContext 一致）。 */
    public Scheduler scheduler() {
        return scheduler;
    }

    /** 绑定本 Mod Scope 的 EventBus。 */
    public EventBus eventBus() {
        return eventBus;
    }

    /** Mod 是否已被 Scope 关闭隔离。 */
    public boolean isClosed() {
        return mod.isClosed();
    }

    /**
     * 复现 Mod 卸载：关闭 EventBus 与 Scheduler，再关 Scope。
     * 幂等 —— 与 Scope.shutdown 的幂等语义一致。
     */
    @Override
    public void close() {
        Objects.requireNonNull(eventBus).close();
        scheduler.close();
        scope.shutdown();
    }
}
