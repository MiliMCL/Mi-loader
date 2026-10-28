package org.loader.runtime.tick;

import org.loader.api.tick.TickService;
import org.loader.runtime.kernel.Scope;

/**
 * 没有 tick 源时使用的 {@link TickService} 实现。
 *
 * <h2>为什么不是返回 null</h2>
 * 返回 null 会让 {@code ctx.tick().runNextTick(...)} 变成 NPE，
 * 堆栈指向 Mod 自己的代码 —— 看起来像 Mod 写错了，实际是环境没有 tick 源
 * （专用服务器未进入世界、纯 JVM 测试、平台尚未接管 tick）。
 *
 * <p>返回本实现后，{@link #isAvailable()} 如实回答 {@code false}，
 * 提交时抛出带明确原因的异常，Mod 可以据此降级 ——
 * 而不是靠猜。
 */
public final class NoTickService implements TickService {

    private final Scope scope;

    public NoTickService(Scope scope) {
        this.scope = scope;
    }

    @Override
    public long currentTick() {
        return 0L;
    }

    @Override
    public TickTask runNextTick(Runnable work) {
        throw unavailable();
    }

    @Override
    public TickTask runAfterTicks(int ticks, Runnable work) {
        throw unavailable();
    }

    @Override
    public TickTask runEveryTick(Runnable work) {
        throw unavailable();
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    private IllegalStateException unavailable() {
        String reason = scope != null && scope.isStopped()
                ? "Mod 已停止（scope=" + scope.id() + "）"
                : "当前环境没有 tick 源";
        return new IllegalStateException(
                "TickService 不可用：" + reason + "。\n"
                        + "  客户端：进入世界（或至少到主菜单 tick 循环）之后才有 tick。\n"
                        + "  服务端：世界启动之前没有 tick。\n"
                        + "  纯 JVM 测试：MiliTestRuntime 没有 tick 引擎，"
                        + "请直接调用被测方法或用 Scheduler。");
    }
}
