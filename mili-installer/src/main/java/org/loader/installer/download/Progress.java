package org.loader.installer.download;

import java.io.PrintStream;
import java.util.Locale;

/**
 * 极简进度显示。
 *
 * <p>刻意只用 {@code System.out} + {@code \r}：安装器可能在无控制台环境
 * （IDE、重定向）下运行，此时自动降级为周期性日志而非疯狂刷屏。
 */
public final class Progress {

    private final PrintStream out;
    private final boolean interactive;
    private long lastPrintNanos;
    private int lastPercent = -1;

    public Progress(PrintStream out, boolean interactive) {
        this.out = out;
        this.interactive = interactive;
    }

    /** 创建一个进度显示器：仅当 stdout 连着控制台时才原地刷新。 */
    public static Progress create(PrintStream out) {
        return new Progress(out, System.console() != null);
    }

    /**
     * 报告单个文件的完成。
     *
     * @param label    文件标识
     * @param done     已完成文件数
     * @param total    总文件数（未知时传 0）
     * @param bytes    累计字节
     * @param totalBytes 总字节（未知时传 0）
     */
    public void file(String label, int done, int total, long bytes, long totalBytes) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        int width = 24;
        int pct = total > 0 ? (int) Math.floor(100.0 * done / total) : 0;
        int filled = total > 0 ? (int) Math.round(width * (double) done / total) : 0;
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? '=' : ' ');
        }
        sb.append("] ");
        sb.append(String.format(Locale.ROOT, "%3d%%", pct));
        sb.append(' ').append(humanBytes(bytes));
        if (totalBytes > 0) {
            sb.append('/').append(humanBytes(totalBytes));
        }
        sb.append("  ").append(label);

        line(sb.toString());
    }

    /** 输出一个普通状态行（不受节流影响）。 */
    public void status(String message) {
        clearLine();
        out.println(message);
    }

    /** 详情行，仅在交互模式显示（避免污染重定向的日志）。 */
    public void detail(String message) {
        if (interactive) {
            clearLine();
            out.println(message);
        }
    }

    private void line(String text) {
        if (interactive) {
            out.print('\r' + text);
            out.flush();
        } else {
            long now = System.nanoTime();
            // 非交互模式：进度每变化 10% 或每 3 秒输出一次
            int pct = -1;
            for (int i = 0; i <= 10; i++) {
                if (text.contains(String.format(Locale.ROOT, "%3d%%", i * 10))) {
                    pct = i * 10;
                    break;
                }
            }
            boolean tick = pct != lastPercent || now - lastPrintNanos > 3_000_000_000L;
            if (tick) {
                lastPercent = pct;
                lastPrintNanos = now;
                out.println(text);
            }
        }
    }

    private void clearLine() {
        if (interactive) {
            out.print("\r" + " ".repeat(100) + "\r");
            out.flush();
        }
    }

    /** 结束时清空进度条。 */
    public void finish() {
        clearLine();
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int idx = -1;
        while (value >= 1024 && idx < units.length - 1) {
            value /= 1024;
            idx++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[idx]);
    }
}
