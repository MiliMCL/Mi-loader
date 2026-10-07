package org.loader.loader;

import java.io.IOException;
import java.io.PrintStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 平台日志 —— 写控制台 + 平台自有日志文件（{@code logs/mili-platform.log}）。
 *
 * <h2>为什么不走游戏的 log4j（曾经的空文件事故）</h2>
 *
 * <p>旧实现用反射调 {@code org.apache.logging.log4j.LogManager}，意图把
 * [Mili] 诊断写进游戏的 {@code logs/latest.log}。但 {@code PlatformLog}
 * 加载在<b>平台类加载器</b>上，log4j 在<b>游戏类加载器</b>里 ——
 * {@code Class.forName} 用调用方加载器查找，必然 {@code ClassNotFoundException}，
 * 而 catch 块出于「日志不能影响启动」全部吞掉。结果是：
 * 建出了空文件、控制台有输出、文件里一个字都没有 —— 诊断信息又一次静默丢失。
 *
 * <h2>做法</h2>
 *
 * <p>写<b>平台自己的文件</b> {@code logs/mili-platform.log}（与
 * latest.log 同目录、不同文件），直接用追加写 {@link OutputStream}：
 * 无加载器可见性依赖、无格式化器依赖、失败会打回控制台而不是吞掉。
 * 多线程写同一文件用 {@code synchronized} 串行化 —— 平台日志量低频，
 * 锁不构成热点。
 */
public final class PlatformLog {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 平台日志文件输出流。null 表示尚未启用 —— 只写控制台。
     * 写入时 synchronized 本对象的监视器锁。
     */
    private static volatile OutputStream fileSink;

    private PlatformLog() {
    }

    /**
     * 启用文件日志：在 {@code logDir}（游戏目录的 logs 子目录）下打开
     * {@code mili-platform.log} 追加写。打开失败只影响文件落盘，
     * 控制台输出照旧。
     */
    public static void enableFileLogging(Path logDir) {
        try {
            Files.createDirectories(logDir);
            Path target = logDir.resolve("mili-platform.log");
            fileSink = Files.newOutputStream(target,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            System.err.println("[Mili] WARN 平台日志文件不可用（"
                    + (logDir == null ? "null" : logDir) + "）: " + e);
        }
    }

    /** 记一条 INFO。 */
    public static void info(String message) {
        write("INFO", message, null);
    }

    /** 记一条 WARN。 */
    public static void warn(String message) {
        write("WARN", message, null);
    }

    /** 记一条 WARN 并附异常。 */
    public static void warn(String message, Throwable t) {
        write("WARN", message, t);
    }

    /** 记一条 ERROR 并附异常。 */
    public static void error(String message, Throwable t) {
        write("ERROR", message, t);
    }

    /**
     * 原样落盘一段多行文本（如线程转储）。不经格式化、不落控制台
     * （调用方负责控制台输出）—— 供看门狗等一次性大批量输出使用。
     */
    public static void raw(String text) {
        OutputStream sink = fileSink;
        if (sink == null) {
            return;
        }
        try {
            sink.write(text.getBytes(StandardCharsets.UTF_8));
            sink.flush();
        } catch (IOException ignored) {
            // 文件写失败不影响主流程
        }
    }

    private static void write(String level, String message, Throwable t) {
        String line = "[" + LocalDateTime.now().format(TIME) + "] [" + level + "] "
                + "[Mili] " + message + System.lineSeparator();
        StringBuilder sb = new StringBuilder(line);
        if (t != null) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            sb.append(sw);
        }

        // 控制台：始终输出（与既有行为一致，不丢现场）
        PrintStream out = "ERROR".equals(level) || "WARN".equals(level)
                ? System.err : System.out;
        out.print(sb);

        // 文件：平台自有 sink（见类注释 —— 游戏的 log4j 从平台加载器不可见）
        OutputStream sink = fileSink;
        if (sink != null) {
            try {
                byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                synchronized (PlatformLog.class) {
                    sink.write(bytes);
                    sink.flush();
                }
            } catch (IOException ignored) {
                // 文件写失败不影响主流程
            }
        }
    }
}
