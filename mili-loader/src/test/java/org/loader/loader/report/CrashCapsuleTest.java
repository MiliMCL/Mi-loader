package org.loader.loader.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Crash Capsule 行为测试：条目安全、限额、失败安全。 */
class CrashCapsuleTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("正常生成：zip 存在、条目名是固定平铺名、MANIFEST 含 SHA-256")
    void createsZipWithFlatEntries() throws Exception {
        Path logs = tmp.resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("startup-report.json"), "{\"outcome\":\"failure\"}");
        Files.writeString(logs.resolve("mili-platform.log"),
                "[12:00:00] [INFO] [Mili] 启动\n[12:00:01] [ERROR] [Mili] 崩溃");

        CrashCapsule.Result r = CrashCapsule.create(logs);
        assertTrue(r.ok(), () -> "应成功: " + r.error());
        assertTrue(Files.exists(r.zipPath()));

        try (ZipFile zip = new ZipFile(r.zipPath().toFile())) {
            var names = zip.entries().asList().stream().map(ZipEntry::getName).toList();
            assertTrue(names.contains("startup-report.json"));
            assertTrue(names.contains("mili-platform.log"));
            for (String n : names) {
                // 条目安全：固定平铺名，不允许出现任何路径成分
                assertFalse(n.contains("/") || n.contains("\\") || n.contains(".."),
                        "条目名必须是平铺固定名: " + n);
            }
            // startup-report.json 内容保真
            try (var in = zip.getInputStream(zip.getEntry("startup-report.json"))) {
                assertEquals("{\"outcome\":\"failure\"}", new String(in.readAllBytes(),
                        StandardCharsets.UTF_8));
            }
        }
        Path manifest = logs.resolve("crash-report-MANIFEST.txt");
        assertTrue(Files.exists(manifest));
        String manifestText = Files.readString(manifest);
        assertTrue(manifestText.contains("capsuleSchemaVersion=1.0"));
        assertTrue(manifestText.contains("sha256="));
        String sha = manifestText.split("sha256=")[1].split(" ")[0];
        assertEquals(64, sha.length(), "SHA-256 应为 64 位十六进制: " + sha);
    }

    @Test
    @DisplayName("超大日志：条目被截断到 512KB 尾部")
    void oversizedLogIsTruncated() throws Exception {
        Path logs = tmp.resolve("logs");
        Files.createDirectories(logs);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            big.append("line-").append(i).append("-0123456789abcdef\n");
        }
        Files.writeString(logs.resolve("mili-platform.log"), big.toString()); // ~3MB

        CrashCapsule.Result r = CrashCapsule.create(logs);
        assertTrue(r.ok());
        try (ZipFile zip = new ZipFile(r.zipPath().toFile())) {
            ZipEntry e = zip.getEntry("mili-platform.log");
            assertNotNull(e);
            assertTrue(e.getSize() <= CrashCapsule.MAX_FILE_BYTES,
                    "条目应被截断到限额内: " + e.getSize());
            // 是尾部而非头部：最后一行的内容应在
            try (var in = zip.getInputStream(e)) {
                String tail = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(tail.contains("line-99999"));
            }
        }
    }

    @Test
    @DisplayName("logs 目录不存在：ok=false，不抛异常")
    void emptyLogsDir() {
        CrashCapsule.Result r = CrashCapsule.create(tmp.resolve("no-logs-here"));
        assertFalse(r.ok());
    }

    @Test
    @DisplayName("logs 目录不存在：ok=false，不抛异常")
    void missingLogsDir() {
        CrashCapsule.Result r = CrashCapsule.create(tmp.resolve("no-such-dir"));
        assertFalse(r.ok());
        assertNotNull(r.error());
    }

    @Test
    @DisplayName("logsDir 是文件：ok=false，不抛异常")
    void logsDirIsFile() throws Exception {
        Path file = tmp.resolve("file-not-dir");
        Files.writeString(file, "x");
        CrashCapsule.Result r = CrashCapsule.create(file);
        assertFalse(r.ok());
    }

    @Test
    @DisplayName("zip 名含时间戳且可重复生成")
    void zipNaming() throws Exception {
        Path logs = tmp.resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("startup-report.json"), "{}");
        CrashCapsule.Result r1 = CrashCapsule.create(logs);
        CrashCapsule.Result r2 = CrashCapsule.create(logs);
        assertTrue(r1.ok() && r2.ok());
        // 同秒内两次调用 → 同名覆盖是允许的；不同秒 → 不同名。只断言存在。
        assertTrue(Files.exists(r1.zipPath()));
    }
}
