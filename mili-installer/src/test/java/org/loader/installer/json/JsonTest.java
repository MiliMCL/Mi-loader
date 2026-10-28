package org.loader.installer.json;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void parsesFlatObject() {
        Map<String, Object> o = Json.parseObject("{\"id\":\"26.2\",\"size\":100}");
        assertEquals("26.2", Json.str(o, "id"));
        assertEquals(100, Json.integer(o, "size"));
    }

    @Test
    void parsesNestedStructures() {
        Map<String, Object> o = Json.parseObject(
                "{\"a\":{\"b\":[1,2,{\"c\":true}]},\"d\":null}");
        List<Object> a = Json.arr(Json.obj(o, "a"), "b");
        assertEquals(3, a.size());
        // a.get(2) is {"c":true} — a bare boolean, not a String, so the
        // typed accessor must return null rather than coerce.
        Map<String, Object> inner = Json.obj(a.get(2), "");
        assertNotNull(inner);
        assertEquals(Boolean.TRUE, inner.get("c"));
        assertNull(Json.str(a.get(2), "c"));
        assertTrue(Json.arr(o, "d").isEmpty());
    }

    @Test
    void parsesEmptyContainers() {
        assertTrue(Json.parseObject("{}").isEmpty());
        assertTrue(Json.arr(Json.parseObject("{\"x\":[]}"), "x").isEmpty());
        assertTrue(Json.arr(Json.parseObject("{\"x\":{}}"), "x").isEmpty());
    }

    @Test
    void handlesEscapes() {
        Map<String, Object> o = Json.parseObject(
                "{\"p\":\"a\\\"b\\\\c\\nd\\te\\u0041\"}");
        assertEquals("a\"b\\c\nd\teA", Json.str(o, "p"));
    }

    @Test
    void handlesUnicodeDirectly() {
        assertEquals("中文", Json.str(Json.parseObject("{\"k\":\"中文\"}"), "k"));
    }

    @Test
    void parsesNegativeAndDecimalNumbers() {
        Map<String, Object> o = Json.parseObject("{\"a\":-5,\"b\":1.5,\"c\":1e3}");
        assertEquals(-5, Json.integer(o, "a"));
        assertEquals(1.5, (double) (Double) Json.parseObject("{\"b\":1.5}").get("b"), 1e-9);
        assertEquals(1000.0, (double) (Double) Json.parseObject("{\"c\":1e3}").get("c"), 1e-9);
    }

    @Test
    void toleratesWhitespace() {
        Map<String, Object> o = Json.parseObject("  {\n \"a\" : 1 ,\t\"b\":2}\r\n");
        assertEquals(1, Json.integer(o, "a"));
        assertEquals(2, Json.integer(o, "b"));
    }

    @Test
    void rejectsMalformed() {
        assertThrows(JsonException.class, () -> Json.parse("{"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1}trailing"));
        assertThrows(JsonException.class, () -> Json.parse("{'a':1}"));
        assertThrows(JsonException.class, () -> Json.parse("[1,]"));
    }

    @Test
    void accessorsReturnNullForMissingOrWrongType() {
        Map<String, Object> o = Json.parseObject("{\"s\":\"x\",\"n\":1}");
        assertNull(Json.str(o, "missing"));
        assertNull(Json.str(o, "n"));
        assertNull(Json.integer(o, "s"));
        assertTrue(Json.obj(o, "s") == null);
    }

    @Test
    void strOrNumberAcceptsBothEncodings() {
        // Mojang ships majorVersion as "25" in older manifests and as the
        // number 25.0 in 26.x. Both must read back as "25".
        Map<String, Object> stringy = Json.parseObject("{\"v\":\"25\"}");
        Map<String, Object> numeric = Json.parseObject("{\"v\":25.0}");
        assertEquals("25", Json.strOrNumber(stringy, "v"));
        assertEquals("25", Json.strOrNumber(numeric, "v"));

        // Genuinely fractional values keep their decimal form.
        assertEquals("25.5", Json.strOrNumber(Json.parseObject("{\"v\":25.5}"), "v"));

        // Other types and missing keys stay null.
        assertNull(Json.strOrNumber(Json.parseObject("{\"v\":true}"), "v"));
        assertNull(Json.strOrNumber(stringy, "missing"));
    }

    // ── 写出（序列化器）─────────────────────────────────────────────────────

    /**
     * Windows 路径是最容易踩的坑：{@code C:\Users\x} 里的 {@code \U}
     * 不是合法 JSON 转义。必须由序列化器转义成 {@code C:\\Users\\x}。
     */
    @Test
    void writeEscapesWindowsPathSoItRoundTrips() {
        String path = "C:\\Users\\Administrator\\AppData\\Local\\mili\\game";
        String json = Json.write(java.util.Map.of("clientJar", path));

        assertTrue(json.contains("\\\\"), () -> "反斜杠必须被转义: " + json);
        Map<String, Object> back = Json.parseObject(json);
        assertEquals(path, Json.str(back, "clientJar"));
    }

    @Test
    void writeRoundTripsQuotesUnicodeNewlinesAndControlChars() {
        String nasty = "a\"b\\c\nd\te\rf\u0001g中🚀";
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("value", nasty);
        source.put("list", java.util.List.of("x\"y", "z\\w"));

        Map<String, Object> back = Json.parseObject(Json.write(source));

        assertEquals(nasty, Json.str(back, "value"));
        assertEquals(java.util.List.of("x\"y", "z\\w"), Json.arr(back, "list"));
    }

    @Test
    void writeRoundTripsNumbersAndBooleans() {
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("i", 42);
        source.put("l", 9_000_000_000L);
        source.put("d", 1.5);
        source.put("b", true);
        source.put("n", null);

        Map<String, Object> back = Json.parseObject(Json.write(source));

        assertEquals(42, Json.integer(back, "i"));
        assertEquals(9_000_000_000L, ((Double) back.get("l")).longValue());
        assertEquals(1.5, (Double) back.get("d"), 1e-9);
        assertEquals(Boolean.TRUE, back.get("b"));
        assertTrue(back.containsKey("n"));
        assertNull(back.get("n"));
    }

    @Test
    void writeRejectsValuesThatWouldProduceInvalidJson() {
        // 非有限数值在 JSON 里没有字面量表示
        assertThrows(JsonException.class, () -> Json.write(Double.NaN));
        assertThrows(JsonException.class, () -> Json.write(Double.POSITIVE_INFINITY));
        // 任意对象不能靠 toString() 混过去（那正是产出非法文档的源头）
        assertThrows(JsonException.class, () -> Json.write(java.nio.file.Path.of("a/b")));
        // 对象 key 必须是 String
        assertThrows(JsonException.class, () -> Json.write(Map.of(1, "x")));
    }

    @Test
    void writePrettyIsParseableAndIndented() {
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("a", java.util.Map.of("b", 1));
        String pretty = Json.writePretty(source);

        assertTrue(pretty.contains("\n"), "缩进输出应包含换行");
        assertEquals(1, Json.integer(Json.obj(Json.parseObject(pretty), "a"), "b"));
    }
}
