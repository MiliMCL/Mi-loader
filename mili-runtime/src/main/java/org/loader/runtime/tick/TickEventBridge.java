package org.loader.runtime.tick;

import org.loader.api.event.TickEvent;
import org.loader.api.event.TickPhase;
import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.service.EventBus;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 {@link TickEngine} 的 tick 边界发布到平台总线。
 *
 * <h2>它补的是哪一段断链</h2>
 * 在此之前，{@code TickEvent} / {@code ClientTickEvent} 这些事件类型
 * <b>只存在于定义里</b>：生产路径上零发布者，只有测试会 {@code post}。
 * 一个 Mod 写 {@code ctx.events().subscribe(TickEvent.class, ...)} 会订阅成功、
 * 编译通过、运行无异常 —— 然后永远收不到事件。
 *
 * <p>这不是配置问题：{@code TickEngine} 在 runtime 模块，事件总线在
 * integration 模块的另一条总线上，两边没有任何连接点。
 *
 * <h2>零订阅零成本</h2>
 * 每个 tick 会检查 {@link EventBus#hasListeners(Class)}。没人订阅时
 * 连事件对象都不分配 —— 20 TPS × 2 个事件 × 每帧一次分配，
 * 在长时间运行的客户端里是实打实的 GC 压力。
 *
 * <h2>线程</h2>
 * 全部回调都在游戏主线程（{@link TickEngine} 的驱动线程）上执行，
 * 与 Mod 对「tick 事件」的直觉一致。
 */
public final class TickEventBridge implements TickEngine.TickObserver, Resource {

    private final String id;
    private final Scope owner;
    private final EventBus bus;
    private final AtomicLong published = new AtomicLong();

    public TickEventBridge(String id, Scope owner, EventBus bus) {
        this.id = id;
        this.owner = owner;
        this.bus = bus;
    }

    /** 便捷构造：直接用平台总线。 */
    public static TickEventBridge install(TickEngine engine, Scope owner, EventBus bus) {
        TickEventBridge bridge = new TickEventBridge("tick-event-bridge", owner, bus);
        engine.addObserver(bridge);
        owner.registerResource(bridge);
        return bridge;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Scope owner() {
        return owner;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    private volatile boolean closed;

    @Override
    public void onTickStart(long tick, TickContract contract) {
        if (closed || !bus.hasListeners(TickEvent.class)) {
            return;
        }
        bus.post(new TickEvent(tick, TickPhase.START, 0L));
        published.incrementAndGet();
    }

    @Override
    public void onTickEnd(long tick, TickContract.TickMetrics metrics) {
        if (closed || !bus.hasListeners(TickEvent.class)) {
            return;
        }
        long duration = metrics == null ? 0L : metrics.durationNanos();
        bus.post(new TickEvent(tick, TickPhase.END, duration));
        published.incrementAndGet();
    }

    /** 已发布的事件数（诊断用）。 */
    public long publishedCount() {
        return published.get();
    }

    @Override
    public void close() {
        closed = true;
    }
}
