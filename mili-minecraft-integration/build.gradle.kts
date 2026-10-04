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

dependencies {
    implementation(project(":mili-runtime"))
    implementation(project(":mili-abi"))
    decompilerCfg("org.benf:cfr:0.152")
}

// ── Minecraft build extension ────────────────────────────────────────────────
val minecraftVersion: String = rootProject.findProperty("minecraftVersion")?.toString() ?: "26.2"

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

tasks.jar {
    from(minecraftMetadataDir) {
        into("META-INF/mili")
    }
    manifest {
        attributes(
            "Implementation-Title" to "Mili Minecraft ${minecraftVersion} Integration",
            "Implementation-Version" to version,
            "Mili-Platform" to (rootProject.findProperty("miliPlatformVersion") ?: "0.1.0"),
            "Mili-Abi" to (rootProject.findProperty("miliAbiVersion") ?: "1"),
            "Mili-Minecraft" to minecraftVersion
        )
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

fun resolveMinecraftArtifact(explicitPath: String?): File {
    if (!explicitPath.isNullOrBlank()) return File(explicitPath).absoluteFile

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
      "integration": "${rootProject.findProperty("miliPlatformVersion")}",
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
