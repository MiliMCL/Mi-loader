package org.loader.api.event;

import org.loader.api.lifecycle.LifecycleState;

/**
 * Mod 生命周期状态迁移事件。
 *
 * <h2>与 {@code ModContext.lifecycle().addListener} 的区别</h2>
 * 两者都观察状态迁移，但可见范围不同：
 * <ul>
 *   <li>{@code lifecycle().addListener} —— 只收到<b>自己</b>的迁移。</li>
 *   <li>本事件 —— 发布在平台总线上，<b>任何</b> Mod 都能收到
 *       <b>任意</b> Mod 的迁移。</li>
 * </ul>
 * 后者是「依赖 Mod 已经就绪」这类需求的正确工具：Mod B 依赖 Mod A，
 * 它需要知道 A 什么时候进入 RUNNING，而不是轮询。
 *
 * <h2>为什么事件里带 modId 而不是给每个 Mod 一个事件类型</h2>
 * 事件类型由编译器生成，无法按运行期数据（modId）分化。
 * 一个带 modId 字段的事件让订阅方自己过滤，也避免为 N 个 Mod
 * 生成 N 个类型。
 *
 * @param modId 发生迁移的 Mod id
 * @param from  迁移前状态
 * @param to    迁移后状态
 */
public record ModLifecycleEvent(String modId, LifecycleState from, LifecycleState to) {

    public ModLifecycleEvent {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId 不能为空");
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("from / to 不能为 null");
        }
    }

    /** 该 Mod 是否刚刚进入 {@link LifecycleState#RUNNING}。 */
    public boolean isStarted() {
        return to == LifecycleState.RUNNING;
    }

    /** 该 Mod 是否刚刚停止（进入终态）。 */
    public boolean isStopped() {
        return to.isTerminal();
    }
}
