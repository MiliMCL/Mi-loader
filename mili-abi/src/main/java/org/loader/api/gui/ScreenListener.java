package org.loader.api.gui;

/**
 * 屏幕事件监听器 —— 按 {@link ScreenSpec} 内的 widget id 回调。
 *
 * <h2>回调线程</h2>
 * 所有回调都发生在 Minecraft 客户端主线程（渲染/tick 线程）——
 * 与 GUI 的天然线程一致。Mod 在回调里可以直接访问客户端状态，
 * 但不应执行耗时操作（卡帧就是卡游戏）。
 *
 * <h2>默认实现全部为空操作</h2>
 * Mod 只关心的事件才覆写 —— 一个只有按钮的配置界面不需要
 * 写五个空方法。
 */
public interface ScreenListener {

    /** 屏幕构建完成、即将显示。此时 widget 已就绪。 */
    default void onOpen() {
    }

    /** 按钮被点击。 */
    default void onClick(String widgetId) {
    }

    /** 文本框内容变化（每次键入/删除触发，值为当前完整内容）。 */
    default void onTextChange(String widgetId, String value) {
    }

    /** 复选框切换。 */
    default void onToggle(String widgetId, boolean checked) {
    }

    /**
     * 屏幕关闭（完成按钮或 Esc）。{@code cancelled} 表示用户按 Esc 退出 ——
     * 与「显式确认后关闭」的语义区分留给 Mod 自己解释。
     */
    default void onClose(boolean cancelled) {
    }
}
