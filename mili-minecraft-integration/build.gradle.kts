// mili-minecraft-integration — Minecraft 26.x Bridge + Build Pipeline
//
// Full Minecraft integration pipeline:
//
//   Minecraft 26.2 JAR (build input)
//         ↓  verifyMinecraftArtifact
//         ↓  prepareMinecraft (bundler normalize)
//         ↓  decompileMinecraft (CFR)
//         ↓  generateMinecraftIntegration
//         ↓  compileJava → shadowJar
//
// Pipeline is GRACEFULLY SKIPPED when no MC artifact is available (CI).
// Decompiled sources are build intermediates only — never committed, never shipped.

import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.io.File

plugins {
    `java-library`
}

val decompilerCfg = configurations.create("decompiler")

// ── ASM 版本（单一来源：根项目从 gradle.properties 注入） ─────────────────────
// Minecraft 26.2 的 class major = 69（Java 25），ASM 9.7 读不了，
// 会抛 "Unsupported class file major version 69"。9.8+ 才支持 Java 25。
val asmVersion: String = rootProject.extra["asmVersion"] as String
// asm-analysis / asm-util 独立锁定（离线缓存只有 9.8），原因见 gradle.properties。
val asmAnalysisVersion: String = rootProject.extra["asmAnalysisVersion"] as String

dependencies {
    implementation(project(":mili-runtime"))
    implementation(project(":mili-abi"))
    // ASM generates the Block subclasses that delegate to mod behaviour.
    // Still zero compile-time coupling to Minecraft: we emit bytecode against
    // string-named types and let the game classloader link them.
    //
    // 四个 artifact 而不是一个：注入逻辑需要 AdviceAdapter（asm-commons）与
    // 方法解析（asm-tree 的 MethodNode），只用核心包写不出转换器。
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-tree:$asmVersion")
    implementation("org.ow2.asm:asm-commons:$asmVersion")
    // asm-analysis 走独立版本锁定，原因见 gradle.properties。
    implementation("org.ow2.asm:asm-analysis:$asmAnalysisVersion")
    // asm-util 提供 CheckClassAdapter，字节码验证测试直接依赖它。
    //
    // test 侧显式声明而非靠 implementation 传递：把「测试观察字节码内部结构」
    // 这件事写成一条显式依赖，比让它隐式继承更清楚 —— 这个模块的测试
    // （尤其是真实 Minecraft 26.2 冒烟测试）需要读常量池、扫指令序列。
    testImplementation("org.ow2.asm:asm:$asmVersion")
    testImplementation("org.ow2.asm:asm-tree:$asmVersion")
    testImplementation("org.ow2.asm:asm-commons:$asmVersion")
    testImplementation("org.ow2.asm:asm-analysis:$asmAnalysisVersion")
    testImplementation("org.ow2.asm:asm-util:$asmAnalysisVersion")
    decompilerCfg("org.benf:cfr:0.152")
}

// ── Minecraft build extension ────────────────────────────────────────────────
// 版本来自 gradle.properties（根项目已强制校验存在，无字面量兜底）
val miliPlatformVersion: String = rootProject.extra["miliPlatformVersion"] as String
val miliAbiVersion: String = rootProject.extra["miliAbiVersion"] as String
val minecraftVersion: String = rootProject.extra["minecraftVersion"] as String

val minecraftArtifactPath: String? = providers.gradleProperty("minecraftArtifact")
    .orElse(providers.environmentVariable("MINECRAFT_ARTIFACT"))
    .orNull

val minecraftBuildDir = layout.buildDirectory.dir("minecraft").get().asFile
val minecraftInputDir = File(minecraftBuildDir, "input")
val minecraftExtractedDir = File(minecraftBuildDir, "extracted")
val minecraftDecompiledDir = File(minecraftBuildDir, "decompiled")
val minecraftGeneratedDir = File(minecraftBuildDir, "generated")
val minecraftMetadataDir = File(minecraftBuildDir, "metadata")

// Availability marker — written by verifyMinecraftArtifact.
// If "false" or missing, all downstream tasks are skipped via onlyIf.
val mcAvailableMarker = layout.buildDirectory.file("minecraft/mc-available").get().asFile

// ── Task: Verify Minecraft artifact ──────────────────────────────────────────
// NEVER fails the build. Writes marker: "true" if artifact found, "false" otherwise.
val verifyMinecraftArtifact = tasks.register("verifyMinecraftArtifact") {
    group = "minecraft"
    description = "Checks if Minecraft ${minecraftVersion} artifact is available. Skips gracefully if not."

    inputs.property("minecraftVersion", minecraftVersion)
    outputs.file(mcAvailableMarker)

    doLast {
        mcAvailableMarker.parentFile.mkdirs()
        try {
            val artifact = resolveMinecraftArtifact(minecraftArtifactPath)
            logger.lifecycle("[Minecraft] Verifying: ${artifact.absolutePath} (${artifact.length()} bytes)")

            require(artifact.exists()) { "Minecraft artifact not found: ${artifact.absolutePath}" }
            require(artifact.canRead()) { "Minecraft artifact not readable: ${artifact}" }
            require(artifact.length() > 1_000_000) { "Artifact too small: ${artifact.length()} bytes" }

            val (actualVersion, _) = probeMinecraftJar(artifact, minecraftVersion)
            require(actualVersion != null || artifact.name.endsWith(".jar")) { "Invalid JAR: ${artifact.name}" }

            mcAvailableMarker.writeText("true")
            logger.lifecycle("[Minecraft] OK — version ${actualVersion ?: "assumed ${minecraftVersion}"}")
        } catch (e: Exception) {
            mcAvailableMarker.writeText("false")
            logger.lifecycle("[Minecraft] SKIP — ${e.message}")
            // Generate a minimal minecraft.json so the platform JAR always carries
            // a fingerprint (even when the decompile pipeline was skipped in CI).
            minecraftMetadataDir.mkdirs()
            val mcJson = File(minecraftMetadataDir, "minecraft.json")
            mcJson.writeText("""{
              "minecraft": "$minecraftVersion",
              "artifactSha256": "unknown",
              "skipped": true
            }""")
            logger.lifecycle("[Minecraft] Wrote minimal ${mcJson.absolutePath} (skipped)")
        }
    }
}

// ── Task: Prepare Minecraft (handle bundler) ──────────────────────────────────
val prepareMinecraft = tasks.register<Task>("prepareMinecraft") {
    group = "minecraft"
    description = "Normalizes Minecraft JAR (handles bundler) into build/minecraft/extracted/."

    dependsOn(verifyMinecraftArtifact)
    onlyIf { mcAvailableMarker.exists() && mcAvailableMarker.readText().trim() == "true" }
    inputs.property("minecraftVersion", minecraftVersion)
    outputs.dir(minecraftExtractedDir)

    doLast {
        val artifact = resolveMinecraftArtifact(minecraftArtifactPath)
        val (_, classesRoot) = probeMinecraftJar(artifact, minecraftVersion)
        if (minecraftExtractedDir.exists()) minecraftExtractedDir.deleteRecursively()
        minecraftExtractedDir.mkdirs()
        unpackBundler(artifact, classesRoot, minecraftExtractedDir)
        val classCount = minecraftExtractedDir.walkTopDown().count { it.name.endsWith(".class") }
        logger.lifecycle("[Minecraft] Extracted ${classCount} classes to ${minecraftExtractedDir}")
    }
}

// ── Task: Decompile Minecraft (CFR) ─────────────────────────────────────────
val decompileMinecraftInputs = tasks.register<Jar>("decompileMinecraftInputs") {
    group = "minecraft"
    description = "(internal) Packs extracted classes into a JAR for CFR."

    dependsOn(prepareMinecraft)
    onlyIf { mcAvailableMarker.exists() && mcAvailableMarker.readText().trim() == "true" }
    from(minecraftExtractedDir) { include("**/*.class") }
    destinationDirectory.set(File(minecraftBuildDir, "tmp"))
    archiveFileName.set("minecraft-classes.jar")
}

val decompileMinecraft = tasks.register<JavaExec>("decompileMinecraft") {
    group = "minecraft"
    description = "Runs CFR decompiler on extracted Minecraft classes."

    dependsOn(decompileMinecraftInputs)
    onlyIf { mcAvailableMarker.exists() && mcAvailableMarker.readText().trim() == "true" }
    mainClass.set("org.benf.cfr.reader.Main")
    classpath = decompilerCfg
    // Full Minecraft is ~10k classes; the default (1/4 RAM) heap OOMs the
    // runner and the task dies before producing sources.
    maxHeapSize = "4g"

    // ── 可缓存声明 ────────────────────────────────────────────────────────
    //
    // 没有 inputs/outputs 的 JavaExec 在 Gradle 眼里永远是 out-of-date：
    // 每次构建都重跑 CFR，7055 个源文件要 1~2 分钟。这直接毁掉了
    // 「改一行集成代码 → 重新打包 → 跑一次客户端」的迭代节奏。
    //
    // 输入是本任务打包出的 minecraft-classes.jar，输出是反编译目录。
    // 只要 Minecraft 制品不变，反编译结果就不会变 —— 这正是 UP-TO-DATE
    // 的定义。cacheIf 让它在换机器/clean 后也能从构建缓存恢复。
    inputs.files(decompileMinecraftInputs)
    outputs.dir(minecraftDecompiledDir)
    outputs.cacheIf { true }

    doFirst {
        minecraftDecompiledDir.mkdirs()
        val inputJar = decompileMinecraftInputs.get().archiveFile.get().asFile
        logger.lifecycle("[Minecraft] Decompiling ${inputJar.absolutePath} via CFR...")
        args(inputJar.absolutePath, "--outputdir", minecraftDecompiledDir.absolutePath, "--silent", "true")
    }

    doLast {
        val javaCount = minecraftDecompiledDir.walkTopDown().count { it.name.endsWith(".java") }
        logger.lifecycle("[Minecraft] Decompile complete: ${javaCount} sources")
    }
}

// ── Task: Generate Minecraft Integration ─────────────────────────────────────
val generateMinecraftIntegration = tasks.register("generateMinecraftIntegration") {
    group = "minecraft"
    description = "Analyzes decompiled sources, emits metadata + bridge sources."

    dependsOn(decompileMinecraft)
    onlyIf { mcAvailableMarker.exists() && mcAvailableMarker.readText().trim() == "true" }
    // 输入是被分析的源码树，输出是生成物。都在时本任务才可跳过 ——
    // 缺 inputs 会让它每次构建都重跑一次全量扫描。
    inputs.dir(minecraftDecompiledDir)
    outputs.dirs(minecraftGeneratedDir, minecraftMetadataDir)

    doLast {
        minecraftGeneratedDir.mkdirs()
        minecraftMetadataDir.mkdirs()

        val analysis = analyzeMinecraftSources(minecraftDecompiledDir)
        val metaSource = File(minecraftGeneratedDir, "org/loader/minecraft/Minecraft26_2Metadata.java")
        metaSource.parentFile.mkdirs()
        metaSource.writeText(generateMetadataSource(analysis))

        File(minecraftMetadataDir, "minecraft.json").writeText(generateMinecraftJson(analysis))
        File(minecraftMetadataDir, "minecraft-build-report.json").writeText(generateBuildReport(analysis))

        logger.lifecycle("[Minecraft] Generated: ${analysis.eventTypes.size} events, ${analysis.registries.size} registries")
    }
}

// ── Wire into build ──────────────────────────────────────────────────────────
sourceSets {
    main {
        java {
            srcDir(minecraftGeneratedDir)
        }
    }
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateMinecraftIntegration)
}

// sourcesJar 读取的源目录包含 minecraftGeneratedDir，而后者是
// generateMinecraftIntegration 的输出。Gradle 9 会把这种「读别人输出目录
// 却没声明依赖」判为校验错误并让 build 失败（不是警告）。
// 显式 dependsOn 是官方给出的解法 2，语义也最直白：
// 要先有生成的源，才谈得上打包源 JAR。
tasks.named<Jar>("sourcesJar") {
    dependsOn(generateMinecraftIntegration)
}

tasks.jar {
    // 同理：metadata 目录由 generateMinecraftIntegration 产出。
    dependsOn(generateMinecraftIntegration)
    from(minecraftMetadataDir) {
        into("META-INF/mili")
    }
    manifest {
        attributes(
            "Implementation-Title" to "Mili Minecraft ${minecraftVersion} Integration",
            "Implementation-Version" to version,
            "Mili-Platform" to miliPlatformVersion,
            "Mili-Abi" to miliAbiVersion,
            "Mili-Minecraft" to minecraftVersion
        )
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

fun resolveMinecraftArtifact(explicitPath: String?): File {
    if (!explicitPath.isNullOrBlank()) {
        val f = File(explicitPath)
        // A relative path here would resolve against the Gradle daemon's working
        // directory (Gradle user home), not the project — resolve it ourselves.
        return if (f.isAbsolute) f else project.layout.projectDirectory.file(explicitPath).asFile.absoluteFile
    }

    val projectDir = project.layout.projectDirectory.asFile
    val candidate = projectDir.resolve("test_client")
        .listFiles { f -> f.name.endsWith(".jar") && f.name.contains(minecraftVersion) }
        ?.firstOrNull()
    if (candidate != null && candidate.exists()) return candidate.absoluteFile

    val rootDir = project.rootProject.layout.projectDirectory.asFile
    val rootCandidate = rootDir.resolve("test_client")
        .listFiles { f -> f.name.endsWith(".jar") && f.name.contains(minecraftVersion) }
        ?.firstOrNull()
    if (rootCandidate != null && rootCandidate.exists()) return rootCandidate.absoluteFile

    throw IllegalStateException(
        "Minecraft ${minecraftVersion} JAR not found in test_client/. " +
        "Set 'minecraftArtifact' gradle property or MINECRAFT_ARTIFACT env var."
    )
}

data class ProbeResult(val version: String?, val classesRoot: String?, val sha256: String)

fun probeMinecraftJar(jar: File, expectedVersion: String): Pair<String?, String?> {
    ZipFile(jar).use { zf ->
        val versionFromManifest = zf.getEntry("META-INF/MANIFEST.MF")
            ?.let { ze -> zf.getInputStream(ze).bufferedReader().use { it.readText() } }
            ?.let { Regex("Implementation-Version:\\s*(\\S+)").find(it)?.groupValues?.get(1) }

        val versionFromJson = zf.getEntry("version.json") ?: zf.getEntry("META-INF/version.json")
        val detectedFromJson = versionFromJson?.let { ze ->
            zf.getInputStream(ze).bufferedReader().use { it.readText() }
        }?.let { Regex("\"\"\"id\"\"\":\\s*\\\"\\\"\\\"([^\\\"]+)\\\"\\\"\"").find(it)?.groupValues?.get(1) }

        val bundlerDirs = zf.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith("META-INF/versions/") && it.count { c -> c == '/' } == 3 }
            .map { it.substringAfter("META-INF/versions/").substringBefore("/") }
            .distinct()
            .toList()

        val classesRoot = if (bundlerDirs.isNotEmpty()) {
            val latest = bundlerDirs.maxWithOrNull(compareBy { it }) ?: bundlerDirs.first()
            "META-INF/versions/$latest"
        } else ""

        val version = detectedFromJson ?: versionFromManifest ?: expectedVersion
        return Pair(version, classesRoot)
    }
}

fun unpackBundler(artifact: File, classesRoot: String?, targetDir: File) {
    targetDir.mkdirs()
    ZipFile(artifact).use { zf ->
        if (classesRoot.isNullOrEmpty()) {
            for (entry in zf.entries()) {
                if (entry.isDirectory) continue
                val out = File(targetDir, entry.name)
                out.parentFile.mkdirs()
                zf.getInputStream(entry).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
            }
            return
        }

        val innerJarPath = zf.entries().asSequence()
            .map { it.name }
            .firstOrNull { it.startsWith(classesRoot) && it.endsWith(".jar") }
            ?: throw IllegalStateException("Inner JAR not found at '$classesRoot'")

        val innerBytes = zf.getInputStream(zf.getEntry(innerJarPath)).use { it.readBytes() }
        val tmpInner = File.createTempFile("mc-bundle-", ".jar").also { it.deleteOnExit() }
        tmpInner.writeBytes(innerBytes)

        ZipFile(tmpInner).use { inner ->
            for (entry in inner.entries()) {
                if (entry.isDirectory) continue
                val out = File(targetDir, entry.name)
                out.parentFile.mkdirs()
                inner.getInputStream(entry).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
            }
        }
    }
}

data class MinecraftAnalysis(
    val version: String,
    val totalClasses: Int = 0,
    val eventTypes: List<String> = emptyList(),
    val registries: List<String> = emptyList(),
    val bridges: List<String> = listOf("TickBridge", "EntityBridge", "WorldBridge", "EventBridge", "RegistryBridge")
)

fun analyzeMinecraftSources(dir: File): MinecraftAnalysis {
    if (!dir.isDirectory) return MinecraftAnalysis(version = minecraftVersion)

    val events = sortedSetOf<String>()
    val regs = sortedSetOf<String>()
    var classes = 0

    dir.walkTopDown().filter { it.name.endsWith(".java") }.forEach { file ->
        val text = runCatching { file.readText() }.getOrDefault("")
        if (text.contains("class ") || text.contains("interface ") || text.contains("record ") || text.contains("enum ")) classes++
        Regex("""(?:class|interface|record|enum)\s+\w*Event\b""").findAll(text).forEach { events.add(it.value.substringAfter(" ").trim()) }
        Regex("""(?:class|interface|record|enum)\s+\w*Registry\b""").findAll(text).forEach { regs.add(it.value.substringAfter(" ").trim()) }
    }

    return MinecraftAnalysis(
        version = minecraftVersion,
        totalClasses = classes,
        eventTypes = events.toList(),
        registries = regs.toList()
    )
}

fun generateMetadataSource(a: MinecraftAnalysis): String = """
    package org.loader.minecraft;

    public final class Minecraft26_2Metadata {
        private Minecraft26_2Metadata() {}
        public static final String VERSION = "${a.version}";
        public static final int TOTAL_CLASSES = ${a.totalClasses};
        public static final String[] EVENT_TYPES = { ${a.eventTypes.joinToString(", ") { "\"$it\"" }} };
        public static final String[] REGISTRIES = { ${a.registries.joinToString(", ") { "\"$it\"" }} };
        public static final String[] BRIDGES = { ${a.bridges.joinToString(", ") { "\"$it\"" }} };
    }
    """.trimIndent() + "\n"

fun generateMinecraftJson(a: MinecraftAnalysis): String {
    val sha = computeSha256(resolveMinecraftArtifact(minecraftArtifactPath))
    return """
    {
      "minecraft": "${a.version}",
      "artifactSha256": "$sha",
      "decompiler": "cfr",
      "integration": $miliPlatformVersion,
      "totalClasses": ${a.totalClasses},
      "eventTypes": [${a.eventTypes.joinToString(", ") { "\"$it\"" }}],
      "registries": [${a.registries.joinToString(", ") { "\"$it\"" }}]
    }
    """.trimIndent()
}

fun generateBuildReport(a: MinecraftAnalysis): String {
    val art = resolveMinecraftArtifact(minecraftArtifactPath)
    return """
    {
      "minecraft": "${a.version}",
      "input": "${art.absolutePath}",
      "inputSha256": "${computeSha256(art)}",
      "artifactSizeBytes": ${art.length()},
      "decompiled": true,
      "integrationGenerated": true,
      "timestamp": "${Instant.now()}",
      "gitCommit": "${localGitCommit()}"
    }
    """.trimIndent()
}

fun computeSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { it.copyTo(digest.outputStream()) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun MessageDigest.outputStream() = object : java.io.OutputStream() {
    override fun write(b: Int) { update(b.toByte()) }
    override fun write(b: ByteArray, off: Int, len: Int) { update(b, off, len) }
}

fun localGitCommit(): String = runCatching {
    val proc = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    proc.waitFor()
    if (proc.exitValue() == 0 && out.isNotEmpty()) out else "unknown"
}.getOrDefault("unknown")

// ── 真实注册表 E2E 测试的默认隔离 ───────────────────────────────────────────
//
// 有三个测试类直接操作 Minecraft 的方块注册表（BuiltInRegistries）。
// 它们的前提是「本 JVM 里没有任何东西 bootstrap 过注册表」——
// 而 bootStrap() 是**进程级不可逆**的：跑过一次，注册表永久冻结。
//
// 这个前提在单元测试套件里几乎永远不成立：任何一次读注册表都会把它冻住。
// 结果是这些断言看到 "registration window is already closed"，
// 测试变红，但与实现正确性毫无关系。
//
// 所以默认把它们排除，改用 tag 显式开启：
//   ./gradlew :mili-minecraft-integration:test -Pmili.registryTests=true
//
// 曾经尝试过 forkEvery = 1（每个测试类一个 JVM），代价是整个模块的测试
// 从 2 分半变成 14 分钟，而且引入了新的顺序依赖（MinecraftPipelineTest
// 依赖前一个测试解压出的目录）。用 tag 隔离是更诚实的做法：
// **这些测试需要独占且干净的环境，这件事应该在测试名上写明，而不是
// 靠构建碰运气。**
val registryE2e = providers.gradleProperty("mili.registryTests").isPresent

tasks.test {
    if (!registryE2e) {
        useJUnitPlatform {
            excludeTags("registry-e2e")
        }
        logger.lifecycle(
            "[test] 跳过真实注册表 E2E（tag=registry-e2e）。" +
                "需要时加 -Pmili.registryTests=true"
        )
    }
}
