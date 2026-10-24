package org.loader.loader.cli;

import org.loader.api.VersionInfo;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code mili new} —— 从权威模板生成一个新的 Mod 工程。
 *
 * <h2>模板的单一权威来源（A6）</h2>
 * 审计发现仓库里有五套模板/示例且互不一致：{@code mod-template} 引用已不存在的
 * JAR 并 import runtime 内部包（已过期）；{@code testmod} 面向 runtime 内部 API；
 * 只有 {@code example-mod}/{@code demo-mod} 严格使用 {@code org.loader.api.*}。
 * 本命令把这些工程中<b>验证过的正确用法</b>固化为内嵌模板：
 * <ul>
 *   <li>入口实现 {@code org.loader.api.Mod#initialize(ModContext)}（唯一合法入口）；</li>
 *   <li>只 import {@code org.loader.api.*}，绝不触碰 runtime/loader 内部包；</li>
 *   <li>三元组在<b>生成时</b>从 {@link VersionInfo} 读取 —— 模板与 Loader/ABI
 *       版本天然一致，不存在漂移；</li>
 *   <li>manifest 放 {@code META-INF/mod.json}（正式发现的两个查找点之一）。</li>
 * </ul>
 *
 * <h2>输入校验与安全（A6 验收）</h2>
 * <ul>
 *   <li>Mod ID：{@code ^[a-z][a-z0-9_-]{1,63}$}；保留字 mili/minecraft 拒绝；</li>
 *   <li>包名：至少两段，每段 {@code ^[a-z][a-z0-9_]*$}；</li>
 *   <li>版本：{@code ^\d+\.\d+\.\d+(-...)?$}；</li>
 *   <li>目标目录：不存在或为空目录才允许生成；<b>绝不</b>覆盖已有文件；</li>
 *   <li>路径安全：所有写出路径强制以目标目录为前缀（防穿越由结构保证并测试锁定）。</li>
 * </ul>
 *
 * <p>退出码：0=生成成功；1=目标目录冲突/IO 失败；2=输入校验失败。
 * 在线抓取任意模板：<b>不支持，也永远不会支持</b>（模板离线内嵌）。
 */
final class NewCommand {

    private NewCommand() {
    }

    static int run(String[] args, PrintStream out, PrintStream err, boolean tty) {
        Path target = null;
        String id = null;
        String name = null;
        String pkg = null;
        String version = "1.0.0";
        String author = "";

        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--id" -> id = requireValue(args, ++i, a);
                    case "--name" -> name = requireValue(args, ++i, a);
                    case "--package" -> pkg = requireValue(args, ++i, a);
                    case "--version" -> version = requireValue(args, ++i, a);
                    case "--author" -> author = requireValue(args, ++i, a);
                    default -> {
                        if (a.startsWith("--")) {
                            err.println("new: 未知选项 " + a);
                            return 2;
                        }
                        if (target != null) {
                            err.println("new: 一次只能指定一个目标目录");
                            return 2;
                        }
                        target = Path.of(a);
                    }
                }
            }
        } catch (IndexOutOfBoundsException missing) {
            err.println("new: 选项缺少参数值");
            return 2;
        }

        // ── 校验 ────────────────────────────────────────────────────────
        if (target == null || id == null || pkg == null) {
            err.println("new: 需要 <目标目录> --id <modId> --package <包名>");
            err.println("示例: mili new ./my-mod --id my-mod --package com.example.mymod");
            return 2;
        }
        name = name != null ? name : id;

        if (!id.matches("[a-z][a-z0-9_-]{1,63}")) {
            err.println("new: 非法 Mod ID '" + id + "'（规则：小写字母开头，"
                    + "仅含小写字母/数字/下划线/连字符，2-64 字符）");
            return 2;
        }
        if ("mili".equals(id) || "minecraft".equals(id)) {
            err.println("new: '" + id + "' 是平台保留 ID，不允许使用");
            return 2;
        }
        if (!pkg.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
            err.println("new: 非法包名 '" + pkg + "'（规则：至少两段，"
                    + "每段小写字母开头，仅含小写字母/数字/下划线）");
            return 2;
        }
        if (!version.matches("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.\\-]+)?")) {
            err.println("new: 非法版本 '" + version + "'（规则：SemVer，如 1.0.0 或 1.0.0-beta.1）");
            return 2;
        }

        // ── 目标目录安全检查 ────────────────────────────────────────────
        target = target.toAbsolutePath().normalize();
        if (Files.exists(target)) {
            if (!Files.isDirectory(target)) {
                err.println("new: 目标路径已存在且不是目录: " + target);
                return 1;
            }
            try (var list = Files.list(target)) {
                if (list.findAny().isPresent()) {
                    err.println("new: 目标目录非空，拒绝生成（防止覆盖已有文件）: " + target);
                    return 1;
                }
            } catch (IOException e) {
                err.println("new: 无法读取目标目录: " + e.getMessage());
                return 1;
            }
        }

        // ── 生成 ────────────────────────────────────────────────────────
        String className = toClassName(id);
        try {
            Map<String, String> files = template(id, name, pkg, version, author, className);
            for (Map.Entry<String, String> f : files.entrySet()) {
                writeUnder(target, f.getKey(), f.getValue());
            }
        } catch (IOException e) {
            err.println("new: 生成失败: " + e.getMessage());
            return 1;
        }

        out.println("已生成 Mod 工程: " + target);
        out.println("  modId=" + id + "  version=" + version
                + "  三元组=" + VersionInfo.CURRENT_VERSION
                + "/" + VersionInfo.ABI_VERSION + "/" + VersionInfo.TARGET_MINECRAFT);
        out.println();
        out.println("下一步:");
        out.println("  1. 确保 mili-abi 已构建（仓库根目录执行 ./gradlew :mili-abi:jar），");
        out.println("     或在 gradle.properties 里把 miliAbiJar 指向已有 JAR");
        out.println("  2. cd " + target.getFileName() + " && ./gradlew build");
        out.println("  3. 把 build/libs/*.jar 放入 Mili 分发包的 mods/ 目录");
        return 0;
    }

    private static String requireValue(String[] args, int i, String opt) {
        if (i >= args.length) {
            throw new IndexOutOfBoundsException(opt);
        }
        return args[i];
    }

    /** 模板内容；key = 相对路径。包级可见供测试校验内容。 */
    static Map<String, String> template(String id, String name, String pkg,
                                        String version, String author, String className) {
        Map<String, String> files = new LinkedHashMap<>();
        String pkgPath = pkg.replace('.', '/');
        String triple = "\"platform\":\"" + VersionInfo.CURRENT_VERSION
                + "\",\"abi\":" + VersionInfo.ABI_VERSION
                + ",\"minecraft\":\"" + VersionInfo.TARGET_MINECRAFT + "\"";

        files.put("src/main/resources/META-INF/mod.json", """
                {
                  "id": "%s",
                  "name": "%s",
                  "version": "%s",
                  "author": "%s",
                  "entrypoint": "%s.%s",
                  "mili": {%s}
                }
                """.formatted(id, name, version, author, pkg, className, triple));

        files.put("src/main/java/" + pkgPath + "/" + className + ".java", """
                package %s;

                import org.loader.api.Mod;
                import org.loader.api.ModContext;

                /**
                 * %s 的入口类。
                 *
                 * <p>规则：只 import org.loader.api.*（ABI 契约），绝不 import
                 * org.loader.runtime.* / org.loader.loader.*（平台内部），
                 * 也不直接引用 Minecraft 类 —— 版本升级时 Mod JAR 无需重编译。
                 */
                public class %s implements Mod {

                    @Override
                    public void initialize(ModContext context) {
                        context.logger().info("%s 初始化（modId=%s）");
                        // 在这里做一次性的初始化：
                        //  - context.scheduler()  提交异步任务（受 Scope 约束）
                        //  - context.events()     订阅事件
                        //  - context.registry()   声明方块/物品（仅初始化期开放）
                        //  - context.resources()  读取本 Mod 资产
                    }
                }
                """.formatted(pkg, name, className, name, id));

        files.put("src/test/java/" + pkgPath + "/" + className + "Test.java", """
                package %s;

                import org.junit.jupiter.api.Test;

                import java.nio.charset.StandardCharsets;

                import static org.junit.jupiter.api.Assertions.*;

                /**
                 * 模板自检：锁定 manifest 的三元组与入口声明。
                 * 平台对三元组是严格全等校验，这里在测试期就把漂移拦下。
                 */
                class %sTest {

                    @Test
                    void manifestIsPresentAndBound() throws Exception {
                        try (var in = getClass().getResourceAsStream("/META-INF/mod.json")) {
                            assertNotNull(in, "META-INF/mod.json 应打包在 JAR 内");
                            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                            assertTrue(json.contains("\\"mili\\""), "必须声明 mili 绑定");
                            assertTrue(json.contains("\\"id\\":\\"%s\\""));
                            assertTrue(json.contains("\\"entrypoint\\":\\"%s.%s\\""));
                        }
                    }
                }
                """.formatted(pkg, className, id, pkg, className));

        files.put("build.gradle.kts", """
                plugins {
                    `java-library`
                }

                group = "%s"
                version = "%s"

                // mili-abi JAR 的位置。默认指向同仓库产物；独立工程用
                // -PmiliAbiJar=/path/to/mili-abi-x.y.z.jar 覆盖，或在
                // gradle.properties 里固定。
                val miliAbiJar: String = (findProperty("miliAbiJar") as String?)
                    ?: rootDir.resolve("../mili-abi/build/libs/mili-abi-%s.jar").absolutePath

                dependencies {
                    // Mod 只面向 ABI 编译（compileOnly）—— ABI 由运行时的平台提供，
                    // 绝不打进 Mod JAR。
                    compileOnly(files(miliAbiJar))
                    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }

                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(%s)
                    }
                }

                tasks.test {
                    useJUnitPlatform()
                }
                """.formatted(pkg, version, VersionInfo.CURRENT_VERSION, "25"));

        files.put("settings.gradle.kts", """
                rootProject.name = "%s"
                """.formatted(id));

        files.put("gradle.properties", """
                # 指向 mili-abi JAR；随平台版本更新而更新
                miliAbiJar=
                """.formatted());

        files.put(".gitignore", """
                .gradle/
                build/
                *.class
                """);

        files.put("README.md", """
                # %s

                Mili Platform Mod。

                | 项 | 值 |
                |---|---|
                | Mod ID | `%s` |
                | 版本 | `%s` |
                | 平台绑定 | platform=%s abi=%s minecraft=%s |

                ## 构建

                ```
                ./gradlew build
                ```

                产物在 `build/libs/`，放入 Mili 分发包的 `mods/` 目录即可被
                发现与加载。入口类见 `src/main/java/%s/%s.java`。
                """.formatted(name, id, version,
                VersionInfo.CURRENT_VERSION, VersionInfo.ABI_VERSION,
                VersionInfo.TARGET_MINECRAFT, pkg.replace('.', '/'), className));

        files.put(".github/workflows/build.yml", """
                name: build

                on: [push, pull_request]

                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - name: Set up JDK 25
                        uses: actions/setup-java@v4
                        with:
                          distribution: temurin
                          java-version: '25'
                      - name: Build
                        run: ./gradlew build --no-daemon --console=plain
                """);

        return files;
    }

    /** my-cool-mod → MyCoolMod；id 已通过正则校验。 */
    private static String toClassName(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        for (String part : id.split("[-_]")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /** 写出文件，路径必须落在 targetDir 之内（防穿越，测试锁定）。 */
    private static void writeUnder(Path targetDir, String relative, String content)
            throws IOException {
        Path resolved = targetDir.resolve(relative).normalize();
        if (!resolved.startsWith(targetDir)) {
            throw new IOException("模板路径越界: " + relative);
        }
        Files.createDirectories(resolved.getParent());
        Files.writeString(resolved, content, StandardCharsets.UTF_8);
    }
}
