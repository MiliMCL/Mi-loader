package org.loader.installer.json;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(Boolean.TRUE, Json.obj(a.get(2), "c").get("c"));
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
}
