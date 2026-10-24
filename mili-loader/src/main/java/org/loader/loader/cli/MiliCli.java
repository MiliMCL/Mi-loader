package org.loader.loader.cli;

import org.loader.runtime.error.ErrorCatalog;
import org.loader.runtime.error.MiliError;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/**
 * {@code mili} 命令行入口 —— 平台诊断子命令的统一分发器。
 *
 * <h2>为什么是独立的 main，而不是挂在 LoaderMain 里</h2>
 * ①{@code LoaderMain.parseArgs} 的契约是"args[0]=gameDir，其余原样转发给
 * Minecraft"，塞进子命令会破坏既有语义与 {@code LaunchArgPassthroughTest}；
 * ②诊断命令必须<b>绝不启动游戏、不实例化 Mod、不执行 Mod 类</b>，与
 * 加载器主流程隔离是天然的安全边界；③LoaderMain 正处于用户未提交的修改中，
 * 独立入口避免冲突。
 *
 * <h2>用法</h2>
 * <pre>
 *   mili explain &lt;CODE&gt; [--json]   解释一个错误码并给出修复建议
 *   mili explain --list            列出全部已登记错误码
 *   mili doctor &lt;gameDir&gt; [opts]   只读体检（发现/绑定/依赖/Capability）
 *   mili help                      本帮助
 * </pre>
 *
 * <h2>退出码（稳定契约）</h2>
 * <ul>
 *   <li>{@code 0} —— 成功；doctor 无 error 级问题；help</li>
 *   <li>{@code 1} —— 业务失败：未知错误码 / doctor 发现 error 级问题</li>
 *   <li>{@code 2} —— 用法错误：未知子命令、缺参数、参数非法</li>
 * </ul>
 *
 * <h2>终端适配</h2>
 * 颜色仅在「交互式 TTY 且未设 NO_COLOR 且 TERM!=dumb 且未传 --no-color」时
 * 启用；重定向/管道/CI 一律纯文本，不破坏既有日志解析。{@code --json}
 * 输出稳定 schema（schemaVersion 固定），面向机器消费。
 */
public final class MiliCli {

    /** JSON 输出的 schema 版本；变更即破坏兼容，需同步文档。 */
    static final String SCHEMA_VERSION = "1.0";

    private MiliCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, detectTty()));
    }

    /** 测试与工具可注入流与 TTY 形态；返回退出码而不直接 exit。 */
    public static int run(String[] args, PrintStream out, PrintStream err, boolean tty) {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])
                || "-h".equals(args[0])) {
            printHelp(out);
            return 0;
        }
        String cmd = args[0];
        switch (cmd) {
            case "explain":
                return runExplain(tail(args), out, err, tty);
            case "doctor":
                return DoctorCommand.run(tail(args), out, err, tty);
            default:
                err.println("未知命令: " + cmd);
                err.println("运行 `mili help` 查看可用命令。");
                return 2;
        }
    }

    // ── explain ─────────────────────────────────────────────────────────

    private static int runExplain(String[] args, PrintStream out, PrintStream err, boolean tty) {
        boolean json = false;
        String code = null;
        for (String a : args) {
            if ("--json".equals(a)) {
                json = true;
            } else if ("--no-color".equals(a)) {
                // 颜色由下方 color() 统一判定；此 flag 显式禁用
                tty = false;
            } else if (a.startsWith("-")) {
                err.println("explain: 未知选项 " + a);
                return 2;
            } else if (code == null) {
                code = a;
            } else {
                err.println("explain: 一次只能解释一个错误码（收到 " + a + "）");
                return 2;
            }
        }

        if (code == null || "--list".equals(code)) {
            return printCodeList(out, json);
        }

        String normalized = code.trim().toUpperCase(Locale.ROOT);
        var entryOpt = ErrorCatalog.get(normalized);
        if (entryOpt.isEmpty()) {
            err.println("未知错误码: " + code);
            err.println("运行 `mili explain --list` 查看全部已登记错误码；");
            err.println("若日志中的码不在列表中，以日志原文为准并向维护者报告。");
            return 1;
        }
        ErrorCatalog.Entry e = entryOpt.get();
        if (json) {
            out.println(explainJson(e));
        } else {
            printExplain(out, e, color(tty));
        }
        return 0;
    }

    private static void printExplain(PrintStream out, ErrorCatalog.Entry e, Palette p) {
        out.println(p.bold(e.code() + " — " + e.title()));
        out.println();
        out.println(p.label("成因:   ") + e.cause());
        out.println(p.label("定位:   ") + e.location());
        out.println(p.label("修复建议:"));
        List<String> fixes = e.fixes();
        for (int i = 0; i < fixes.size(); i++) {
            out.println("  " + (i + 1) + ". " + fixes.get(i));
        }
        if (e.doc() != null) {
            out.println(p.label("文档:   ") + e.doc());
        }
    }

    private static String explainJson(ErrorCatalog.Entry e) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"schemaVersion\":\"").append(SCHEMA_VERSION).append('"');
        sb.append(",\"found\":true");
        sb.append(",\"code\":\"").append(jsonEscape(e.code())).append('"');
        sb.append(",\"title\":\"").append(jsonEscape(e.title())).append('"');
        sb.append(",\"cause\":\"").append(jsonEscape(e.cause())).append('"');
        sb.append(",\"location\":\"").append(jsonEscape(e.location())).append('"');
        sb.append(",\"fixes\":[");
        for (int i = 0; i < e.fixes().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(jsonEscape(e.fixes().get(i))).append('"');
        }
        sb.append(']');
        sb.append(",\"doc\":").append(e.doc() != null
                ? "\"" + jsonEscape(e.doc()) + "\"" : "null");
        sb.append('}');
        return sb.toString();
    }

    private static int printCodeList(PrintStream out, boolean json) {
        if (json) {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"schemaVersion\":\"").append(SCHEMA_VERSION).append('"');
            sb.append(",\"codes\":[");
            var all = ErrorCatalog.all();
            for (int i = 0; i < all.size(); i++) {
                ErrorCatalog.Entry e = all.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"code\":\"").append(jsonEscape(e.code()))
                  .append("\",\"title\":\"").append(jsonEscape(e.title())).append("\"}");
            }
            sb.append("]}");
            out.println(sb);
            return 0;
        }
        out.println("已登记错误码（" + ErrorCatalog.all().size() + " 个）：");
        out.println();
        for (ErrorCatalog.Entry e : ErrorCatalog.all()) {
            out.printf("  %-28s %s%n", e.code(), e.title());
        }
        out.println();
        out.println("用 `mili explain <CODE>` 查看完整解释。");
        return 0;
    }

    // ── help ────────────────────────────────────────────────────────────

    private static void printHelp(PrintStream out) {
        out.println("mili — Mili Platform 诊断命令行");
        out.println();
        out.println("用法: mili <命令> [参数]");
        out.println();
        out.println("命令:");
        out.println("  explain <CODE> [--json]   解释错误码并给出修复建议");
        out.println("  explain --list [--json]   列出全部已登记错误码");
        out.println("  doctor <gameDir> [选项]   只读体检：发现/绑定/依赖/文件完整性");
        out.println("                            选项: --json --graph --mili-mods <dir>");
        out.println("  help                      显示本帮助");
        out.println();
        out.println("退出码: 0=成功  1=业务失败(未知码/doctor发现error)  2=用法错误");
        out.println("说明: 诊断命令绝不启动游戏、不实例化 Mod、不执行 Mod 类。");
    }

    // ── 终端与 JSON 工具 ────────────────────────────────────────────────

    private static boolean detectTty() {
        if (System.console() == null) return false;
        if (System.getenv("NO_COLOR") != null) return false;
        String term = System.getenv("TERM");
        return !"dumb".equals(term);
    }

    private static String[] tail(String[] args) {
        String[] t = new String[args.length - 1];
        System.arraycopy(args, 1, t, 0, t.length);
        return t;
    }

    private static Palette color(boolean tty) {
        return tty ? new Palette("\u001B[1;36m", "\u001B[1m", "\u001B[0m")
                   : new Palette("", "", "");
    }

    /** 极小调色板：null 样式即纯文本。 */
    private record Palette(String title, String bold, String reset) {
        String label(String s) {
            return bold + s + reset;
        }

        String bold(String s) {
            return title + s + reset;
        }
    }

    /** 最小 JSON 字符串转义（RFC 8259 必需集合 + 控制字符）。内容语义，不含两侧引号。 */
    static String jsonEscape(String s) {
        if (s == null) return "";
        String q = org.loader.loader.util.MiliJson.quote(s);
        return q.substring(1, q.length() - 1); // 去掉 quote() 的首尾引号
    }
}
