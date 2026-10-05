package org.loader.runtime.minecraft;

/**
 * 真实 Minecraft tick 入口契约。
 *
 * <p>Minecraft 26.2 的 tick 循环在游戏内部，Mili 无法在编译期引用它
 * （也<b>不应该</b> —— Minecraft 只能作为构建输入，不能成为平台源码依赖）。
 * 因此 tick 接入点是一个<b>窄接口</b>：注入层负责把游戏真实 tick 回调
 * 转发到 {@link TickBridge#beginTick()} / {@link #endTick()}。
 *
 * <p><b>为什么保留这个抽象</b>：不同接入方式（字节码注入、官方启动路径内嵌、
 * 测试替身）共享同一契约，使 Tick 正确性可以在<b>不启动 Minecraft</b> 的
 * 情况下被完整验证 —— 这是 Phase 6 单线程正确性测试的基础。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>{@link #onTickBegin()} 与 {@link #onTickEnd()} 必须在同一线程成对调用</li>
 *   <li>该线程即 Minecraft 主线程</li>
 *   <li>不得在 begin/end 之间抛出到游戏主循环（异常必须被捕获并记录）</li>
 * </ul>
 */
public interface MinecraftTickSource {

    /**
     * 由注入层调用：一次真实 tick 开始。
     *
     * @return 本 tick 的执行契约；{@link TickBridge#beginTick()} 在桥已关闭时
     *         抛异常而非返回 null
     */
    org.loader.runtime.tick.TickContract beginTick();

    /**
     * 由注入层调用：一次真实 tick 结束。
     */
    void endTick();

    /**
     * 该来源是否已就绪（游戏已初始化到可 tick 的状态）。
     */
    boolean isReady();

    /**
     * 诊断信息：接入点名称、已执行 tick 数等。
     */
    String describe();
}