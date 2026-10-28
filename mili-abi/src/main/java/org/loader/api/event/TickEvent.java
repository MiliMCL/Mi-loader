package org.loader.api.event;

/**
 * 平台 tick 事件 —— 每个游戏 tick 发布两次（{@link TickPhase#START} 与 {@link TickPhase#END}）。
 *
 * <h2>订阅方式</h2>
 * <pre>{@code
 * context.events().subscribe(TickEvent.class, e -> {
 *     if (e.phase() == TickPhase.END) {
 *         // 本 tick 已结束，可以安全统计
 *     }
 * });
 * }</pre>
 *
 * <h2>线程约束</h2>
 * 事件在<b>游戏主线程</b>上同步发布。回调里做重活会直接吃掉 tick 预算
 * （表现为 TPS 下降、界面卡顿），耗时工作请通过
 * {@code context.scheduler()} 移出主线程。
 *
 * <h2>为什么是 record</h2>
 * tick 事件每秒产生 40 个实例。record 的字段是不可变的 final，
 * 分配成本与一个普通对象相同，但省掉了 getter 的间接层；
 * 更重要的是它无法被监听器改写 —— 一个监听器改了 tick 号，
 * 后续监听器就会看到错的数据，而这种 bug 极难复现。
 *
 * @param tick         单调递增的 tick 序号，从 1 开始
 * @param phase        该事件处于 tick 的哪个边界
 * @param durationNanos {@link TickPhase#END} 时为上一 tick 的耗时；
 *                      {@link TickPhase#START} 时恒为 0
 */
public record TickEvent(long tick, TickPhase phase, long durationNanos) {

    public TickEvent {
        if (phase == null) {
            throw new IllegalArgumentException("phase 不能为 null");
        }
    }

    /** 上一 tick 的耗时（毫秒）；{@link TickPhase#START} 时恒为 0。 */
    public double durationMs() {
        return durationNanos / 1_000_000.0;
    }

    /** 是否为 tick 结束事件。 */
    public boolean isEnd() {
        return phase == TickPhase.END;
    }
}
