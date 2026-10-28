package org.loader.api.event;

/**
 * Tick 事件所处的阶段。
 *
 * <h2>为什么需要阶段而不是只有「一个 tick」</h2>
 * 「每 tick 做一次」在不同场景下要求的时机完全不同：
 * <ul>
 *   <li><b>读取本 tick 的输入/世界状态</b> —— 必须在 {@link #START}，
 *       此时世界还没被本 tick 的实体逻辑改过。</li>
 *   <li><b>统计、上报、写盘</b> —— 必须在 {@link #END}，
 *       此时本 tick 的全部状态变更都已落地。</li>
 * </ul>
 * 只提供一个无阶段的事件，等于逼 Mod 用「上一次 tick 的数据」做本 tick 的判断，
 * 而且没有任何办法表达「我要在实体 tick 之前」。
 *
 * <h2>当前只暴露两个阶段</h2>
 * 引擎内部有 7 个阶段（PRE_TICK / SCHEDULE / CORE_TICK / ASYNC_COMPLETION /
 * POST_TICK / TICK_END），但对 Mod 暴露中间阶段会立刻产生
 * 「CORE_TICK 到底包不包含实体 tick」这类依赖 Minecraft 内部实现的约定。
 * 这里只承诺两个语义明确、与 Minecraft 版本无关的边界。
 */
public enum TickPhase {

    /** 一个 tick 刚开始，世界尚未被本 tick 的逻辑修改。 */
    START,

    /** 一个 tick 已结束，本 tick 的全部状态变更都已落地。 */
    END
}
