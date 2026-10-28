package org.loader.loader.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.loader.loader.util.MiliJson;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code mili doctor} 行为契约测试。
 *
 * <p>覆盖 A2 验收清单：合法/非法 fixture、循环依赖、缺失依赖、重复 ID、
 * 三元组绑定、JSON schema、退出码、zip 条目安全、只读行为。
 */
class DoctorCommandTest {

    @TempDir
    Path tmp;

    private record Out(int code, String stdout, String stderr) {
    }

    private static Out run(String... args) {
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(so, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(se, true, StandardCharsets.UTF_8);
        int code = DoctorCommand.run(args, out, err, false);
        return new Out(code, so.toString(StandardCharsets.UTF_8),
                se.toString(StandardCharsets.UTF_8));
    }

    /** mods 目录 = gameDir/mods（与 LoaderConfig 默认推导一致）。 */
    private Path modsDir() {
        return tmp.resolve("mods");
    }

    private static void writeJar(Path dir, String jarName,
                                 java.util.LinkedHashMap<String, String> entries) {
        try {
            Files.createDirectories(dir);
            Path p = dir.resolve(jarName);
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(p))) {
                for (var e : entries.entrySet()) {
                    zos.putNextEntry(new ZipEntry(e.getKey()));
                    zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String manifest(String id, String depsJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"").append(id).append("\",\"name\":\"").append(id)
                .append("\",\"version\":\"1.0.0\",\"entrypoint\":\"com.example.")
                .append(id).append(".Main\"");
        if (depsJson != null) {
            sb.append(",\"dependencies\":").append(depsJson);
        }
        sb.append(",\"mili\":{\"platform\":\"0.1.0\",\"abi\":").append(
                org.loader.api.VersionInfo.ABI_VERSION).append(",\"minecraft\":\"26.2\"}}");
        return sb.toString();
    }

    private static java.util.LinkedHashMap<String, String> manifestOnly(String json) {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        m.put("mod.json", json);
        return m;
    }

    // ── 健康场景 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("空目录（无 mods 子目录）：仅 warning，退出 0")
    void emptyDirPasses() {
        Out o = run(tmp.toString());
        assertEquals(0, o.code());
        assertTrue(o.stdout().contains("MODS_DIR_MISSING"));
    }

    @Test
    @DisplayName("合法 Mod JAR：体检通过，mods 清单含该 Mod 与绑定信息")
    void validJarPasses() {
        writeJar(modsDir(), "goodmod.jar", manifestOnly(manifest("goodmod", null)));
        Out o = run(tmp.toString());
        assertEquals(0, o.code());
        assertTrue(o.stdout().contains("goodmod"));
        assertTrue(o.stdout().contains("platform=0.1.0"));
        assertTrue(o.stdout().contains("体检通过"));
    }

    @Test
    @DisplayName("只读行为：体检不创建/修改/删除 mods 目录下任何文件")
    void readOnlyGuarantee() {
        writeJar(modsDir(), "goodmod.jar", manifestOnly(manifest("goodmod", null)));
        Path jar = modsDir().resolve("goodmod.jar");
        long sizeBefore = jar.toFile().length();
        long mtimeBefore = jar.toFile().lastModified();
        int filesBefore = modsDir().toFile().list().length;

        run(tmp.toString());

        assertEquals(filesBefore, modsDir().toFile().list().length, "不应新增文件");
        assertEquals(sizeBefore, jar.toFile().length(), "不应修改文件大小");
        assertEquals(mtimeBefore, jar.toFile().lastModified(), "不应触碰修改时间");
    }

    // ── 故障场景 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("gameDir 不存在：GAME_DIR_MISSING，退出 1")
    void missingGameDir() {
        Out o = run(tmp.resolve("nope").toString());
        assertEquals(1, o.code());
        assertTrue(o.stdout().contains("GAME_DIR_MISSING"));
    }

    @Test
    @DisplayName("坏 JSON manifest：MOD_MANIFEST_INVALID，不中断其余 Mod")
    void brokenJsonIsolated() {
        writeJar(modsDir(), "broken.jar", manifestOnly("{id: not valid json"));
        writeJar(modsDir(), "healthy.jar", manifestOnly(manifest("healthy", null)));
        Out o = run(tmp.toString());
        assertEquals(1, o.code());
        assertTrue(o.stdout().contains("MOD_MANIFEST_INVALID"));
        assertTrue(o.stdout().contains("broken.jar"));
        // 坏文件不掩盖好文件
        assertTrue(o.stdout().contains("healthy"));
    }

    @Test
    @DisplayName("JAR 无 manifest：MOD_MANIFEST_INVALID")
    void jarWithoutManifest() {
        writeJar(modsDir(), "empty.jar",
                new java.util.LinkedHashMap<>(Map.of("README.txt", "no manifest here")));
        Out o = run(tmp.toString());
        assertEquals(1, o.code());
        assertTrue(o.stdout().contains("MOD_MANIFEST_INVALID"));
        assertTrue(o.stdout().contains("empty.jar"));
    }

    @Test
    @DisplayName("重复 Mod ID：MOD_ID_DUPLICATE")
    void duplicateIds() {
        writeJar(modsDir(), "a-one.jar", manifestOnly(manifest("dup", null)));
        writeJar(modsDir(), "a-two.jar", manifestOnly(manifest("dup", null)));
        Out o = run(tmp.toString());
        assertEquals(1, o.code());
        assertTrue(o.stdout().contains("MOD_ID_DUPLICATE"));
    }

    @Test
    @DisplayName("缺失必需依赖：DEP_MISSING；可选依赖缺失只是 INFO")
    void missingDependencies() {
        writeJar(modsDir(), "needy.jar", manifestOnly(manifest("needy",
                "[{\"modId\":\"ghost\",\"required\":true},"
                        + "{\"modId\":\"phantom\",\"required\":false}]")));
        Out o = run(tmp.toString());
        assertEquals(1, o.code());
        assertTrue(o.stdout().contains("DEP_MISSING"));
        assertTrue(o.stdout().contains("DEP_OPTIONAL_MISSING"));
    }

    @Test
    @DisplayName("循环依赖：DEP_CYCLE 报告完整闭合环路径")
    void dependencyCycle() {
        writeJar(modsDir(), "a.jar", manifestOnly(manifest("cyc_a", "[{\"modId\":\"cyc_b\"}]")));
        writeJar(modsDir(), "b.jar", manifestOnly(manifest("cyc_b", "[{\"modId\":\"cyc_a\"}]")));
        Out o = run(tmp.toString());
        assertEquals(1, o.code());
        assertTrue(o.stdout().contains("DEP_CYCLE"));
        assertTrue(o.stdout().contains("cyc_a -> cyc_b -> cyc_a"));
    }

    @Test
    @DisplayName("未声明绑定：MOD_PLATFORM_MISSING")
    void unboundManifest() {
        String unbound = "{\"id\":\"nobind\",\"name\":\"n\",\"version\":\"1.0.0\","
                + "\"entrypoint\":\"x\"}";
        writeJar(modsDir(), "nobind.jar", manifestOnly(unbound));
        Out o = run(tmp.toString());
        assertEquals(1, o.code());
        assertTrue(o.stdout().contains("MOD_PLATFORM_MISSING"));
    }

    @Test
    @DisplayName("ABI 不匹配：MOD_ABI_MISMATCH")
    void abiMismatch() {
        String wrongAbi = manifest("wrongabi", null)
                .replace("\"abi\":" + org.loader.api.VersionInfo.ABI_VERSION, "\"abi\":99");
        writeJar(modsDir(), "wrongabi.jar", manifestOnly(wrongAbi));
        Out o = run(tmp.toString());
        assertEquals(1, o.code());
        assertTrue(o.stdout().contains("MOD_ABI_MISMATCH"));
    }

    @Test
    @DisplayName("自依赖：DEP_SELF")
    void selfDependency() {
        writeJar(modsDir(), "self.jar", manifestOnly(manifest("selfish", "[{\"modId\":\"selfish\"}]")));
        Out o = run(tmp.toString());
        assertEquals(1, o.code());
        assertTrue(o.stdout().contains("DEP_SELF"));
    }

    @Test
    @DisplayName("zip 条目含路径穿越标记：ZIP_ENTRY_UNSAFE（ERROR）")
    void zipTraversalFlagged() {
        java.util.LinkedHashMap<String, String> entries = manifestOnly(manifest("evil", null));
        entries.put("../evil.txt", "pwn");
        writeJar(modsDir(), "evil.jar", entries);
        Out o = run(tmp.toString());
        assertEquals(1, o.code());
        assertTrue(o.stdout().contains("ZIP_ENTRY_UNSAFE"));
    }

    @Test
    @DisplayName("捆绑平台类：MOD_BUNDLES_PLATFORM_CLASSES 警告（不影响退出码）")
    void bundlesPlatformClasses() {
        java.util.LinkedHashMap<String, String> entries = manifestOnly(manifest("bundler", null));
        entries.put("org/loader/loader/LoaderMain.class", "bytes");
        writeJar(modsDir(), "bundler.jar", entries);
        Out o = run(tmp.toString());
        assertEquals(0, o.code(), "warning 不应影响退出码");
        assertTrue(o.stdout().contains("MOD_BUNDLES_PLATFORM_CLASSES"));
    }

    // ── 输出形态 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("--json：稳定 schema，可被 MiliJson 解析，summary 计数正确")
    void jsonSchema() {
        writeJar(modsDir(), "bad.jar", manifestOnly("{broken"));
        Out o = run(tmp.toString(), "--json");
        assertEquals(1, o.code());
        Map<String, Object> json = MiliJson.parseObject(o.stdout().trim());
        assertEquals("1.0", json.get("schemaVersion"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) json.get("summary");
        assertNotNull(summary);
        assertEquals(1.0, ((Number) summary.get("errors")).doubleValue());
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> findings =
                (java.util.List<Map<String, Object>>) json.get("findings");
        assertTrue(findings.stream().anyMatch(
                f -> "MOD_MANIFEST_INVALID".equals(f.get("id"))));
    }

    @Test
    @DisplayName("--graph：输出 Mermaid 依赖图")
    void mermaidGraph() {
        writeJar(modsDir(), "a.jar", manifestOnly(manifest("ga", "[{\"modId\":\"gb\"}]")));
        writeJar(modsDir(), "b.jar", manifestOnly(manifest("gb", null)));
        Out o = run(tmp.toString(), "--graph");
        assertEquals(0, o.code());
        assertTrue(o.stdout().contains("```mermaid"));
        assertTrue(o.stdout().contains("graph TD"));
        assertTrue(o.stdout().contains("-->"));
    }

    @Test
    @DisplayName("用法错误：未知选项退出 2")
    void usageError() {
        assertEquals(2, run(tmp.toString(), "--frobnicate").code());
    }
}
