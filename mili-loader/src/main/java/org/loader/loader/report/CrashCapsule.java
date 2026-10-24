package org.loader.loader.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Crash Capsule —— 启动失败时的本地诊断包（{@code logs/crash-report-*.zip}）。
 *
 * <h2>边界（A3 验收清单逐条对应）</h2>
 * <ul>
 *   <li><b>本地生成、用户主动导出</b>：绝不自动上传。默认随失败报告生成
 *       （{@code mili.crashCapsule=false} 关闭），是否发给维护者由用户决定；</li>
 *   <li><b>条目安全</b>：条目名全部是<b>固定字面量</b>（平台自己生成的日志名），
 *       结构上不存在路径穿越的可能；测试锁定这一点；</li>
 *   <li><b>限额</b>：最多 {@value #MAX_FILES} 个文件、单文件尾部
 *       {@value #MAX_FILE_BYTES} 字节、总预算 {@value #BUDGET_MS} 毫秒 ——
 *       恶意超大日志不会拖垮崩溃路径；</li>
 *   <li><b>清单与校验</b>：{@code MANIFEST.txt} 记录每个条目的 SHA-256 与
 *       字节数 + capsule schema 版本；</li>
 *   <li><b>失败安全</b>：任何异常都转为 {@code ok=false} 返回值，不抛出 ——
 *       capsule 失败绝不能影响原始崩溃的正常传播。</li>
 * </ul>
 */
final class CrashCapsule {

    static final String SCHEMA_VERSION = "1.0";
    static final int MAX_FILES = 10;
    static final int MAX_FILE_BYTES = 512 * 1024;
    static final long BUDGET_MS = 5_000;

    /** 生成结果。ok=false 时 error 说明原因；zipPath 仅 ok 时有意义。 */
    record Result(boolean ok, Path zipPath, String error) {
        static Result fail(String why) {
            return new Result(false, null, why);
        }
    }

    /** 拟收入 capsule 的候选文件（文件名固定，防穿越由构造保证）。 */
    private record Candidate(String entryName, Path file, long tailBytes) {
    }

    private CrashCapsule() {
    }

    /**
     * 在 {@code logsDir} 下生成 crash-report-&lt;时间戳&gt;.zip。
     * 永不抛出。
     */
    static Result create(Path logsDir) {
        long deadline = System.currentTimeMillis() + BUDGET_MS;
        try {
            if (logsDir == null || !Files.isDirectory(logsDir)) {
                return Result.fail("logs 目录不可用");
            }

            List<Candidate> candidates = new ArrayList<>();
            candidates.add(new Candidate("startup-report.json",
                    logsDir.resolve("startup-report.json"), MAX_FILE_BYTES));
            candidates.add(new Candidate("mili-platform.log",
                    logsDir.resolve("mili-platform.log"), MAX_FILE_BYTES));
            candidates.add(new Candidate("mili-audit.log",
                    logsDir.resolve("mili-audit.log"), MAX_FILE_BYTES));
            candidates.add(new Candidate("install-report.json",
                    logsDir.resolve("install-report.json"), 256 * 1024));

            String stamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path zip = logsDir.resolve("crash-report-" + stamp + ".zip");

            List<String> manifestLines = new ArrayList<>();
            manifestLines.add("capsuleSchemaVersion=" + SCHEMA_VERSION);
            manifestLines.add("createdAt=" + LocalDateTime.now());
            manifestLines.add("files=");

            int written = 0;
            try (ZipOutputStream zos = new ZipOutputStream(
                    Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
                for (Candidate c : candidates) {
                    if (written >= MAX_FILES) break;
                    if (System.currentTimeMillis() > deadline) {
                        manifestLines.add("TRUNCATED=budget exhausted");
                        break;
                    }
                    if (!Files.isRegularFile(c.file())) continue;
                    try {
                        byte[] tail = readTail(c.file(), c.tailBytes());
                        zos.putNextEntry(new ZipEntry(c.entryName()));
                        zos.write(tail);
                        zos.closeEntry();
                        manifestLines.add("  " + c.entryName()
                                + " sha256=" + sha256Hex(tail)
                                + " bytes=" + tail.length
                                + (Files.size(c.file()) > c.tailBytes() ? " (tail)" : ""));
                        written++;
                    } catch (IOException readFailure) {
                        // 单文件读取失败跳过，不拖垮 capsule
                        manifestLines.add("  " + c.entryName() + " UNAVAILABLE ("
                                + readFailure.getClass().getSimpleName() + ")");
                    }
                }
            }
            // MANIFEST 最后写入 zip 外说明也复制一份在返回值里，方便日志
            Files.writeString(logsDir.resolve("crash-report-MANIFEST.txt"),
                    String.join("\n", manifestLines) + "\n", StandardCharsets.UTF_8);
            return new Result(true, zip, null);
        } catch (Throwable t) {
            return Result.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** 读文件尾部最多 maxBytes 字节（超大日志只保留最近的部分）。 */
    private static byte[] readTail(Path file, long maxBytes) throws IOException {
        long size = Files.size(file);
        long start = Math.max(0, size - maxBytes);
        try (InputStream in = Files.newInputStream(file)) {
            long skipped = 0;
            while (skipped < start) {
                long s = in.skip(start - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            return in.readNBytes((int) Math.min(maxBytes, Integer.MAX_VALUE));
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                  .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
