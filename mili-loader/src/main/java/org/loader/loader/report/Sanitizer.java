package org.loader.loader.report;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 崩溃报告脱敏器 —— 落盘前抹掉一切能识别用户的信息。
 *
 * <h2>为什么要先脱敏再写盘</h2>
 * 启动报告与 Crash Capsule 的生命周期是"用户自己看 → 可能发给维护者"。
 * 一旦文件里出现用户名、主目录或 accessToken，用户就会在"报障"与
 * "泄露隐私"之间二选一。所以本类的策略是<b>宁可多抹、不可漏抹</b>：
 * <ul>
 *   <li>主目录（user.home，两种分隔符形态）→ {@code <home>}；</li>
 *   <li>gameDir 绝对路径 → {@code <gameDir>}；</li>
 *   <li>user.name 属性 → {@code <user>}；</li>
 *   <li>{@code --accessToken} 等命令行令牌 → 值替换为 {@code <redacted>}；</li>
 *   <li>残余的 Windows 盘符路径 / Unix 绝对路径 → {@code <path>}。</li>
 * </ul>
 *
 * <p>误伤可读性是可接受的（栈帧的类名不带路径，仍可定位）；漏掉一条
 * 隐私不可接受。替换顺序有讲究：先长后短、先具体后通用，避免
 * {@code C:\Users\<user>} 被"绝对路径"规则抢跑成半截混合体。
 */
public final class Sanitizer {

    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:[\\\\/][^\\s\"'<>|,;)\\]]*");
    private static final Pattern UNIX_PATH = Pattern.compile("(?<![\\w~])/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)+");
    private static final Pattern CMD_TOKEN = Pattern.compile(
            "(--accessToken|--token|--password|--clientid|--xuid)([= ])([^\\s\"']+)");
    private static final Pattern JSON_TOKEN = Pattern.compile(
            "(\"(?:accessToken|token|password|secret)\"\\s*:\\s*\")([^\"]*)(\")");

    private Sanitizer() {
    }

    /**
     * 脱敏任意文本（异常消息、日志片段、JSON 值）。null 安全。
     *
     * @param text    原文
     * @param gameDir 本次启动的 gameDir（其绝对路径被整体替换）
     */
    public static String clean(String text, Path gameDir) {
        if (text == null || text.isEmpty()) return text;
        String out = text;

        // 1. 命令行令牌（先于路径规则，避免值里的路径被先替换）
        out = CMD_TOKEN.matcher(out).replaceAll("$1$2<redacted>");
        out = JSON_TOKEN.matcher(out).replaceAll("$1<redacted>$3");

        // 2. 主目录与用户名（两种分隔符形态都替换）
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            out = replaceAllSeparators(out, home, "<home>");
        }
        String user = System.getProperty("user.name");
        if (user != null && !user.isBlank() && user.length() > 1) {
            out = out.replace(user, "<user>");
        }

        // 3. gameDir 绝对路径
        if (gameDir != null) {
            String abs = gameDir.toAbsolutePath().normalize().toString();
            if (!abs.isBlank()) {
                out = replaceAllSeparators(out, abs, "<gameDir>");
            }
        }

        // 4. 残余绝对路径（通用兜底）
        out = WINDOWS_PATH.matcher(out).replaceAll("<path>");
        out = UNIX_PATH.matcher(out).replaceAll("<path>");
        return out;
    }

    /** 把 path 的正/反斜杠两种形态都替换为 replacement。 */
    private static String replaceAllSeparators(String text, String path, String replacement) {
        if (path.isEmpty()) return text;
        String out = text.replace(path, replacement);
        String flipped = path.replace('\\', '/');
        if (!flipped.equals(path)) {
            out = out.replace(flipped, replacement);
        }
        String flippedBack = path.replace('/', '\\');
        if (!flippedBack.equals(path)) {
            out = out.replace(flippedBack, replacement);
        }
        return out;
    }

    /**
     * 脱敏并截断一段堆栈：只保留 {@code at Class.method(Source)} 行的
     * 有限帧数；Caused by / Suppressed 链以文本形式保留但同样截断。
     */
    public static String stackTrace(Throwable t, int maxFrames, Path gameDir) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder(512);
        Throwable cur = t;
        int chain = 0;
        while (cur != null && chain < 4) {
            if (chain > 0) {
                sb.append("Caused by: ");
            }
            sb.append(cur.getClass().getName())
              .append(": ").append(clean(String.valueOf(cur.getMessage()), gameDir)).append('\n');
            StackTraceElement[] frames = cur.getStackTrace();
            for (int i = 0; i < frames.length && i < maxFrames; i++) {
                sb.append("  at ").append(frames[i]).append('\n');
            }
            if (frames.length > maxFrames) {
                sb.append("  ... ").append(frames.length - maxFrames).append(" more\n");
            }
            cur = cur.getCause();
            chain++;
        }
        return sb.toString();
    }
}
