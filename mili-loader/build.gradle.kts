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
// 1. Generate platform.json descriptor into build/resources/main/META-INF/mili/
// every time processResources runs. The fat JAR picks it up from the main
// source set output.
// ---------------------------------------------------------------------------
tasks.named<Copy>("processResources") {
    doLast {
        val platformVersion = rootProject.findProperty("miliPlatformVersion") ?: "0.1.0"
        val abiVersion = rootProject.findProperty("miliAbiVersion") ?: "1"
        val minecraftVersion = rootProject.findProperty("minecraftVersion") ?: "26.2"
        val javaVersion = rootProject.findProperty("javaVersion") ?: "25"
        val platformId = "mili-${platformVersion}-mc${minecraftVersion}"
        val timestamp = Instant.now().toString()

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
            append("  \"buildTimestamp\": \"$timestamp\"\n")
            append("}\n")
        }
        metaFile.writeText(body)
        logger.lifecycle("[platform] Generated ${metaFile.absolutePath}")
    }
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

        // release-manifest.json
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
