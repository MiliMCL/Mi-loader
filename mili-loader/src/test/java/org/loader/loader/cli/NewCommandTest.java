package org.loader.loader.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.loader.api.VersionInfo;
import org.loader.loader.util.MiliJson;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** mili new 脚手架：生成正确性、输入校验、目录安全。 */
class NewCommandTest {

    @TempDir
    Path tmp;

    private record Out(int code, String stdout, String stderr) {
    }

    private static Out run(String... args) {
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(so, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(se, true, StandardCharsets.UTF_8);
        int code = NewCommand.run(args, out, err, false);
        return new Out(code, so.toString(StandardCharsets.UTF_8),
                se.toString(StandardCharsets.UTF_8));
    }

    private Out generate(String dirName, String id, String pkg) {
        return run(tmp.resolve(dirName).toString(), "--id", id, "--package", pkg);
    }

    @Test
    @DisplayName("快乐路径：全部文件生成，manifest 三元组与 VersionInfo 实时一致")
    void happyPath() throws Exception {
        Out o = generate("my-mod", "my-mod", "com.example.mymod");
        assertEquals(0, o.code(), o.stderr());

        Path root = tmp.resolve("my-mod");
        assertTrue(Files.exists(root.resolve("src/main/resources/META-INF/mod.json")));
        assertTrue(Files.exists(root.resolve("src/main/java/com/example/mymod/MyMod.java")));
        assertTrue(Files.exists(root.resolve("src/test/java/com/example/mymod/MyModTest.java")));
        assertTrue(Files.exists(root.resolve("build.gradle.kts")));
        assertTrue(Files.exists(root.resolve("settings.gradle.kts")));
        assertTrue(Files.exists(root.resolve("README.md")));
        assertTrue(Files.exists(root.resolve(".gitignore")));
        assertTrue(Files.exists(root.resolve(".github/workflows/build.yml")));

        // manifest：合法 JSON、三元组实时来自 VersionInfo（模板不漂移）
        Map<String, Object> manifest = MiliJson.parseObject(
                Files.readString(root.resolve("src/main/resources/META-INF/mod.json")));
        assertEquals("my-mod", manifest.get("id"));
        assertEquals("com.example.mymod.MyMod", manifest.get("entrypoint"));
        @SuppressWarnings("unchecked")
        Map<String, Object> mili = (Map<String, Object>) manifest.get("mili");
        assertEquals(VersionInfo.CURRENT_VERSION, mili.get("platform"));
        assertEquals((double) VersionInfo.ABI_VERSION, ((Number) mili.get("abi")).doubleValue());
        assertEquals(VersionInfo.TARGET_MINECRAFT, mili.get("minecraft"));

        // 入口类：只 import API，实现 Mod 接口（对 import 语句断言，避免误伤
        // 模板中解释"不许 import 什么"的注释文本）
        String main = Files.readString(root.resolve("src/main/java/com/example/mymod/MyMod.java"));
        assertTrue(main.contains("package com.example.mymod;"));
        assertTrue(main.contains("implements Mod"));
        assertTrue(main.contains("void initialize(ModContext"));
        assertFalse(main.contains("import org.loader.runtime"),
                "模板不得 import runtime 内部包");
        assertFalse(main.contains("import org.loader.loader"),
                "模板不得 import loader 内部包");

        // 构建脚本锁定 Java 25 工具链
        String build = Files.readString(root.resolve("build.gradle.kts"));
        assertTrue(build.contains("JavaLanguageVersion.of(25)"));
    }

    @Test
    @DisplayName("生成出的工程文件全部位于目标目录内（防穿越）")
    void allFilesUnderTarget() throws Exception {
        Out o = generate("scoped", "scoped", "a.b.c");
        assertEquals(0, o.code());
        try (Stream<Path> walk = Files.walk(tmp)) {
            var outside = walk.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(tmp.resolve("scoped")))
                    .collect(Collectors.toList());
            // tmp 是 @TempDir，可能还有 JUnit 自己的文件；只断言没有模板产物外溢
            assertTrue(outside.stream().noneMatch(p ->
                            p.getFileName().toString().endsWith(".java")
                                    || p.getFileName().toString().endsWith(".kts")
                                    || p.getFileName().toString().equals("mod.json")),
                    "模板产物不得越出目标目录: " + outside);
        }
    }

    @Test
    @DisplayName("ID 校验：大写/数字开头/超短一律拒绝（退出 2）")
    void idValidation() {
        assertEquals(2, run(tmp.resolve("a").toString(), "--id", "Mili", "--package", "a.b").code());
        assertEquals(2, run(tmp.resolve("b").toString(), "--id", "9bad", "--package", "a.b").code());
        assertEquals(2, run(tmp.resolve("c").toString(), "--id", "x", "--package", "a.b").code());
        assertEquals(2, run(tmp.resolve("d").toString(), "--id", "has space", "--package", "a.b").code());
    }

    @Test
    @DisplayName("保留 ID 拒绝")
    void reservedIds() {
        assertEquals(2, run(tmp.resolve("e").toString(), "--id", "mili", "--package", "a.b").code());
        assertEquals(2, run(tmp.resolve("f").toString(), "--id", "minecraft", "--package", "a.b").code());
    }

    @Test
    @DisplayName("包名校验：单段/大写段拒绝")
    void packageValidation() {
        assertEquals(2, run(tmp.resolve("g").toString(), "--id", "okid", "--package", "one").code());
        assertEquals(2, run(tmp.resolve("h").toString(), "--id", "okid", "--package", "a.Bad").code());
        assertEquals(2, run(tmp.resolve("i").toString(), "--id", "okid", "--package", "a.9x").code());
    }

    @Test
    @DisplayName("版本校验：非法 SemVer 拒绝")
    void versionValidation() {
        assertEquals(2, run(tmp.resolve("j").toString(), "--id", "okid",
                "--package", "a.b", "--version", "1.0").code());
        assertEquals(0, run(tmp.resolve("k").toString(), "--id", "okid",
                "--package", "a.b", "--version", "1.2.3-beta.1").code());
    }

    @Test
    @DisplayName("目录安全：非空目录拒绝（退出 1），空目录允许")
    void directorySafety() throws Exception {
        Path occupied = tmp.resolve("occupied");
        Files.createDirectories(occupied);
        Files.writeString(occupied.resolve("existing.txt"), "keep me");

        Out o = run(occupied.toString(), "--id", "okid", "--package", "a.b");
        assertEquals(1, o.code());
        assertTrue(o.stderr().contains("非空"));
        // 已有文件未被触碰
        assertEquals("keep me", Files.readString(occupied.resolve("existing.txt")));
        assertFalse(Files.exists(occupied.resolve("build.gradle.kts")));

        // 空目录允许
        Path empty = tmp.resolve("empty");
        Files.createDirectories(empty);
        assertEquals(0, run(empty.toString(), "--id", "okid", "--package", "a.b").code());
    }

    @Test
    @DisplayName("用法错误：缺参数退出 2")
    void missingArgs() {
        assertEquals(2, run().code());
        assertEquals(2, run(tmp.resolve("l").toString(), "--id", "okid").code());
        assertEquals(2, run(tmp.resolve("m").toString(), "--id", "okid", "--package").code());
        assertEquals(2, run(tmp.resolve("n").toString(), "--id", "okid",
                "--package", "a.b", "--bogus").code());
    }

    @Test
    @DisplayName("连字符 ID 的类名转换：my-cool-mod → MyCoolMod")
    void classNameConversion() throws Exception {
        Out o = generate("cool", "my-cool-mod", "com.example.cool");
        assertEquals(0, o.code());
        assertTrue(Files.exists(tmp.resolve("cool/src/main/java/com/example/cool/MyCoolMod.java")));
    }
}
