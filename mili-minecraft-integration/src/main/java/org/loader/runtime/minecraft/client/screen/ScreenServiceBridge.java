package org.loader.runtime.minecraft.client.screen;

import org.loader.api.gui.ScreenListener;
import org.loader.api.gui.ScreenService;
import org.loader.api.gui.ScreenSpec;

/**
 * 屏幕服务桥 —— 把 ABI 的 {@link ScreenService} 接到集成实现上。
 *
 * <p>由 loader 在客户端启动装配期调用 {@code install()}；
 * 服务端环境不安装，{@code ScreenService.open()} 保持显式不可用。
 */
public final class ScreenServiceBridge {

    private ScreenServiceBridge() {
    }

    /** 装配（幂等）。客户端启动期调用。 */
    public static void install() {
        ScreenService.registerProvider(new ScreenService.Provider() {
            @Override
            public void open(ScreenSpec spec, ScreenListener listener) {
                ScreenHostDispatch.open(spec, listener);
            }

            @Override
            public void closeCurrent() {
                ScreenHostDispatch.closeRequested();
            }

            @Override
            public boolean isShowing() {
                return ScreenHostDispatch.isShowing();
            }
        });
    }
}
