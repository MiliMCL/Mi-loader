package org.loader.loader.report;

import org.loader.runtime.diagnostics.StartupTimeline;
import org.loader.runtime.error.MiliError;
import org.loader.runtime.mod.Mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 版本化启动报告（{@code logs/startup-report.json}）—— 结构化启动数据的
 * 主源。
 *
 * <h2>兑现文档承诺</h2>
 * README 早已承诺此文件（"所有已处理的 Mod、依赖图拓扑序、每个 Mod 的最终
 * 状态与加载耗时……格式版本化 1.0……环境摘要"），但审计（§0.2.11）确认
 * 从无实现。本类按 README 承诺的 schema 兑现，<b>只采集真实可获得的字段</b>：
 * 依赖拓扑序在失败路径上并不总是可得（可能失败在发现之前），因此不伪造 ——
 * 无字段就省略。
 *
 * <h2>失败安全（A3 验收红线）</h2>
 * <ul>
 *   <li>报告生成失败<b>绝不能掩盖原始崩溃</b>：入口 {@link #onLaunchFailure}
 *       全程 try/catch(Throwable)，最坏情况只打一行平台日志；</li>
 *   <li>所有字段采集都有独立 try 块，单个字段失败不影响其余字段；</li>
 *   <li>文本经 {@link Sanitizer} 脱敏后才落盘。</li>
 * </ul>
 *
 * <h2>消费方式</h2>
 * {@link CrashCapsule} 以本文件为清单第一项；CI 与维护者按
 * {@code schemaVersion} 演进解析。schema 变更必须升版本号并同步测试。
 */
public final class StartupReport {

    /** 与 README 承诺一致；变更即破坏兼容，需同步文档与测试。 */
    public static final String SCHEMA_VERSION = "1.0";

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final int MAX_TIMELINE_PHASES = 200;

    private StartupReport() {
    }

    /**
     * 启动失败钩子：写 {@code logs/startup-report.json} 并按需生成
     * Crash Capsule。**永不抛出**——包括 gameDir 为 null、指向文件、
     * 磁盘满、timeline 为 null 等一切病态输入。
     *
     * @param gameDir  游戏目录；null 时直接放弃（无处落盘）
     * @param error    原始异常（脱敏后进入报告）
     * @param timeline 启动时间线；可为 null
     * @param mods     已加载 Mod；可为 null（早期失败时为空是正常的）
     */
    public static void onLaunchFailure(Path gameDir, Throwable error,
                                       StartupTimeline timeline, List<Mod> mods) {
        try {
            if (gameDir == null) return;
            Path logs = gameDir.resolve("logs");
            Files.createDirectories(logs);
            String json = renderFailureReport(gameDir, error, timeline, mods);
            Path report = logs.resolve("startup-report.json");
            // 原子写：先写临时文件再移动，避免半截 JSON
            Path tmp = logs.resolve("startup-report.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, report,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, report,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // 本地可选 ZIP（默认开，mili.crashCapsule=false 关闭；绝不自动上传）
            if (!"false".equalsIgnoreCase(System.getProperty("mili.crashCapsule", "true"))) {
                CrashCapsule.create(logs);
            }
        } catch (Throwable reportFailure) {
            // 报告生成失败绝不能覆盖原始崩溃 —— 只留一行痕迹
            try {
                System.err.println("[Mili] 启动报告生成失败（不影响原始异常）: "
                        + reportFailure);
            } catch (Throwable ignored) {
                // 连 stderr 都不可用——放弃
            }
        }
    }

    /**
     * 渲染失败报告 JSON。包级可见供测试直接断言内容。
     */
    static String renderFailureReport(Path gameDir, Throwable error,
                                      StartupTimeline timeline, List<Mod> mods) {
        StringBuilder sb = new StringBuilder(2048);
        String gameDirStr = gameDir.toAbsolutePath().normalize().toString();

        sb.append('{');
        sb.append("\"schemaVersion\":").append(q(SCHEMA_VERSION));
        sb.append(",\"generatedAt\":").append(q(LocalDateTime.now().format(TS)));
        sb.append(",\"outcome\":").append(q("failure"));

        // ── 平台与环境摘要（README 承诺的 Java/OS/架构）────────────────
        sb.append(",\"platform\":{");
        sb.append("\"miliVersion\":").append(q(constant(() ->
                org.loader.api.VersionInfo.CURRENT_VERSION)));
        sb.append(",\"abiVersion\":").append(safeInt(() ->
                org.loader.api.VersionInfo.ABI_VERSION, 0));
        sb.append(",\"minecraftVersion\":").append(q(constant(() ->
                org.loader.api.VersionInfo.TARGET_MINECRAFT)));
        sb.append('}');
        sb.append(",\"environment\":{");
        sb.append("\"javaVersion\":").append(q(safe(() ->
                System.getProperty("java.version", "unknown"))));
        sb.append(",\"osName\":").append(q(safe(() ->
                System.getProperty("os.name", "unknown"))));
        sb.append(",\"osArch\":").append(q(safe(() ->
                System.getProperty("os.arch", "unknown"))));
        sb.append(",\"availableProcessors\":").append(safeInt(
                Runtime.getRuntime()::availableProcessors, -1));
        sb.append(",\"maxMemoryBytes\":").append(safeLong(
                () -> Runtime.getRuntime().maxMemory(), -1));
        sb.append('}');

        // ── 错误（脱敏）─────────────────────────────────────────────────
        sb.append(",\"error\":{");
        sb.append("\"type\":").append(q(error != null ? error.getClass().getName() : null));
        sb.append(",\"errorCode\":").append(q(MiliError.codeOf(error)));
        sb.append(",\"message\":").append(q(Sanitizer.clean(
                error != null ? String.valueOf(error.getMessage()) : null, gameDir)));
        sb.append(",\"stacktrace\":").append(q(Sanitizer.stackTrace(error, 40, gameDir)));
        sb.append('}');

        // ── 阶段：时间线最后一个已记录区间 ──────────────────────────────
        String phase = "unknown";
        try {
            if (timeline != null) {
                List<StartupTimeline.Phase> phases = timeline.phases();
                if (!phases.isEmpty()) {
                    phase = phases.get(phases.size() - 1).name();
                }
            }
        } catch (Throwable ignored) {
            // 保持 unknown
        }
        sb.append(",\"phase\":").append(q(phase));

        // ── 时间线（截断）──────────────────────────────────────────────
        sb.append(",\"timeline\":[");
        try {
            if (timeline != null) {
                List<StartupTimeline.Phase> phases = timeline.phases();
                int n = Math.min(phases.size(), MAX_TIMELINE_PHASES);
                for (int i = 0; i < n; i++) {
                    StartupTimeline.Phase p = phases.get(i);
                    if (i > 0) sb.append(',');
                    sb.append("{\"name\":").append(q(p.name()))
                      .append(",\"durationMs\":").append(String.format(
                              java.util.Locale.ROOT, "%.3f", p.durationNanos() / 1e6))
                      .append(",\"depth\":").append(p.depth()).append('}');
                }
            }
        } catch (Throwable ignored) {
            // 空时间线
        }
        sb.append(']');

        // ── Mod 状态（README 承诺"每个 Mod 的最终状态"）────────────────
        sb.append(",\"mods\":[");
        try {
            if (mods != null) {
                for (int i = 0; i < mods.size(); i++) {
                    Mod m = mods.get(i);
                    if (i > 0) sb.append(',');
                    String state;
                    try {
                        state = m.isClosed() ? "CLOSED" : "ACTIVE";
                    } catch (Throwable t) {
                        state = "UNKNOWN";
                    }
                    sb.append("{\"id\":").append(q(safe(m::id)))
                      .append(",\"state\":").append(q(state)).append('}');
                }
            }
        } catch (Throwable ignored) {
            // 空 mods
        }
        sb.append(']');

        sb.append('}');
        return sb.toString();
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    /** JSON 字符串转义（含引号）。 */
    private static String q(String s) {
        return org.loader.loader.util.MiliJson.quote(s);
    }

    /** 字段采集的独立保险： supplier 抛错时回退默认值。 */
    private static String constant(java.util.function.Supplier<String> s) {
        return safe(s);
    }

    private static String safe(java.util.function.Supplier<String> s) {
        try {
            String v = s.get();
            return v != null ? v : "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static int safeInt(java.util.function.Supplier<Integer> s, int fallback) {
        try {
            Integer v = s.get();
            return v != null ? v : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static long safeLong(java.util.function.Supplier<Long> s, long fallback) {
        try {
            Long v = s.get();
            return v != null ? v : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
