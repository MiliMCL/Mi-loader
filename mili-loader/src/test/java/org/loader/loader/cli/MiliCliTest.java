package org.loader.loader.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.loader.util.MiliJson;
import org.loader.runtime.error.ErrorCatalog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code mili explain} / 帮助 / 退出码契约测试。
 *
 * <p>退出码是稳定契约（0/1/2），变更需同步 README 与 docs/ERROR_MODEL.md。
 */
class MiliCliTest {

    /** 一次受控执行的完整结果。 */
    private record Out(int code, String stdout, String stderr) {
    }

    private static Out run(boolean tty, String... args) {
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(so, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(se, true, StandardCharsets.UTF_8);
        int code = MiliCli.run(args, out, err, tty);
        return new Out(code, so.toString(StandardCharsets.UTF_8),
                se.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("explain 已知码：退出 0，输出含标题/成因/建议")
    void explainKnownCode() {
        Out o = run(false, "explain", "MOD_ABI_MISMATCH");
        assertEquals(0, o.code());
        assertTrue(o.stdout().contains("MOD_ABI_MISMATCH"));
        assertTrue(o.stdout().contains("修复建议"));
        assertTrue(o.stdout().contains("1."));
    }

    @Test
    @DisplayName("explain 大小写不敏感")
    void explainCaseInsensitive() {
        Out o = run(false, "explain", "mod_manifest_invalid");
        assertEquals(0, o.code());
        assertTrue(o.stdout().contains("MOD_MANIFEST_INVALID"));
    }

    @Test
    @DisplayName("explain 未知码：退出 1，stderr 给出可发现提示")
    void explainUnknownCodeExitsNonZero() {
        Out o = run(false, "explain", "TOTALLY_MADE_UP");
        assertEquals(1, o.code());
        assertTrue(o.stderr().contains("未知错误码"));
        assertTrue(o.stderr().contains("explain --list"));
    }

    @Test
    @DisplayName("explain --json：稳定 schema，可被 MiliJson 解析")
    void explainJsonIsParsable() {
        Out o = run(false, "explain", "MOD_PLATFORM_MISSING", "--json");
        assertEquals(0, o.code());
        Map<String, Object> json = MiliJson.parseObject(o.stdout().trim());
        assertEquals("1.0", json.get("schemaVersion"));
        assertEquals(Boolean.TRUE, json.get("found"));
        assertEquals("MOD_PLATFORM_MISSING", json.get("code"));
        assertNotNull(json.get("title"));
        assertNotNull(json.get("cause"));
        assertInstanceOf(java.util.List.class, json.get("fixes"));
        assertEquals("docs/PLATFORM_VERSIONING.md", json.get("doc"));
    }

    @Test
    @DisplayName("explain --list 覆盖目录全部码")
    void explainListCoversCatalog() {
        Out o = run(false, "explain", "--list");
        assertEquals(0, o.code());
        for (String code : ErrorCatalog.codes()) {
            assertTrue(o.stdout().contains(code), "--list 缺少码 " + code);
        }
    }

    @Test
    @DisplayName("非 TTY 输出不含 ANSI 转义；TTY 输出含")
    void nonTtyIsPlain() {
        Out o = run(false, "explain", "MOD_ABI_MISMATCH");
        assertFalse(o.stdout().contains("\u001B["));
        Out t = run(true, "explain", "MOD_ABI_MISMATCH");
        assertTrue(t.stdout().contains("\u001B["));
    }

    @Test
    @DisplayName("--no-color 强制关闭颜色")
    void noColorFlagDisablesColor() {
        Out o = run(true, "explain", "MOD_ABI_MISMATCH", "--no-color");
        assertFalse(o.stdout().contains("\u001B["));
    }

    @Test
    @DisplayName("无参数=help 退出 0；未知命令=退出 2")
    void helpAndUsageErrors() {
        assertEquals(0, run(false).code());
        assertEquals(0, run(false, "help").code());
        Out o = run(false, "explode");
        assertEquals(2, o.code());
        assertTrue(o.stderr().contains("未知命令"));
    }

    @Test
    @DisplayName("explain 非法选项=退出 2")
    void explainBadOption() {
        assertEquals(2, run(false, "explain", "MOD_ABI_MISMATCH", "--bogus").code());
    }

    @Test
    @DisplayName("JSON 转义：消息中的引号/换行/反斜杠不会破坏 JSON")
    void jsonEscapeHandlesSpecials() {
        assertEquals("a\\\"b\\\\c\\nd", MiliCli.jsonEscape("a\"b\\c\nd"));
        Map<String, Object> json = MiliJson.parseObject(
                "{\"k\":\"" + MiliCli.jsonEscape("x\"y\\z\nw") + "\"}");
        assertEquals("x\"y\\z\nw", json.get("k"));
    }
}
