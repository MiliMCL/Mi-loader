package org.loader.api.input;

/**
 * 按键绑定规格。
 *
 * @param id          绑定标识，格式 {@code modid:action}；同时也是原版
 *                    {@code KeyMapping} 的名称键（控件界面据此显示翻译条目）
 * @param category    分类显示名（v1 为纯文本；原版分类用翻译键，模组分类
 *                    会被追加到原版分类列表尾部）
 * @param defaultKey  默认按键描述。支持单个可打印字符（{@code "R"}、
 *                    {@code "1"}），或 {@code GLFW} 键码的十进制形式
 *                    （{@code "GLFW:82"}）。未知形式在注册时报错。
 */
public record KeyBindingSpec(String id, String category, String defaultKey) {

    public KeyBindingSpec {
        if (id == null || !id.contains(":") || id.indexOf(':') == 0) {
            throw new IllegalArgumentException(
                    "KeyBindingSpec.id 必须是 modid:action 形式: \"" + id + "\"");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("KeyBindingSpec.category 不能为空");
        }
        if (defaultKey == null || defaultKey.isBlank()) {
            throw new IllegalArgumentException("KeyBindingSpec.defaultKey 不能为空");
        }
    }

    /** 解析默认键的 GLFW 键码（KEYSYM 值域）。非法描述时抛异常。 */
    public int glfwKeyCode() {
        String k = defaultKey.trim();
        if (k.regionMatches(true, 0, "GLFW:", 0, 5)) {
            try {
                return Integer.parseInt(k.substring(5).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "GLFW 键码非法: \"" + defaultKey + "\"");
            }
        }
        if (k.length() == 1) {
            char c = Character.toUpperCase(k.charAt(0));
            if (c >= 'A' && c <= 'Z') {
                return c;   // GLFW KEYSYM 与 ASCII 大写字母一致
            }
            if (c >= '0' && c <= '9') {
                return c;   // 数字键码与其字符值一致
            }
        }
        throw new IllegalArgumentException(
                "无法解析默认按键: \"" + defaultKey + "\""
                        + "（支持单个字母/数字，或 GLFW:<键码> 形式）");
    }
}
