package org.loader.api.input;

/**
 * 按键绑定注册句柄 —— 用于注销。
 */
public interface KeyBinding {

    /** 绑定标识（即 spec.id()）。 */
    String id();

    /**
     * 注销：解除轮询、从控件界面移除（下次打开时不再显示）。
     * 重复注销是无操作。
     */
    void unregister();
}
