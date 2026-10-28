package net.minecraft.client.gui.components;

/**
 * 测试专用最小 Button 壳 —— 只为让测试类路径上存在真实的
 * {@code Button$OnPress} 坐标（生产里是同名原版类的公开嵌套接口）。
 *
 * <p>字段与方法刻意精简：回归测试只关心 OnPress 回调的可达性。
 */
public class Button {

    /** 与原版签名一致的 {@code Button.OnPress}。 */
    public interface OnPress {
        void onPress(Button button);
    }
}
