package org.loader.runtime.transform.debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 类转储 —— 把转换前后的字节码写到磁盘，供反编译比对。
 *
 * <h2>启用方式</h2>
 * <pre>
 *   -Dmili.transform.dump=true
 *   -Dmili.transform.dump.dir=D:/mili-dump        # 可选，默认 ./logs/transform-dump
 * </pre>
 *
 * <h2><b>绝对禁止写入仓库目录</b></h2>
 * 转储目录默认是 {@code logs/transform-dump}，而 {@code logs} 已在
 * {@code .gitignore} 中。这是刻意的：
 * <ul>
 *   <li>写进仓库 → 用户 {@code git status} 看到几百个 {@code .class}
 *       文件，可能误提交；</li>
 *   <li>写进 {@code build/} → 会被 Gradle 的 clean 删掉，用户刚转储完
 *       就没了；</li>
 *   <li>写进 {@code src/} → 会被当成源码，且污染发布物。</li>
 * </ul>
 *
 * <p>因此本类在 {@link #ensureSafeDirectory} 里显式拒绝这些路径，
 * 即使系统属性指向它们也不写。
 *
 * <h2>为什么转储对定位问题不可替代</h2>
 * 转换后字节码是否正确，最终只能靠「看反编译结果」判断。特别是
 * REDIRECT 与 MODIFY_ARG —— 这两者的正确性完全体现在生成的指令序列上，
 * 而字节码验证器只能确认它「合法」，不能确认它「是你想要的」。
 */
public final class ClassDumper {

    public static final String DUMP_PROPERTY = "mili.transform.dump";
    public static final String DUMP_DIR_PROPERTY = "mili.transform.dump.dir";
    public static final String DEFAULT_DIR = "logs/transform-dump";

    /**
     * 禁止写入的目录名片段 —— 转储绝不能污染这些位置。
     *
     * <p>检查的是路径中的目录名片段，因此
     * {@code E:/loader/build/dump} 也会被拒绝（含有 {@code build}）。
     */
    private static final String[] FORBIDDEN_SEGMENTS = {
            "build", "src", "gradle", ".git", ".github"
    };

    private final boolean enabled;
    private final Path directory;
    private final AtomicLong dumpCount = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private volatile String lastError;

    private ClassDumper(boolean enabled, Path directory) {
        this.enabled = enabled;
        this.directory = directory;
    }

    /** 创建转储器；是否启用由系统属性决定。 */
    public static ClassDumper create() {
        boolean on = flag(DUMP_PROPERTY);
        Path dir = resolveDirectory();
        return new ClassDumper(on, ensureSafeDirectory(dir));
    }

    /** 显式关闭的转储器 —— 生产默认。 */
    public static ClassDumper disabled() {
        return new ClassDumper(false, null);
    }

    /** 测试用构造器。 */
    public static ClassDumper create(Path directory, boolean enabled) {
        return new ClassDumper(enabled, ensureSafeDirectory(directory));
    }

    private static boolean flag(String property) {
        try {
            return Boolean.parseBoolean(System.getProperty(property, "false"));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Path resolveDirectory() {
        String custom = null;
        try {
            custom = System.getProperty(DUMP_DIR_PROPERTY);
        } catch (Throwable ignored) {
            // 安全策略禁止读属性 → 用默认值
        }
        if (custom == null || custom.isBlank()) {
            return Path.of(DEFAULT_DIR);
        }
        return Path.of(custom);
    }

    /**
     * 校验目录安全性 —— 命中禁止片段则返回 {@code null}（转储关闭）。
     *
     * <p>返回 null 而非抛异常：转储是调试功能，它不可用不应阻止游戏启动。
     * 但必须让用户知道 —— 通过 {@link #lastError()}。
     */
    private static Path ensureSafeDirectory(Path dir) {
        if (dir == null) {
            return null;
        }
        Path absolute = dir.toAbsolutePath().normalize();
        for (Path segment : absolute) {
            String name = segment.toString();
            for (String forbidden : FORBIDDEN_SEGMENTS) {
                if (name.equalsIgnoreCase(forbidden)) {
                    return null;
                }
            }
        }
        return absolute;
    }

    public boolean isEnabled() {
        return enabled && directory != null;
    }

    public Path directory() {
        return directory;
    }

    /**
     * 转储一个类的转换前后字节码。
     *
     * @param className 类内部名
     * @param original  转换前字节
     * @param transformed 转换后字节
     */
    public void dump(String className, byte[] original, byte[] transformed) {
        if (!isEnabled() || className == null || transformed == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
            String simple = className.replace('/', '.');
            // 前后各存一份：只看结果无法判断「注入是否落在了正确位置」。
            write(directory.resolve(simple + ".before.class"), original);
            write(directory.resolve(simple + ".after.class"), transformed);
            dumpCount.incrementAndGet();
        } catch (Throwable t) {
            writeFailures.incrementAndGet();
            lastError = t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
        }
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        if (bytes == null) {
            // 未变化的类没有 before 文件，这是正常情况
            return;
        }
        Files.write(target, bytes);
    }

    /** 最近一次写入错误。 */
    public String lastError() {
        return lastError;
    }

    public long dumpCount() {
        return dumpCount.get();
    }

    public long writeFailureCount() {
        return writeFailures.get();
    }

    public String diagnostics() {
        return "ClassDumper[enabled=" + isEnabled()
                + ", dir=" + directory
                + ", dumps=" + dumpCount.get()
                + ", writeFailures=" + writeFailures.get()
                + (lastError != null ? ", lastError=" + lastError : "")
                + "]";
    }
}