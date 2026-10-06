// mili-loader — Mili Platform Loader (bootstrap + discovery + classloading)
//
// Produces the single fat platform JAR: mili-<version>-mc<minecraft>.jar
//
// Dependency direction (enforced):
//   loader → minecraft-integration → runtime → abi (abi is zero-dep)
//
// Fat JAR via the Gradle Shadow plugin (com.github.johnrengelman.shadow).
// Shadow merges all runtimeClasspath JARs, merges META-INF/services entries,
// and drops signature files from nested JARs.

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile

plugins {
    `java-library`
    id("com.gradleup.shadow")
}

// ── 版本单一来源（由根项目从 gradle.properties 强制注入，此处只读取） ────────
val miliPlatformVersion: String = rootProject.extra["miliPlatformVersion"] as String
val miliAbiVersion: String = rootProject.extra["miliAbiVersion"] as String
val minecraftVersion: String = rootProject.extra["minecraftVersion"] as String
val javaVersion: String = rootProject.extra["javaVersion"] as String
val asmVersion: String = rootProject.extra["asmVersion"] as String


dependencies {
    implementation(project(":mili-runtime"))
    implementation(project(":mili-minecraft-integration"))
    implementation(project(":mili-abi"))
    // Installer is bundled INTO the fat JAR: LoaderMain reflectively invokes
    // org.loader.installer.InstallerMain when no Minecraft is present in the
    // game dir, so a stock distribution can bootstrap itself on first run.
    implementation(project(":mili-installer"))

    // ASM lets the api-boundary test introspect the loader's compiled classes
    // and flag any accidental reference to runtime-internal packages.
    // Version from gradle.properties (single source of truth) — Minecraft 26.2
    // is class major 69 (Java 25); ASM 9.7 cannot read it.
    testImplementation("org.ow2.asm:asm:$asmVersion")
}

// ---------------------------------------------------------------------------
// 1. Generate platform.json descriptor with Minecraft artifact fingerprint.
// ---------------------------------------------------------------------------
tasks.named<Copy>("processResources") {
    doLast {
        val platformVersion = miliPlatformVersion
        val abiVersion = miliAbiVersion
        val minecraftVersion = minecraftVersion
        val javaVersion = javaVersion
        val platformId = "mili-${platformVersion}-mc${minecraftVersion}"
        val timestamp = Instant.now().toString()

        val mcSha = minecraftArtifactSha256(minecraftVersion)

        val metaDir = layout.buildDirectory.dir("resources/main/META-INF/mili").get().asFile
        metaDir.mkdirs()
        val metaFile = File(metaDir, "platform.json")

        val body = buildString {
            append("{\n")
            append("  \"platform\": \"$platformVersion\",\n")
            append("  \"abi\": $abiVersion,\n")
            append("  \"minecraft\": \"$minecraftVersion\",\n")
            append("  \"java\": $javaVersion,\n")
            append("  \"platformId\": \"$platformId\",\n")
            append("  \"buildTimestamp\": \"$timestamp\",\n")
            append("  \"minecraftArtifact\": {\n")
            append("    \"version\": \"$minecraftVersion\",\n")
            append("    \"sha256\": \"$mcSha\"\n")
            append("  }\n")
            append("}\n")
        }
        metaFile.writeText(body)
        logger.lifecycle("[platform] Generated ${metaFile.absolutePath} (mc.sha256=${mcSha.take(16)}...)")
    }
}

/** Resolve the Minecraft build-input JAR and compute its SHA-256 (best-effort — degrades to local file hash or "unknown"). */
fun minecraftArtifactSha256(version: String): String {
    val path: String? = rootProject.findProperty("minecraftArtifact") as String?
        ?: System.getenv("MINECRAFT_ARTIFACT")
    val jar: File? = when {
        !path.isNullOrBlank() -> {
            val f = File(path)
            // Relative paths must be anchored to the project, not the daemon CWD.
            if (f.isAbsolute) f
            else rootProject.layout.projectDirectory.file(path).asFile.absoluteFile
        }
        else -> {
            val dirs = listOf(
                rootProject.layout.projectDirectory.dir("test_client").asFile,
                layout.projectDirectory.dir("test_client").asFile
            )
            dirs.filter { it.isDirectory }
                .mapNotNull { d -> d.listFiles { f -> f.name.endsWith(".jar") && f.name.contains(version) }?.firstOrNull() }
                .firstOrNull()
        }
    }
    if (jar == null || !jar.exists()) return "unknown"
    val digest = MessageDigest.getInstance("SHA-256")
    jar.inputStream().use { input ->
        val buf = ByteArray(8192)
        var n = input.read(buf)
        while (n > 0) { digest.update(buf, 0, n); n = input.read(buf) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// ---------------------------------------------------------------------------
// 2. Fat JAR — merge our own classes + all runtimeClasspath JARs (abi, runtime,
// minecraft-integration, and future transitive deps) into a single platform
// JAR using a plain Jar task. Archive: mili-<v>-mc<mc>.jar (no -all classifier).
// ---------------------------------------------------------------------------
// Shadow plugin provides the `shadowJar` task. Configure it to produce the
// single fat platform JAR. Gradle wires shadowJar to depend on `classes`.

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    group = "build"
    description = "Assembles the Mili platform fat JAR (all modules merged via Shadow)"

    archiveBaseName.set("mili")
    archiveVersion.set("${miliPlatformVersion}-mc${minecraftVersion}")
    archiveClassifier.set("")

    mergeServiceFiles()

    manifest {
        attributes(
            "Main-Class" to "org.loader.loader.LoaderMain",
            "Implementation-Title" to "Mili Platform",
            "Implementation-Version" to version,
            "Mili-Platform" to miliPlatformVersion,
            "Mili-Abi" to miliAbiVersion,
            "Mili-Minecraft" to minecraftVersion,
            "Multi-Release" to "false",
            "Sealed" to "false"
        )
    }

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// Disable the plain jar task — the platform ships only the fat JAR.
tasks.named<Jar>("jar") {
    enabled = false
}

// `build` should produce the shadow JAR directly.
tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

// ── verifyPlatformJar — integrity check on the platform JAR ────────────────
val verifyPlatformJar = tasks.register<Task>("verifyPlatformJar") {
    group = "verification"
    description = "Validates platform JAR has expected Mili modules + META-INF mili/minecraft.json fingerprint."

    dependsOn(tasks.named("shadowJar"))
    val platformVersion = miliPlatformVersion
    val minecraftVersion = minecraftVersion
    inputs.file(layout.buildDirectory.file("libs/mili-${platformVersion}-mc${minecraftVersion}.jar"))
    outputs.file(layout.buildDirectory.file("verification/platform-jar.verified"))

    doLast {
        val platformVersion = miliPlatformVersion
        val minecraftVersion = minecraftVersion
        val jar = layout.buildDirectory.file("libs/mili-${platformVersion}-mc${minecraftVersion}.jar").get().asFile
        require(jar.exists()) { "Platform JAR missing: ${jar.absolutePath}" }

        var mcClasses = 0; var miliClasses = 0; var hasPlatformJson = false; var hasMinecraftJson = false
        var totalEntries = 0
        var platformJsonText = ""; var minecraftJsonText = ""

        val zf = ZipFile(jar)
        try {
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                totalEntries++
                when (entry.name) {
                    "META-INF/mili/platform.json" -> { hasPlatformJson = true; platformJsonText = zf.getInputStream(entry).bufferedReader().readText() }
                    "META-INF/mili/minecraft.json" -> { hasMinecraftJson = true; minecraftJsonText = zf.getInputStream(entry).bufferedReader().readText() }
                }
                if (entry.name.startsWith("net/minecraft/") && entry.name.endsWith(".class")) mcClasses++
                if (entry.name.startsWith("org/loader/") && entry.name.endsWith(".class")) miliClasses++
            }
        } finally { zf.close() }

        require(hasPlatformJson) { "META-INF/mili/platform.json missing" }
        require(hasMinecraftJson) { "META-INF/mili/minecraft.json (MC build fingerprint) missing" }
        require(platformJsonText.contains(minecraftVersion)) { "platform.json missing minecraft=${minecraftVersion}" }

        // ── 分发边界红线：平台 JAR 绝不可包含 Minecraft 类 ──────────────────
        // 这条断言过去只是打日志（mcClasses 仅用于展示），等于没有约束。
        require(mcClasses == 0) {
            "分发边界违规：平台 JAR 含 $mcClasses 个 net.minecraft.* 类。" +
            "Mili 不得重新分发 Minecraft —— Minecraft 只能作为构建输入。"
        }
        logger.lifecycle("[verify] 分发边界 OK —— 平台 JAR 中 net.minecraft.* 类数 = 0")

        val marker = File(layout.buildDirectory.get().asFile, "verification/platform-jar.verified")
        marker.parentFile.mkdirs()
        marker.writeText("verified_at=${Instant.now()}\ntotal=${totalEntries}\nmcClasses=${mcClasses}\nmiliClasses=${miliClasses}\n")
        logger.lifecycle("[verify] Platform JAR OK — ${totalEntries} entries (${mcClasses} net.minecraft, ${miliClasses} org.loader)")
    }
}

// -----------------------------------------------------------------------------
// 3. releaseArtifacts — produce release layout under build/release/:
//   mili-<v>-mc<mc>.jar
//   SHA256SUMS
//   release-manifest.json
// -----------------------------------------------------------------------------
tasks.register("releaseArtifacts") {
    group = "release"
    description = "Build fat JAR + SHA256SUMS + release-manifest.json"

    dependsOn(tasks.named("shadowJar"))

    doLast {
        val platformVersion = miliPlatformVersion
        val minecraftVersion = minecraftVersion
        val javaVersion = javaVersion
        val abiVersion = miliAbiVersion
        val artifactBase = "mili-${platformVersion}-mc${minecraftVersion}"

        // Shadow writes the fat JAR to build/libs/<archiveBaseName>-<version>.jar
        // (Gradle convention). Compute the path directly to avoid a compile-time
        // dependency on the Shadow plugin classes.
        val shadowJarFile = layout.buildDirectory.file("libs/${artifactBase}.jar").get().asFile

        val releaseDir = layout.buildDirectory.dir("release").get().asFile
        releaseDir.mkdirs()
        val destJar = File(releaseDir, "${artifactBase}.jar")
        shadowJarFile.copyTo(destJar, overwrite = true)

        // Compute SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        shadowJarFile.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }

        // SHA256SUMS (GNU format: "<hash>  <filename>")
        File(releaseDir, "SHA256SUMS").writeText("$sha  ${destJar.name}\n")

        // Resolve git commit/tag; degrade gracefully if git is unavailable.
        var commit = "unknown"
        var tag = "unknown"
        try {
            val procCommit = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .directory(rootProject.projectDir).start()
            commit = procCommit.inputStream.bufferedReader().readText().trim()
            procCommit.waitFor()
            val procTag = ProcessBuilder("git", "describe", "--tags", "--exact-match")
                .directory(rootProject.projectDir).start()
            val tagRead = procTag.inputStream.bufferedReader().readText().trim()
            if (procTag.waitFor() == 0 && tagRead.isNotEmpty()) tag = tagRead
        } catch (_: Exception) { /* keep defaults */ }

        val timestamp = Instant.now().toString()

        // minecraft SHA for release manifest (MC build input fingerprint)
        val mcSha = minecraftArtifactSha256(minecraftVersion)

        // release-manifest.json
        val modulesJson = "{ \"abi\": \"$platformVersion\", \"runtime\": \"$platformVersion\", \"loader\": \"$platformVersion\", \"minecraftIntegration\": \"$platformVersion\" }"

        val manifestJson = buildString {
            append("{\n")
            append("  \"platformId\": \"${artifactBase}\",\n")
            append("  \"version\": \"$platformVersion\",\n")
            append("  \"abi\": $abiVersion,\n")
            append("  \"minecraft\": \"$minecraftVersion\",\n")
            append("  \"java\": $javaVersion,\n")
            append("  \"commit\": \"$commit\",\n")
            append("  \"tag\": \"$tag\",\n")
            append("  \"buildTimestamp\": \"$timestamp\",\n")
            append("  \"modules\": $modulesJson,\n")
            append("  \"minecraftBuild\": {\n")
            append("    \"version\": \"$minecraftVersion\",\n")
            append("    \"artifactSha256\": \"$mcSha\",\n")
            append("    \"decompiled\": true\n")
            append("  },\n")
            append("  \"assets\": {\n")
            append("    \"platform\": \"${destJar.name}\",\n")
            append("    \"sha256\": \"$sha\"\n")
            append("  }\n")
            append("}\n")
        }
        File(releaseDir, "release-manifest.json").writeText(manifestJson)

        logger.lifecycle("[release] ${shadowJarFile.absolutePath}")
        logger.lifecycle("[release] SHA-256: $sha")
        logger.lifecycle("[release] Manifest : ${File(releaseDir, "release-manifest.json").absolutePath}")
    }
}

// -----------------------------------------------------------------------------
// 4. distTar / distZip — 完整分发包（README 中长期缺失的实现）
//
// 产出可直接解压运行的目录：
//   mili-<v>-mc<mc>/
//     bin/mili-loader(.bat)     启动脚本（首次运行自动安装 Minecraft）
//     core/mili-<v>-mc<mc>.jar  平台 fat JAR（内含 installer）
//     mods/                     放 Mod JAR
//     README.txt                使用说明
//
// 关键：Minecraft 本体不在分发包内，由启动脚本在首次运行时从 Mojang
// 官方 CDN 拉取并校验 SHA-1 —— 既是 EULA 要求，也让分发包保持在 KB 级。
// -----------------------------------------------------------------------------

fun distDirName(): String =
    "mili-${miliPlatformVersion}-mc${minecraftVersion}"

// 平台 fat JAR 的产出路径（provider 形式，配置期不要求文件存在）。
val platformJarFileProvider = layout.buildDirectory.file(
    "libs/mili-${miliPlatformVersion}-mc${minecraftVersion}.jar"
)

// 两种格式共用同一份内容规格。
//
// 布局必须精确为 <root>/{core,bin,mods,README.txt}。踩过的坑：
//   * 裸 into("mods") 后紧跟 from(...) → 后续 from 落到兄弟层级
//   * into("README.txt")         → Gradle 视其为目录，产出 README.txt/README.txt
// 因此每一项都用自己的 into(...) 块，README 也不例外（into 传父目录而非文件名）。
fun configureDistContents(spec: CopySpec) {
    val root = distDirName()
    spec.into(root) {
        into("core") {
            from(platformJarFileProvider)
        }
        into("bin") {
            from(layout.projectDirectory.dir("distribution/bin"))
        }
        into("mods") {
            from(layout.projectDirectory.file("distribution/mods/.keep"))
        }
        from(layout.projectDirectory.file("distribution/README.txt"))
    }
}

val distTar = tasks.register<Tar>("distTar") {
    group = "distribution"
    description = "Assembles a ready-to-run Mili distribution (tar.gz)"
    dependsOn(tasks.named("shadowJar"))

    archiveBaseName.set("mili")
    archiveVersion.set(
        "${miliPlatformVersion}-mc${minecraftVersion}"
    )
    // Gradle defaults GZIP-compressed tars to .tgz; the release notes and
    // README both say .tar.gz, so pin the full name.
    archiveExtension.set("tar.gz")
    compression = Compression.GZIP
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    filePermissions { unix("rwxr-xr-x") }

    configureDistContents(this)
}

val distZip = tasks.register<Zip>("distZip") {
    group = "distribution"
    description = "Assembles a ready-to-run Mili distribution (zip)"
    dependsOn(tasks.named("shadowJar"))

    archiveBaseName.set("mili")
    archiveVersion.set(
        "${miliPlatformVersion}-mc${minecraftVersion}"
    )
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    configureDistContents(this)
}

tasks.named("build") { dependsOn(distTar) }

// ── 启动脚本编码校验 ────────────────────────────────────────────────────────
// 踩过的坑：mili-loader.bat 曾经是「UTF-8 中文注释 + LF 行尾」。cmd.exe 在
// OEM 代码页（中文系统为 GBK/936）下无法正确切分 LF-only 文件的命令边界，
// 于是一行被劈成碎片，报出：
//   '浠?Mojang' 不是内部或外部命令
//   'local' 不是内部或外部命令
//   批处理参数替换中的路径运算符的下列用法无效: %~fI"
// 更隐蔽的是：REM 注释里写一个字面百分号也会被 cmd 展开并破坏解析。
//
// 因此这里强制三条硬规则，任何一条不满足即构建失败：
//   1. 纯 ASCII（无 BOM）—— cmd 不需要猜代码页
//   2. CRLF 行尾，且不允许出现裸 LF
//   3. REM 注释行内不出现字面百分号
val launcherScripts = tasks.register("launcherScriptCheck") {
    group = "verification"
    description = "校验 bin/ 启动脚本为纯 ASCII + CRLF，且 REM 注释不含字面百分号"

    val scripts = listOf(
        layout.projectDirectory.file("distribution/bin/mili-loader.bat")
    )
    inputs.files(scripts)
    // 无输出：这是校验任务，每次都跑

    doLast {
        val problems = mutableListOf<String>()

        scripts.forEach { f ->
            if (!f.asFile.isFile) {
                problems += "${f.asFile.name}: 文件不存在"
                return@forEach
            }
            val bytes = f.asFile.readBytes()

            // 规则 1a：BOM
            if (bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
            ) {
                problems += "${f.asFile.name}: 含 UTF-8 BOM，cmd 会把 BOM 当命令首字符"
            }

            // 规则 1b：非 ASCII
            val nonAscii = bytes.withIndex().filter { it.value >= 0x80 }
            if (nonAscii.isNotEmpty()) {
                val first = nonAscii.first()
                problems += "${f.asFile.name}: 含 ${nonAscii.size} 个非 ASCII 字节" +
                    "（首个在偏移 ${first.index}，值 0x%02X）".format(first.value.toInt() and 0xFF)
            }

            // 规则 2：行尾必须是 CRLF，不允许裸 LF
            //
            // 注意不要用 bytes.windowed(2)：windowed 是 CharSequence 的扩展
            // 函数，ByteArray 上不存在，用它会让整个 build 脚本编译失败
            // （连带 compileJava / verifyMinecraftArtifact 等所有任务一起挂）。
            // 这里直接按下标遍历，避免依赖任何集合扩展。
            var bareLf = 0
            var crlf = 0
            for (i in bytes.indices) {
                if (bytes[i] != 0x0A.toByte()) continue
                if (i > 0 && bytes[i - 1] == 0x0D.toByte()) crlf++ else bareLf++
            }
            if (bareLf > 0) {
                problems += "${f.asFile.name}: 含 $bareLf 个裸 LF（CRLF 共 $crlf 个）。" +
                    "cmd.exe 要求 CRLF 行尾，否则命令边界解析错乱"
            }

            // 规则 3：REM 注释里不得出现字面百分号（cmd 会尝试展开它）
            val text = String(bytes, Charsets.ISO_8859_1)
            text.split("\r\n").forEachIndexed { idx, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("REM", ignoreCase = true) && line.contains('%')) {
                    problems += "${f.asFile.name}:${idx + 1}: REM 注释含字面百分号，" +
                        "cmd 会在 REM 行展开变量并破坏解析"
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "启动脚本编码校验失败:\n" + problems.joinToString("\n") { "  - $it" } +
                    "\n\n修复方式：用纯 ASCII 内容 + CRLF 行尾重写该文件。" +
                    "详见 mili-loader/distribution/bin/mili-loader.bat 顶部说明。"
            )
        }
        logger.lifecycle("启动脚本编码校验通过（纯 ASCII + CRLF）")
    }
}

tasks.named("check") { dependsOn(launcherScripts) }
tasks.named("build") { dependsOn(launcherScripts) }

// ── 分发边界验证（Minecraft 分发红线） ──────────────────────────────────────
// 递归扫描分发产物，任何 Minecraft 类/源码/本体 JAR 都让构建失败。
// 详见 distribution-boundary.gradle.kts 顶部的规则说明。
apply(from = "distribution-boundary.gradle.kts")

tasks.named("check") {
    dependsOn("distributionBoundaryCheck")
}

