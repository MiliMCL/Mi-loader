package org.loader.loader.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 脱敏回归测试：宁可多抹、不可漏抹。 */
class SanitizerTest {

    @TempDir
    Path tmp;

    private static final String HOME = System.getProperty("user.home");
    private static final String USER = System.getProperty("user.name");

    @Test
    @DisplayName("主目录（正/反斜杠）都被替换为 <home>")
    void homeIsRedacted() {
        String fwd = HOME.replace('\\', '/');
        String back = HOME.replace('/', '\\');
        String out = Sanitizer.clean("加载失败 at " + fwd + "/logs/x.log and " + back + "\\mods", tmp);
        assertFalse(out.contains(fwd), "正斜杠形态泄露: " + out);
        assertFalse(out.contains(back), "反斜杠形态泄露: " + out);
        assertTrue(out.contains("<home>"));
    }

    @Test
    @DisplayName("user.name 被替换为 <user>")
    void userNameIsRedacted() {
        if (USER == null || USER.length() < 2) return; // 环境无用户名则跳过
        String out = Sanitizer.clean("running as " + USER + " session", tmp);
        assertFalse(out.contains(USER));
        assertTrue(out.contains("<user>"));
    }

    @Test
    @DisplayName("gameDir 绝对路径被替换为 <gameDir>")
    void gameDirIsRedacted() {
        String out = Sanitizer.clean("扫描目录 " + tmp.toAbsolutePath(), tmp);
        assertFalse(out.contains(tmp.toAbsolutePath().toString()));
        assertTrue(out.contains("<gameDir>"));
    }

    @Test
    @DisplayName("命令行令牌值被替换为 <redacted>")
    void cmdTokensRedacted() {
        String out = Sanitizer.clean(
                "java ... --accessToken abc123secret --gameDir . ", tmp);
        assertFalse(out.contains("abc123secret"));
        assertTrue(out.contains("<redacted>"));
    }

    @Test
    @DisplayName("JSON 形态的 token/secret 值被替换")
    void jsonTokensRedacted() {
        String out = Sanitizer.clean("{\"accessToken\":\"leak-me\",\"other\":1}", tmp);
        assertFalse(out.contains("leak-me"));
    }

    @Test
    @DisplayName("残余绝对路径被兜底替换为 <path>")
    void residualPathsRedacted() {
        String out = Sanitizer.clean("file at C:\\Some\\Other\\Place\\x.png", tmp);
        assertFalse(out.contains("C:\\Some"));
        assertTrue(out.contains("<path>"));
    }

    @Test
    @DisplayName("普通文本不被误伤")
    void normalTextUntouched() {
        String text = "Mod 'example' v1.0.0 初始化失败: cannot find block amaranth_crop";
        assertEquals(text, Sanitizer.clean(text, tmp));
    }

    @Test
    @DisplayName("堆栈：帧数截断且 Caused by 保留、含脱敏")
    void stackTraceCappedAndCleaned() {
        Exception root = new IllegalStateException("secret at " + HOME + "\\x");
        Exception wrapped = new RuntimeException("wrap", root);
        for (int i = 0; i < 60; i++) {
            wrapped.addSuppressed(root);
        }
        String out = Sanitizer.stackTrace(wrapped, 10, tmp);
        assertFalse(out.contains(HOME));
        assertTrue(out.contains("Caused by: java.lang.IllegalStateException"));
        assertTrue(out.contains("<home>"));
        long atLines = out.lines().filter(l -> l.trim().startsWith("at ")).count();
        assertTrue(atLines <= 20, "帧数应被截断: " + atLines);
    }

    @Test
    @DisplayName("null 全链安全")
    void nullSafe() {
        assertNull(Sanitizer.clean(null, null));
        assertEquals("", Sanitizer.stackTrace(null, 10, null));
        assertEquals("plain", Sanitizer.clean("plain", null));
    }
}
