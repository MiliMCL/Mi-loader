package org.loader.api.event;

/**
 * 平台启动/关闭阶段。
 *
 * <p>与 {@link ModLifecycleEvent} 分开：那个描述「某个 Mod」，
 * 这个描述「平台整体」。Mod 想在「全部 Mod 初始化完成、游戏即将启动」
 * 这个时刻做一次全局工作（例如汇总注册内容、写一次报告），
 * 靠 Mod 自己的生命周期是拿不到这个时刻的。
 */
public enum PlatformStage {

    /** 平台开始引导，Mod 尚未初始化。 */
    BOOTSTRAPPING,

    /** 全部 Mod 的 initialize() 已返回，平台即将把控制权交给 Minecraft。 */
    READY,

    /** 平台开始关停，Mod 的 Scope 即将被释放。 */
    STOPPING,

    /** 全部资源已释放。 */
    STOPPED
}
