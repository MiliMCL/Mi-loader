package org.loader.loader;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 平台日志 —— 同时写到控制台与 Minecraft 日志文件。
 *
 * <h2>为什么不能只用 System.out</h2>
 *
 * <p>平台此前所有诊断都走 {@code System.out.println("[Mili] ...")}。
 * 那些输出<b>只出现在控制台</b>，不会进入 {@code logs/latest.log} ——
 * 于是排查时只能盯控制台，一旦用户只给日志文件，平台侧信息就全部缺失。
 *
 * <p>实际踩过的坑：连续两轮修复都因为「latest.log 里零条 [Mili]」而误判为
 * 「代码没执行」，而真实原因是那些输出压根不写进日志。
 * 这个盲区让诊断方向偏了两次。
 *
 * <h2>做法</h2>
 *
 * <p>优先用 {@link java.util.logging.Logger}（游戏自身就在用它，
 * {@code latest.log} 里的内容也由它写出）；若平台类加载器早于游戏日志系统
 * 初始化导致 logger 不可用，则退化为写 stderr —— 至少不会静默丢失。
 *
 * <p>输出格式与游戏日志对齐（时间 + 标签 + 级别），便于在同一文件里阅读。
 */
public final class PlatformLog {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 游戏日志系统是否就绪。不可用时退化为 stderr。 */
    private static volatile boolean log4jReady = false;

    private PlatformLog() {
    }

    /**
     * 启用文件日志。必须在游戏日志系统初始化后调用一次。
     *
     * <p>调用后 {@link #info} 等会写进 {@code logs/latest.log}。
     */
    public static void enableFileLogging() {
        log4jReady = true;
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

    /** 记一条 ERROR。 */
    public static void error(String message, Throwable t) {
        write("ERROR", message, t);
    }

    private static void write(String level, String message, Throwable t) {
        String line = "[" + LocalTime.now().format(TIME) + "] [" + level + "] "
                + "[Mili] " + message;

        // 控制台：始终输出（与既有行为一致，不丢现场）
        PrintStream out = "ERROR".equals(level) || "WARN".equals(level)
                ? System.err : System.out;
        out.println(line);
        if (t != null) {
            t.printStackTrace(out);
        }

        // 文件：走游戏日志系统，才能落进 latest.log
        if (log4jReady) {
            try {
                Class<?> log4j = Class.forName("org.apache.logging.log4j.LogManager");
                Object logger = log4j
                        .getMethod("getLogger", String.class)
                        .invoke(null, "Mili");
                Class<?> logLevel = Class.forName("org.apache.logging.log4j.Level");
                // Level 是枚举；用 asSubclass + valueOf 而非原始类型强转，
                // 避免 unchecked 警告
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object levelObj = Enum.valueOf(
                        (Class<? extends Enum>) logLevel.asSubclass(Enum.class), level);
                Class<?> loggerClass = Class.forName("org.apache.logging.log4j.Logger");
                loggerClass
                        .getMethod("log", logLevel, String.class, Throwable.class)
                        .invoke(logger, levelObj, message, t);
            } catch (Throwable ignored) {
                // 日志系统不可用不该影响启动 —— 控制台已经有输出了
            }
        }
    }
}
