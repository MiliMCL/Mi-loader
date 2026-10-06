package org.loader.api.input;

/**
 * 按键事件类型。
 *
 * <h2>语义边界（v1）</h2>
 * <ul>
 *   <li>{@link #PRESS} —— 由原版 {@code consumeClick()} 计数驱动，
 *       一次按键累计一次（连发按住只算一次，与原版行为一致）。</li>
 *   <li>{@link #RELEASE} —— {@code isDown()} 的下降沿。</li>
 * </ul>
 *
 * <p><b>已知限制</b>：按键事件只在「游戏内」派发 —— 打开任意界面
 * （菜单、聊天、容器）时按键被该界面消费，不会产生 PRESS。
 * 这是原版按键系统的行为，不是平台缺陷。
 */
public enum KeyEventType {
    /** 键被按下（可能有多次计数 —— 原版点击队列语义）。 */
    PRESS,
    /** 键被释放（下降沿，一次）。 */
    RELEASE
}
