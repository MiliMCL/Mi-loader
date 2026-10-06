package org.loader.runtime.minecraft.client.input;

import org.loader.api.input.KeyBinding;
import org.loader.api.input.KeyBindingService;
import org.loader.api.input.KeyEventType;
import org.loader.api.input.KeyBindingSpec;

import java.util.function.Consumer;

/**
 * 按键服务桥 —— 把 ABI 的 {@link KeyBindingService} 接到集成实现上。
 *
 * <p>由 loader 在客户端启动装配期调用 {@code install()}；
 * 服务端环境不安装，注册调用保持显式不可用。
 */
public final class KeyBindingServiceBridge {

    private KeyBindingServiceBridge() {
    }

    /** 装配（幂等）。客户端启动期调用。 */
    public static void install() {
        KeyBindingService.registerProvider(new KeyBindingService.Provider() {
            @Override
            public KeyBinding register(KeyBindingSpec spec,
                                       Consumer<KeyEventType> handler) {
                return KeyBindingDispatch.register(spec, handler);
            }
        });
    }
}
