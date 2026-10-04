package org.loader.loader.util;

import java.util.*;

/**
 * Minimal, reliable JSON parser for Mili Mod manifests.
 * <p>
 * Supports: objects, arrays, strings (with standard escape sequences), numbers
 * (int and floating), booleans, null, arbitrary nesting, leading/trailing whitespace,
 * and unsorted fields. Does <b>not</b> use fragile indexOf/split hacks.
 * <p>
 * This is deliberately self-contained so mod manifest parsing does not pull in
 * a third-party JSON dependency just to read a few known fields.
 */
public final class MiliJson {

    private MiliJson() {
    }

    /**
     * Parses a JSON document into Java objects.
     * <ul>
     *     <li>JSON object → {@code Map<String,Object>}</li>
     *     <li>JSON array  → {@code List<Object>}</li>
     *     <li>JSON string → {@code String}</li>
     *     <li>JSON number → {@code Long} or {@code Double}</li>
     *     <li>true / false → {@code Boolean}</li>
     *     <li>null → {@code null}</li>
     * </ul>
     *
     * @param text JSON source
     * @return parsed value (Map, List, String, Number, Boolean, or null)
     * @throws ParseException on syntax error
     */
    public static Object parse(String text) {
        if (text == null) throw new ParseException("null input", 0);
        Parser p = new Parser(text);
        Object result = p.parseValue();
        p.skipWs();
        if (p.pos < p.s.length()) {
            throw new ParseException("unexpected trailing data at " + p.pos, p.pos);
        }
        return result;
    }

    /**
     * Parses and expects a JSON object.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new ParseException("expected JSON object, got " + typeName(o), 0);
        return (Map<String, Object>) o;
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    public static final class ParseException extends RuntimeException {
        ParseException(String message, int position) {
            super(message + " (offset " + position + ")");
        }
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
            this.pos = 0;
        }

        Object parseValue() {
            skipWs();
            if (pos >= s.length()) throw new ParseException("unexpected end of input", pos);
            char c = s.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
            throw new ParseException("unexpected character '" + c + "'", pos);
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWs();
                if (peek() != '"') throw new ParseException("expected string key", pos);
                String key = parseString();
                skipWs();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWs();
                char c = peek();
                if (c == '}') { pos++; break; }
                if (c != ',') throw new ParseException("expected ',' or '}'", pos);
                pos++;
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ']') { pos++; break; }
                if (c != ',') throw new ParseException("expected ',' or ']'", pos);
                pos++;
            }
            return list;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw new ParseException("trailing escape", pos);
                    char esc = s.charAt(pos++);
                    sb.append(unescape(esc));
                } else {
                    sb.append(c);
                }
            }
            throw new ParseException("unterminated string", pos);
        }

        private char unescape(char esc) {
            return switch (esc) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> {
                    if (pos + 4 > s.length()) throw new ParseException("truncated \\u escape", pos);
                    String hex = s.substring(pos, pos + 4);
                    pos += 4;
                    yield (char) Integer.parseInt(hex, 16);
                }
                default -> throw new ParseException("unknown escape \\" + esc, pos);
            };
        }

        private Number parseNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            boolean hasDigits = false;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
                hasDigits = true;
            }
            if (!hasDigits) throw new ParseException("expected digits after '-'", start);
            boolean isFloat = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
            }
            String num = s.substring(start, pos);
            if (isFloat) return Double.parseDouble(num);
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                return Double.parseDouble(num);
            }
        }

        private Boolean parseBoolean() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new ParseException("expected 'true' or 'false'", pos);
        }

        private Object parseNull() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw new ParseException("expected 'null'", pos);
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        private char peek() {
            return s.charAt(pos);
        }

        private void expect(char expected) {
            if (s.charAt(pos) != expected) {
                throw new ParseException("expected '" + expected + "' but found '" + s.charAt(pos) + "'", pos);
            }
            pos++;
        }
    }
}
