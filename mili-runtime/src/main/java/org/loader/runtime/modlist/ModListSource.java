package org.loader.runtime.modlist;

/**
 * Mod 列表数据源 —— 由 loader 装配，由客户端 GUI 消费。
 *
 * <h2>为什么是接口而不是静态字段</h2>
 * GUI 分发器住在 {@code mili-minecraft-integration}，loader 住在
 * {@code mili-loader}，依赖方向是 loader → integration，所以
 * integration 无法引用 loader 的类型。接口留在 runtime（两者都依赖它），
 * 装配动作由 loader 完成 —— 这是整个平台「契约在下、实现在上」的同一模式。
 *
 * <p>实现方必须保证：{@link #snapshot()} 在客户端渲染线程上被调用，
 * 并且必须是<b>无锁或极短持锁</b>的。列表界面每帧都会读它。
 */
@FunctionalInterface
public interface ModListSource {

    /** 空数据源 —— 装配前的安全默认值。 */
    ModListSource EMPTY = ModListSnapshot::empty;

    /** 拍一份当前快照。实现方不得返回 null。 */
    ModListSnapshot snapshot();
}
