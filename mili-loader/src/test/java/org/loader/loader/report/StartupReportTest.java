package org.loader.loader.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.loader.loader.util.MiliJson;
import org.loader.runtime.diagnostics.StartupTimeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 启动报告 schema 与失败安全测试。 */
class StartupReportTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("失败报告：schema 齐全，可被 MiliJson 解析")
    void schemaIsComplete() {
        StartupTimeline timeline = new StartupTimeline("bootstrap");
        timeline.mark("parseArgs");
        timeline.mark("scanMods");
        Exception boom = new IllegalStateException("模拟 Mod 初始化失败");
        String json = StartupReport.renderFailureReport(tmp, boom, timeline, null);

        Map<String, Object> root = MiliJson.parseObject(json);
        assertEquals("1.0", root.get("schemaVersion"));
        assertEquals("failure", root.get("outcome"));
        assertNotNull(root.get("generatedAt"));

        @SuppressWarnings("unchecked")
        Map<String, Object> platform = (Map<String, Object>) root.get("platform");
        assertEquals("0.1.0", platform.get("miliVersion"));
        assertEquals(1.0, ((Number) platform.get("abiVersion")).doubleValue());
        assertEquals("26.2", platform.get("minecraftVersion"));

        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) root.get("environment");
        assertNotNull(env.get("javaVersion"));
        assertNotNull(env.get("osName"));
        assertNotNull(env.get("osArch"));

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) root.get("error");
        assertEquals("java.lang.IllegalStateException", error.get("type"));
        assertEquals("PLATFORM_UNKNOWN", error.get("errorCode"));
        assertTrue(((String) error.get("message")).contains("模拟 Mod 初始化失败"));

        assertEquals("scanMods", root.get("phase")); // 最后一个已记录区间

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timelineArr =
                (List<Map<String, Object>>) root.get("timeline");
        assertEquals(2, timelineArr.size());
        assertEquals("parseArgs", timelineArr.get(0).get("name"));

        assertInstanceOf(List.class, root.get("mods"));
    }

    @Test
    @DisplayName("错误消息中的隐私被脱敏后才落盘")
    void errorIsSanitized() {
        String home = System.getProperty("user.home");
        Exception boom = new RuntimeException("日志位于 " + home + "\\logs\\x.log");
        String json = StartupReport.renderFailureReport(tmp, boom, null, null);
        assertFalse(json.contains(home), "报告泄露主目录路径");
        assertTrue(json.contains("<home>"));
    }

    @Test
    @DisplayName("null timeline / null mods：phase=unknown、空数组，不抛异常")
    void nullInputsAreTolerated() {
        String json = StartupReport.renderFailureReport(tmp,
                new RuntimeException("x"), null, null);
        Map<String, Object> root = MiliJson.parseObject(json);
        assertEquals("unknown", root.get("phase"));
        assertTrue(((List<?>) root.get("timeline")).isEmpty());
        assertTrue(((List<?>) root.get("mods")).isEmpty());
    }

    @Test
    @DisplayName("onLaunchFailure 真实落盘 startup-report.json 且不抛出")
    void onLaunchFailureWritesReport() throws Exception {
        System.setProperty("mili.crashCapsule", "false"); // zip 单独测
        try {
            assertDoesNotThrow(() ->
                    StartupReport.onLaunchFailure(tmp,
                            new RuntimeException("boom"), null, null));
            Path report = tmp.resolve("logs").resolve("startup-report.json");
            assertTrue(Files.exists(report));
            Map<String, Object> root = MiliJson.parseObject(Files.readString(report));
            assertEquals("failure", root.get("outcome"));
        } finally {
            System.clearProperty("mili.crashCapsule");
        }
    }

    @Test
    @DisplayName("报告失败不覆盖原始异常：gameDir 指向文件等病态输入也不抛")
    void hostileInputsNeverThrow() {
        Path fileNotDir = tmp.resolve("i-am-a-file");
        assertDoesNotThrow(() -> Files.writeString(fileNotDir, "x"));
        // gameDir 是文件 → createDirectories 会失败 → 内部兜底
        assertDoesNotThrow(() ->
                StartupReport.onLaunchFailure(fileNotDir,
                        new RuntimeException("boom"), null, null));
        // 全 null
        assertDoesNotThrow(() ->
                StartupReport.onLaunchFailure(null, null, null, null));
    }

    @Test
    @DisplayName("默认开启 crashCapsule：onLaunchFailure 会生成本地 ZIP")
    void capsuleGeneratedByDefault() {
        System.clearProperty("mili.crashCapsule");
        assertDoesNotThrow(() ->
                StartupReport.onLaunchFailure(tmp,
                        new RuntimeException("boom"), null, null));
        try (var stream = Files.list(tmp.resolve("logs"))) {
            assertTrue(stream.anyMatch(p ->
                    p.getFileName().toString().startsWith("crash-report-")),
                    "应生成 crash-report-*.zip");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("mili.crashCapsule=false：不生成 ZIP")
    void capsuleCanBeDisabled() {
        System.setProperty("mili.crashCapsule", "false");
        try {
            StartupReport.onLaunchFailure(tmp, new RuntimeException("boom"), null, null);
            try (var stream = Files.list(tmp.resolve("logs"))) {
                assertTrue(stream.noneMatch(p ->
                        p.getFileName().toString().startsWith("crash-report-")));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } finally {
            System.clearProperty("mili.crashCapsule");
        }
    }
}
