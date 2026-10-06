package org.loader.api.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕规格 —— 一个客户端界面的<b>纯数据描述</b>。
 *
 * <h2>为什么是数据而不是控件对象</h2>
 * ABI 里不允许出现任何 Minecraft 类坐标（否则 ABI 与游戏版本绑定）。
 * 控件对象（Button/EditBox…）是 Minecraft 类型，一旦进入 API 签名，
 * 版本升级就会成批破坏 Mod。因此 Mili 的 GUI 走「声明式」：
 * Mod 描述「要什么界面」，平台负责「怎么渲染」——
 * 与 {@code @MiliInject} 把「改哪里」和「怎么改」分离是同一个思路。
 *
 * <pre>
 * ScreenSpec spec = ScreenSpec.builder("mymod:config", "我的模组设置")
 *         .size(240, 160)
 *         .label("title", "常规", 12, 12)
 *         .textField("name", 12, 32, 140, "默认值")
 *         .checkbox("enabled", "启用", 12, 60, true)
 *         .button("done", "完成", 150, 130, 70, 20)
 *         .build();
 * ScreenService.open(spec, listener);
 * </pre>
 *
 * <h2>坐标系</h2>
 * 与 Minecraft GUI 一致：原点在屏幕左上角，x 向右、y 向下，单位为像素
 * （GUI 缩放后的逻辑像素）。{@code size} 是内容区域大小，不是全屏 ——
 * 居中由平台负责。
 *
 * <h2>widget id</h2>
 * 每个 widget 必须有 id，事件（点击、输入、切换）按 id 回调到
 * {@link ScreenListener}。id 在同一 spec 内必须唯一 —— 重复 id
 * 会让「点了哪个按钮」变得不可判定，构建时直接报错。
 */
public final class ScreenSpec {

    /** widget 的公共形状 —— 平台按具体类型分别渲染。 */
    public sealed interface Widget permits Button, Label, TextField, Checkbox {
        /** 在 spec 内唯一的标识；事件按它回调。 */
        String id();
    }

    /** 按钮。点击触发 {@code ScreenListener.onClick(id)}。 */
    public record Button(String id, String label, int x, int y, int width, int height)
            implements Widget {
    }

    /** 静态文本。不产生事件。 */
    public record Label(String id, String text, int x, int y) implements Widget {
    }

    /** 文本框。输入变化触发 {@code ScreenListener.onTextChange(id, value)}。 */
    public record TextField(String id, String initial, int x, int y,
                            int width, int height, int maxLength) implements Widget {
    }

    /** 复选框。切换触发 {@code ScreenListener.onToggle(id, checked)}。 */
    public record Checkbox(String id, String label, int x, int y,
                           boolean initial) implements Widget {
    }

    private final String id;
    private final String title;
    private final int width;
    private final int height;
    private final List<Widget> widgets;

    private ScreenSpec(String id, String title, int width, int height,
                       List<Widget> widgets) {
        this.id = id;
        this.title = title;
        this.width = width;
        this.height = height;
        this.widgets = List.copyOf(widgets);
    }

    /** spec 标识，格式 {@code modid:name}。 */
    public String id() {
        return id;
    }

    /** 标题文本。 */
    public String title() {
        return title;
    }

    /** 内容区域宽（逻辑像素）。 */
    public int width() {
        return width;
    }

    /** 内容区域高（逻辑像素）。 */
    public int height() {
        return height;
    }

    /** 全部 widget（只读，按添加顺序）。 */
    public List<Widget> widgets() {
        return widgets;
    }

    public static Builder builder(String id, String title) {
        return new Builder(id, title);
    }

    public static final class Builder {
        private final String id;
        private final String title;
        private int width = 256;
        private int height = 180;
        private final List<Widget> widgets = new ArrayList<>();
        private final java.util.Set<String> ids = new java.util.HashSet<>();

        private Builder(String id, String title) {
            if (id == null || id.isBlank() || !id.contains(":")) {
                throw new IllegalArgumentException(
                        "ScreenSpec.id 必须是 modid:name 形式: \"" + id + "\"");
            }
            this.id = id;
            this.title = title != null ? title : "";
        }

        /** 内容区域尺寸。默认 256×180。 */
        public Builder size(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException(
                        "ScreenSpec 尺寸必须为正: " + width + "x" + height);
            }
            this.width = width;
            this.height = height;
            return this;
        }

        /** 添加按钮。推荐尺寸 200×20（原版按钮的常规大小）。 */
        public Builder button(String id, String label, int x, int y, int width, int height) {
            return add(new Button(id, label, x, y, width, height));
        }

        /** 添加静态文本。 */
        public Builder label(String id, String text, int x, int y) {
            return add(new Label(id, text, x, y));
        }

        /** 添加文本框（maxLength 默认 64）。 */
        public Builder textField(String id, String initial, int x, int y,
                                 int width, int height) {
            return add(new TextField(id, initial, x, y, width, height, 64));
        }

        /** 添加文本框（显式 maxLength）。 */
        public Builder textField(String id, String initial, int x, int y,
                                 int width, int height, int maxLength) {
            return add(new TextField(id, initial, x, y, width, height, maxLength));
        }

        /** 添加复选框。 */
        public Builder checkbox(String id, String label, int x, int y, boolean initial) {
            return add(new Checkbox(id, label, x, y, initial));
        }

        public ScreenSpec build() {
            return new ScreenSpec(id, title, width, height, widgets);
        }

        private Builder add(Widget widget) {
            if (!ids.add(widget.id())) {
                // 重复 id 的事件无法归属 —— 与其让 Mod 收到含糊的回调，
                // 不如构建时指名道姓。
                throw new IllegalArgumentException(
                        "ScreenSpec 内 widget id 重复: \"" + widget.id() + "\"");
            }
            widgets.add(widget);
            return this;
        }
    }
}
