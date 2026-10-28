package org.loader.installer.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.loader.installer.download.Progress;
import org.loader.installer.download.VerifiedDownloader;
import org.loader.installer.json.Json;
import org.loader.installer.meta.MetaSource;
import org.loader.installer.meta.MojangMetaClient;
import org.loader.installer.meta.VersionMeta;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安装结果可靠性的回归测试 —— 全部离线，不触碰 Mojang CDN。
 *
 * <h2>这些测试在防什么</h2>
 *
 * <p>历史实现用警告文本前缀判定安装成败：
 * <pre>
 *   ok = warnings.stream().noneMatch(w -&gt; w.startsWith("Failed to install library"))
 * </pre>
 * 于是「资源下载失败」「native 解压失败」「assetIndex 缺失」都被判成成功，
 * 安装报告写 {@code ok: true}、CLI 退出码 0，用户拿到一个进不了世界的环境。
 * 下面的用例逐条钉住这些失败路径：任何一条回归都会让 {@code ok()} 变回 true。
 */
class InstallOrchestratorTest {

    private static final String CLIENT_URL = "https://piston/client.jar";
    private static final String LIB_URL = "https://libraries/demo-1.0.jar";
    private static final String INDEX_URL = "https://piston/32.json";
    private static final String LOG_URL = "https://piston/client.log4j2.xml";

    private static final byte[] CLIENT_BYTES = "client-jar-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LIB_BYTES = "library-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ASSET_BYTES = "asset-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LOG_BYTES = "<Configuration/>".getBytes(StandardCharsets.UTF_8);

    private static final String ASSET_SHA1 = VerifiedDownloader.sha1OfBytes(ASSET_BYTES);

    // ── 用例 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("全部成功 → ok=true，报告为合法 JSON 且路径往返一致")
    void allStepsSucceed(@TempDir Path gameDir) throws Exception {
        InstallOrchestrator.Report report = install(gameDir, true, fullContent(), fullMeta());

        assertTrue(report.ok(), () -> "预期成功，实际失败: " + report.failures());
        assertTrue(report.warnings().isEmpty(), () -> "不应有警告: " + report.warnings());
        assertTrue(report.clientDownloaded());
        assertEquals(1, report.librariesApplied());
        assertEquals(1, report.assetsTotal());
        assertEquals(1, report.assetsDownloaded());

        Map<String, Object> json = readReport(gameDir);
        assertEquals(Boolean.TRUE, json.get("ok"));
        // 路径必须原样往返：Windows 反斜杠若未转义，这里的解析会直接抛异常
        assertEquals(report.clientJar().toString(), json.get("clientJar"));
        assertTrue(json.get("issues") instanceof java.util.List<?>);
        assertEquals(0, ((java.util.List<?>) json.get("issues")).size());
        assertTrue(json.get("steps") instanceof java.util.List<?>);
    }

    @Test
    @DisplayName("必需依赖库下载失败 → 整体失败（不再被当成 warning 吞掉）")
    void requiredLibraryFailureFailsInstall(@TempDir Path gameDir) throws Exception {
        Map<String, byte[]> content = fullContent();
        content.remove(LIB_URL);   // 该库 URL 返回 404

        InstallOrchestrator.Report report = install(gameDir, true, content, fullMeta());

        assertFalse(report.ok(), "必需库失败必须让整体安装失败");
        InstallIssue failure = firstFailure(report, InstallIssue.Stage.LIBRARY);
        assertEquals("org.example:demo:1.0", failure.resourceId());
        assertTrue(failure.message().contains("404"), failure.message());
        assertNotNull(failure.cause(), "根因必须保留");

        // 报告落盘后同样必须是失败，不能只在内存里失败
        assertEquals(Boolean.FALSE, readReport(gameDir).get("ok"));
        assertEquals(0, report.librariesApplied());
    }

    @Test
    @DisplayName("资源对象下载失败 → 整体失败，且带 sha1 作为资源标识")
    void assetFailureFailsInstall(@TempDir Path gameDir) throws Exception {
        Map<String, byte[]> content = fullContent();
        content.remove(objectUrl(ASSET_SHA1));

        InstallOrchestrator.Report report = install(gameDir, true, content, fullMeta());

        assertFalse(report.ok(), "资源失败必须让整体安装失败");
        InstallIssue failure = firstFailure(report, InstallIssue.Stage.ASSET_OBJECT);
        assertEquals(ASSET_SHA1, failure.resourceId());
        assertEquals(1, report.assetsTotal());
        assertEquals(0, report.assetsDownloaded());
    }

    @Test
    @DisplayName("资源索引为空 → 失败（旧实现只记一条 warning 并判成功）")
    void emptyAssetIndexFailsInstall(@TempDir Path gameDir) throws Exception {
        String emptyIndex = "{\"objects\":{}}";
        byte[] emptyBytes = emptyIndex.getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> content = fullContent();
        content.put(INDEX_URL, emptyBytes);
        String meta = versionJson(sha1(CLIENT_BYTES), LIB_URL, sha1(LIB_BYTES),
                INDEX_URL, sha1(emptyBytes), loggingBlock());

        InstallOrchestrator.Report report = install(gameDir, true, content, meta);

        assertFalse(report.ok());
        firstFailure(report, InstallIssue.Stage.ASSET_INDEX);
    }

    @Test
    @DisplayName("元数据缺少 assetIndex → 失败，而不是「跳过资源」")
    void missingAssetIndexFailsInstall(@TempDir Path gameDir) throws Exception {
        String meta = versionJson(sha1(CLIENT_BYTES), LIB_URL, sha1(LIB_BYTES),
                null, null, "");

        InstallOrchestrator.Report report = install(gameDir, true, fullContent(), meta);

        assertFalse(report.ok());
        firstFailure(report, InstallIssue.Stage.ASSET_INDEX);
    }

    @Test
    @DisplayName("可选资源（日志配置）失败 → ok=true 但必须有 WARNING")
    void optionalLoggingConfigFailureIsWarning(@TempDir Path gameDir) throws Exception {
        Map<String, byte[]> content = fullContent();
        content.remove(LOG_URL);

        InstallOrchestrator.Report report = install(gameDir, true, content, fullMeta());

        assertTrue(report.ok(), () -> "可选资源失败不应阻断: " + report.failures());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("日志配置")),
                () -> "必须留下可回溯的警告: " + report.warnings());
        assertTrue(report.steps().stream().anyMatch(s ->
                        s.stage() == InstallIssue.Stage.LOGGING_CONFIG
                                && s.status() == InstallStep.Status.DEGRADED),
                "步骤状态应标记为 DEGRADED");
    }

    @Test
    @DisplayName("客户端 JAR 失败 → 整体失败，后续阶段标记为跳过")
    void clientFailureAbortsRemainingStages(@TempDir Path gameDir) throws Exception {
        Map<String, byte[]> content = fullContent();
        content.remove(CLIENT_URL);

        InstallOrchestrator.Report report = install(gameDir, true, content, fullMeta());

        assertFalse(report.ok());
        firstFailure(report, InstallIssue.Stage.CLIENT_JAR);
        assertTrue(report.steps().stream().anyMatch(s ->
                s.stage() == InstallIssue.Stage.LIBRARY && s.status() == InstallStep.Status.SKIPPED));
        assertTrue(Files.exists(gameDir.resolve("logs").resolve("install-report.json")));
    }

    @Test
    @DisplayName("元数据获取失败 → 仍写出失败报告，而不是抛异常跑掉")
    void metadataFailureStillWritesReport(@TempDir Path gameDir) throws Exception {
        MetaSource failing = new MetaSource() {
            @Override
            public VersionMeta fetchVersionMeta(String versionId) throws IOException {
                throw new IOException("manifest unavailable");
            }

            @Override
            public java.io.InputStream openStream(String url) throws IOException {
                throw new IOException("unreachable");
            }
        };
        InstallOrchestrator orchestrator = new InstallOrchestrator(
                failing, url -> {
                    throw new IOException("unreachable");
                },
                silentProgress());

        InstallOrchestrator.Report report = orchestrator.install(
                new InstallOrchestrator.Options(gameDir, "26.2", false, false, 1));

        assertFalse(report.ok());
        firstFailure(report, InstallIssue.Stage.METADATA);
        assertEquals(Boolean.FALSE, readReport(gameDir).get("ok"));
    }

    @Test
    @DisplayName("--skip-assets 是显式策略：ok=true 但留下警告且步骤为 SKIPPED")
    void skipAssetsIsExplicitPolicy(@TempDir Path gameDir) throws Exception {
        InstallOrchestrator.Report report = install(gameDir, false, fullContent(), fullMeta());

        assertTrue(report.ok());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("--skip-assets")));
        assertTrue(report.steps().stream().anyMatch(s ->
                s.stage() == InstallIssue.Stage.ASSET_INDEX && s.status() == InstallStep.Status.SKIPPED));
    }

    @Test
    @DisplayName("索引里的非法 hash 被拒绝，不会越出 assets/objects 写文件")
    void rejectsTraversalInAssetIndex(@TempDir Path gameDir) throws Exception {
        // 一个合法对象 + 一个试图穿越的 hash
        String mixedIndex = "{\"objects\": {"
                + "\"" + ASSET_SHA1 + "\": {\"hash\": \"" + ASSET_SHA1
                + "\", \"size\": " + ASSET_BYTES.length + "}, "
                + "\"../../evil.jar\": {\"hash\": \"../../evil.jar\", \"size\": 1}"
                + "}}";
        byte[] indexBytes = mixedIndex.getBytes(StandardCharsets.UTF_8);

        Map<String, byte[]> content = fullContent();
        content.put(INDEX_URL, indexBytes);
        String meta = versionJson(sha1(CLIENT_BYTES), LIB_URL, sha1(LIB_BYTES),
                INDEX_URL, sha1(indexBytes), loggingBlock());

        InstallOrchestrator.Report report = install(gameDir, true, content, meta);

        assertFalse(report.ok(), "含非法 hash 的索引必须判为失败");
        InstallIssue failure = firstFailure(report, InstallIssue.Stage.ASSET_INDEX);
        assertTrue(failure.message().contains("非法"), failure.message());
        assertEquals(1, report.assetsTotal(), "合法对象仍应被处理");
        assertFalse(Files.exists(gameDir.resolve("evil.jar")), "非法条目不得落盘到 assets 之外");
        assertFalse(Files.exists(gameDir.getParent().resolve("evil.jar")));
    }

    // ── 构造与辅助 ──────────────────────────────────────────────────────────

    /**
     * 跑一次完整安装。
     *
     * @param withAssets false 表示显式 {@code --skip-assets}
     */
    private static InstallOrchestrator.Report install(Path gameDir, boolean withAssets,
                                                      Map<String, byte[]> content,
                                                      String metaJson) throws Exception {
        VersionMeta meta = MojangMetaClient.parseVersionMeta(Json.parseObject(metaJson));
        InstallOrchestrator orchestrator = new InstallOrchestrator(
                MetaSource.of(meta), fakeStream(content), silentProgress());
        return orchestrator.install(new InstallOrchestrator.Options(
                gameDir, "26.2", !withAssets, false, 2));
    }

    private static InstallIssue firstFailure(InstallOrchestrator.Report report,
                                             InstallIssue.Stage stage) {
        return report.failures().stream()
                .filter(i -> i.stage() == stage)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "缺少阶段 " + stage + " 的失败项，实际: " + report.issues()));
    }

    private static VerifiedDownloader.MojangStreamSource fakeStream(Map<String, byte[]> content) {
        return url -> {
            byte[] data = content.get(url);
            if (data == null) {
                throw new IOException("HTTP 404 for " + url);
            }
            return new ByteArrayInputStream(data);
        };
    }

    private static Progress silentProgress() {
        return new Progress(new PrintStream(OutputStream.nullOutputStream(), true,
                StandardCharsets.UTF_8), false);
    }

    private static Map<String, Object> readReport(Path gameDir) throws IOException {
        Path report = gameDir.resolve("logs").resolve("install-report.json");
        assertTrue(Files.exists(report), "安装报告必须落盘: " + report);
        return Json.parseObject(Files.readString(report, StandardCharsets.UTF_8));
    }

    private static Map<String, byte[]> fullContent() {
        Map<String, byte[]> content = new HashMap<>();
        content.put(CLIENT_URL, CLIENT_BYTES);
        content.put(LIB_URL, LIB_BYTES);
        content.put(INDEX_URL, indexJson().getBytes(StandardCharsets.UTF_8));
        content.put(LOG_URL, LOG_BYTES);
        content.put(objectUrl(ASSET_SHA1), ASSET_BYTES);
        return content;
    }

    private static String indexJson() {
        return "{\"objects\": {\"" + ASSET_SHA1 + "\": {\"hash\": \"" + ASSET_SHA1
                + "\", \"size\": " + ASSET_BYTES.length + "}}}";
    }

    private static String objectUrl(String sha1) {
        return "https://resources.download.minecraft.net/" + sha1.substring(0, 2) + "/" + sha1;
    }

    private static String fullMeta() {
        return versionJson(sha1(CLIENT_BYTES), LIB_URL, sha1(LIB_BYTES),
                INDEX_URL, sha1(indexJson().getBytes(StandardCharsets.UTF_8)), loggingBlock());
    }

    private static String loggingBlock() {
        return """
                ,
                  "logging": {
                    "client": {"file": {"url": "%s", "sha1": "%s", "size": %d}}
                  }""".formatted(LOG_URL, sha1(LOG_BYTES), LOG_BYTES.length);
    }

    /**
     * @param indexUrl null 表示这份元数据不含 assetIndex 块
     * @param extra    追加在顶层对象末尾的 JSON 片段（日志配置等），可为空串
     */
    private static String versionJson(String clientSha1, String libUrl, String libSha1,
                                      String indexUrl, String indexSha1, String extra) {
        String assetIndex = indexUrl == null ? "" : """
                ,
                  "assetIndex": {
                    "id": "32", "url": "%s", "sha1": "%s", "size": 20, "totalSize": %d
                  }""".formatted(indexUrl, indexSha1, ASSET_BYTES.length);

        return """
                {
                  "id": "26.2",
                  "javaVersion": {"majorVersion": 25},
                  "downloads": {
                    "client": {"url": "%s", "sha1": "%s", "size": %d}
                  },
                  "libraries": [
                    {
                      "name": "org.example:demo:1.0",
                      "downloads": {
                        "artifact": {
                          "path": "org/example/demo/1.0/demo-1.0.jar",
                          "url": "%s",
                          "sha1": "%s",
                          "size": %d
                        }
                      }
                    }
                  ]%s%s
                }
                """.formatted(CLIENT_URL, clientSha1, CLIENT_BYTES.length,
                libUrl, libSha1, LIB_BYTES.length, assetIndex, extra);
    }

    private static String sha1(byte[] data) {
        return VerifiedDownloader.sha1OfBytes(data);
    }
}
