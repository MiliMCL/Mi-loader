package org.loader.api.gui;

/**
 * 客户端屏幕服务 —— Mod 打开自定义界面的唯一入口。
 *
 * <h2>为什么是 Holder 而不是直接实现</h2>
 * 与 {@code Mili.registerProvider} 相同的模式：ABI 声明形状，
 * 平台在客户端启动时装配实现。服务端 / 专用服务器上没有渲染器，
 * provider 未装配时调用会抛 {@link ScreenUnavailableException} ——
 * 让「在服务器上调了 open()」这种错误在第一时间显式失败，
 * 而不是静默什么都不发生。
 *
 * <h2>线程要求</h2>
 * {@link #open} 必须在客户端主线程调用（在注入回调、按键事件等
 * 客户端上下文里调用天然满足）。从其他线程调用会抛
 * {@link ScreenUnavailableException} 或由平台记日志拒绝 ——
 * 跨线程改屏幕状态在 Minecraft 里不是「不推荐」，是必崩。
 */
public final class ScreenService {

    /** 平台实现 —— 由集成模块在客户端启动时装配。 */
    public interface Provider {
        void open(ScreenSpec spec, ScreenListener listener);
        void closeCurrent();
        boolean isShowing();
    }

    private static volatile Provider provider;

    private ScreenService() {
    }

    /** 平台装配实现；传 null 恢复「不可用」状态。 */
    public static void registerProvider(Provider p) {
        provider = p;
    }

    /**
     * 打开（或替换当前）屏幕。
     *
     * @throws ScreenUnavailableException 无客户端渲染环境或当前屏幕
     *         已被外部占用为不可替换状态时
     */
    public static void open(ScreenSpec spec, ScreenListener listener) {
        Provider p = requireProvider();
        if (spec == null) {
            throw new IllegalArgumentException("spec 不能为 null");
        }
        p.open(spec, listener != null ? listener : new ScreenListener() {
        });
    }

    /** 关闭当前由本服务打开的屏幕（如有）。 */
    public static void closeCurrent() {
        Provider p = provider;
        if (p != null) {
            p.closeCurrent();
        }
    }

    /** 当前是否显示着本服务打开的屏幕。 */
    public static boolean isShowing() {
        Provider p = provider;
        return p != null && p.isShowing();
    }

    private static Provider requireProvider() {
        Provider p = provider;
        if (p == null) {
            throw new ScreenUnavailableException(
                    "ScreenService 未装配 —— 当前环境没有客户端渲染器"
                            + "（专用服务器 / 无头环境），或平台尚未启动到客户端阶段。");
        }
        return p;
    }
}
