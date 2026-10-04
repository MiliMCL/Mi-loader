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

dependencies {
    implementation(project(":mili-runtime"))
    implementation(project(":mili-minecraft-integration"))
    implementation(project(":mili-abi"))

    // ASM lets the api-boundary test introspect the loader's compiled classes
    // and flag any accidental reference to runtime-internal packages.
    testImplementation("org.ow2.asm:asm:9.7")
}

// ---------------------------------------------------------------------------
// 1. Generate platform.json descriptor with Minecraft artifact fingerprint.
// ---------------------------------------------------------------------------
tasks.named<Copy>("processResources") {
    doLast {
        val platformVersion = rootProject.findProperty("miliPlatformVersion") ?: "0.1.0"
        val abiVersion = rootProject.findProperty("miliAbiVersion") ?: "1"
        val minecraftVersion = rootProject.findProperty("minecraftVersion") ?: "26.2"
        val javaVersion = rootProject.findProperty("javaVersion") ?: "25"
        val platformId = "mili-${platformVersion}-mc${minecraftVersion}"
        val timestamp = Instant.now().toString()

        val mcSha = minecraftArtifactSha256(minecraftVersion.toString())

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
        !path.isNullOrBlank() -> File(path)
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
    archiveVersion.set("${rootProject.findProperty("miliPlatformVersion")}-mc${rootProject.findProperty("minecraftVersion")}")
    archiveClassifier.set("")

    mergeServiceFiles()

    manifest {
        attributes(
            "Main-Class" to "org.loader.loader.LoaderMain",
            "Implementation-Title" to "Mili Platform",
            "Implementation-Version" to version,
            "Mili-Platform" to (rootProject.findProperty("miliPlatformVersion") ?: "0.1.0"),
            "Mili-Abi" to (rootProject.findProperty("miliAbiVersion") ?: "1"),
            "Mili-Minecraft" to (rootProject.findProperty("minecraftVersion") ?: "26.2"),
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
    val platformVersion = rootProject.findProperty("miliPlatformVersion") ?: "0.1.0"
    val minecraftVersion = rootProject.findProperty("minecraftVersion") ?: "26.2"
    inputs.file(layout.buildDirectory.file("libs/mili-${platformVersion}-mc${minecraftVersion}.jar"))
    outputs.file(layout.buildDirectory.file("verification/platform-jar.verified"))

    doLast {
        val platformVersion = rootProject.findProperty("miliPlatformVersion") ?: "0.1.0"
        val minecraftVersion = rootProject.findProperty("minecraftVersion") ?: "26.2"
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
        require(platformJsonText.contains(minecraftVersion.toString())) { "platform.json missing minecraft=${minecraftVersion}" }
        require(minecraftJsonText.contains("artifactSha256")) { "minecraft.json missing artifactSha256 fingerprint" }

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
        val platformVersion = rootProject.findProperty("miliPlatformVersion") ?: "0.1.0"
        val minecraftVersion = rootProject.findProperty("minecraftVersion") ?: "26.2"
        val javaVersion = rootProject.findProperty("javaVersion") ?: "25"
        val abiVersion = rootProject.findProperty("miliAbiVersion") ?: "1"
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
        val mcSha = minecraftArtifactSha256(minecraftVersion.toString())

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
