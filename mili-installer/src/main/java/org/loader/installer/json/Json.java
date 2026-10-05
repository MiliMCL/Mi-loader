package org.loader.installer.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器 —— 仅依赖 JDK。
 *
 * <p>安装器刻意不引入 Gson/Jackson：整个模块必须能用单个 JDK 25 启动，
 * 任何第三方依赖都会破坏「下载即运行」的前提（用户手上可能只有 JRE）。
 *
 * <p>支持 RFC 8259 的全部合法 JSON：对象、数组、字符串、数字、布尔、null，
 * 以及反斜杠 u 形式的 Unicode 转义。数值统一存为 {@link Double}，调用方
 * 需要精确整数时自行转换（Mojang 元数据里的 size/sha1 不会超出 2^53 精度范围）。
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    /** 解析顶层 JSON 值。 */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw p.error("Trailing content after JSON value");
        }
        return value;
    }

    /** 解析并要求顶层为对象。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("Expected JSON object at top level, got "
                    + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        return (Map<String, Object>) v;
    }

    // ── 读取 ────────────────────────────────────────────────────────────────

    private Object readValue() {
        if (pos >= src.length()) {
            throw error("Unexpected end of input");
        }
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("Expected ',' or '}' but found '" + c + "'");
            }
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("Expected ',' or ']' but found '" + c + "'");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                if (c < 0x20) {
                    throw error("Unescaped control character U+" + Integer.toHexString(c) + " in string");
                }
                sb.append(c);
                continue;
            }
            char esc = next();
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> sb.append(readUnicodeEscape());
                default -> throw error("Invalid escape '\\" + esc + "'");
            }
        }
    }

    private char readUnicodeEscape() {
        if (pos + 4 > src.length()) {
            throw error("Truncated \\u escape");
        }
        String hex = src.substring(pos, pos + 4);
        pos += 4;
        try {
            return (char) Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw error("Invalid \\u escape '" + hex + "'");
        }
    }

    private Double readNumber() {
        int start = pos;
        if (peek() == '-' || peek() == '+') {
            pos++;
        }
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                    || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw error("Expected a JSON value");
        }
        try {
            return Double.valueOf(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("Malformed number '" + src.substring(start, pos) + "'");
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!src.startsWith(literal, pos)) {
            throw error("Expected literal '" + literal + "'");
        }
        pos += literal.length();
        return value;
    }

    // ── 游标辅助 ────────────────────────────────────────────────────────────

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw error("Unexpected end of input");
        }
        return src.charAt(pos);
    }

    private char next() {
        if (pos >= src.length()) {
            throw error("Unexpected end of input");
        }
        return src.charAt(pos++);
    }

    private void expect(char expected) {
        char c = next();
        if (c != expected) {
            throw error("Expected '" + expected + "' but found '" + c + "'");
        }
    }

    private JsonException error(String message) {
        return new JsonException(message + " (at offset " + pos + ")");
    }

    // ── 类型安全访问器 ──────────────────────────────────────────────────────

    /** 取对象字段；不存在或非 Map 返回 null。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object node, String key) {
        Object v = get(node, key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** 取数组字段；不存在或非 List 返回空列表。 */
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object node, String key) {
        Object v = get(node, key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    /** 取字符串字段。 */
    public static String str(Object node, String key) {
        Object v = get(node, key);
        return v instanceof String s ? s : null;
    }

    /** 取整数字段（JSON 数值以 Double 存储，此处安全收窄）。 */
    public static Integer integer(Object node, String key) {
        Double d = number(node, key);
        return d == null ? null : Integer.valueOf((int) (double) d);
    }

    private static Double number(Object node, String key) {
        Object v = get(node, key);
        return v instanceof Double d ? d : null;
    }

    private static Object get(Object node, String key) {
        if (node instanceof Map<?, ?> m) {
            return m.get(key);
        }
        return null;
    }
}
