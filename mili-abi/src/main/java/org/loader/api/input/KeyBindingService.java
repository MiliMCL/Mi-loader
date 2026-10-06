package org.loader.api.input;

import java.util.function.Consumer;

/**
 * 按键绑定服务 —— Mod 注册自定义按键的唯一入口。
 *
 * <h2>装配模式</h2>
 * 与 {@code ScreenService} 相同的 Holder 模式：集成模块在客户端
 * 启动时装配实现；服务端 / 未装配时 {@link #register} 抛
 * {@link KeyBindingUnavailableException} —— 显式失败优于静默无效。
 *
 * <h2>派发时机</h2>
 * 事件在客户端主线程、每个客户端 tick 轮询一次派发
 * （实现挂在 {@code Minecraft#tick} 的注入上）。回调里不要做重活。
 *
 * <h2>控件界面可见性</h2>
 * 注册的绑定会通过扩充 {@code Options.keyMappings} 出现在原版
 * 控制设置里（追加在原版按键之后）。重新绑定后生效无需重启。
 */
public final class KeyBindingService {

    /** 平台实现 —— 由集成模块装配。 */
    public interface Provider {
        /**
         * 注册一个绑定。
         *
         * @return 注册句柄，用于注销
         */
        KeyBinding register(KeyBindingSpec spec, Consumer<KeyEventType> handler);
    }

    private static volatile Provider provider;

    private KeyBindingService() {
    }

    /** 平台装配；传 null 恢复「不可用」。 */
    public static void registerProvider(Provider p) {
        provider = p;
    }

    /**
     * 注册按键绑定。
     *
     * @throws KeyBindingUnavailableException 客户端环境不可用
     * @throws IllegalArgumentException       spec 或 handler 非法
     */
    public static KeyBinding register(KeyBindingSpec spec,
                                      Consumer<KeyEventType> handler) {
        if (spec == null || handler == null) {
            throw new IllegalArgumentException("spec 与 handler 均不能为 null");
        }
        Provider p = provider;
        if (p == null) {
            throw new KeyBindingUnavailableException(
                    "KeyBindingService 未装配 —— 当前环境没有客户端输入系统"
                            + "（专用服务器 / 平台未启动到客户端阶段）。");
        }
        return p.register(spec, handler);
    }
}
