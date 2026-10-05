import java.util.zip.ZipFile

/**
 * Distribution Boundary Verification (Minecraft 分发边界验证)
 * ------------------------------------------------------------------
 * <p><b>Minecraft规定下</b>：Mili <b>绝不重新分发 Minecraft</b>。
 * Mojang 文件只能作为构建输入（build-time input），绝不进入发布产物。
 *
 * <p>本任务在 release 打包<b>之后</b>、发布<b>之前</b>运行，递归扫描
 * 分发目录与平台 fat JAR，任何 Minecraft 痕迹立即让构建失败。
 *
 * <p><b>禁止内容</b>：
 * <ul>
 *   <li>{@code net/minecraft/**} —— Minecraft 类</li>
 *   <li>{@code com/mojang/**} —— Mojang 类</li>
 *   <li>{@code decompiled/**} —— 反编译源码</li>
 *   <li>{@code *.mcpack}、{@code *.mca} 等 Minecraft 数据文件</li>
 *   <li>Minecraft 本体 JAR（server*.jar / client*.jar / minecraft*.jar）</li>
 * </ul>
 *
 * <p><b>允许内容</b>：
 * <ul>
 *   <li>{@code org/loader/**} —— Mili 自身的类</li>
 *   <li>{@code META-INF/mili/*.json} —— 平台元数据（其中可能提及 Minecraft
 *       版本号与 SHA-256，这是<b>指纹</b>而非 Minecraft 内容，允许）</li>
 * </ul>
 */

val distributionBoundaryCheck by tasks.registering {
    group = "verification"
    description = "扫描分发产物，确保不包含任何 Minecraft 类、源码或数据（Mojang 分发边界红线）"

    // 依赖打包任务 —— 必须在产物生成后检查
    dependsOn(":mili-loader:shadowJar")
    dependsOn(":mili-loader:distTar")
    dependsOn(":mili-loader:distZip")
    dependsOn(":mili-loader:releaseArtifacts")

    // 分发目录与平台 JAR 都在本模块的 build/ 下
    val distDir = layout.buildDirectory.dir("distributions").get().asFile
    val fatJar = layout.buildDirectory
        .file("libs/mili-${rootProject.extra["miliPlatformVersion"]}-mc${rootProject.extra["minecraftVersion"]}.jar")
        .get().asFile

    inputs.dir(distDir).optional()
    inputs.file(fatJar)
    // 无输出文件：该任务每次都执行（它是校验，不是转换）

    doLast {
        val violations = mutableListOf<String>()

        // ── Minecraft 禁止前缀与文件名模式 ──────────────────────────────────
        val forbiddenDirs = listOf(
            "net/minecraft/",
            "com/mojang/",
            "decompiled/",
            "net/minecraftforge/",
            "cpw/mods/",
            "org/bukkit/",
            "io/papermc/"
        )
        val forbiddenNamePatterns = listOf(
            Regex("^minecraft.*\\.(jar|zip)$", RegexOption.IGNORE_CASE),
            Regex("^server.*\\.(jar|zip)$", RegexOption.IGNORE_CASE),
            Regex("^client.*\\.(jar|zip)$", RegexOption.IGNORE_CASE),
            Regex("\\.mcpack$", RegexOption.IGNORE_CASE),
            Regex("\\.mca$", RegexOption.IGNORE_CASE),
            Regex("^.*-decompiled.*\\.(jar|zip)$", RegexOption.IGNORE_CASE)
        )

        fun isForbiddenPath(path: String): String? {
            val normalized = path.replace('\\', '/')
            for (prefix in forbiddenDirs) {
                if (normalized.contains(prefix)) {
                    return "包含 Minecraft 内部目录 '$prefix'"
                }
            }
            val fileName = normalized.substringAfterLast('/')
            for (pattern in forbiddenNamePatterns) {
                if (pattern.matches(fileName)) {
                    return "文件名匹配 Minecraft 模式 '$fileName'"
                }
            }
            return null
        }

        // ── 1. 检查 fat JAR 内部条目 ───────────────────────────────────────
        if (fatJar.exists()) {
            logger.lifecycle("[Boundary] 扫描平台 JAR: ${fatJar.name}")
            ZipFile(fatJar).use { zf ->
                val entries = zf.entries()
                var entryCount = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entryCount++
                    val reason = isForbiddenPath(entry.name)
                    if (reason != null) {
                        violations.add("平台 JAR ${fatJar.name} 条目 '${entry.name}' — $reason")
                    }
                }
                logger.lifecycle("[Boundary] 平台 JAR 共 $entryCount 个条目")
            }
        } else {
            violations.add("平台 JAR 不存在: ${fatJar.absolutePath}（无法验证分发边界）")
        }

        // ── 2. 检查分发目录树 ───────────────────────────────────────────────
        if (distDir.exists() && distDir.walkTopDown().any { it.isFile }) {
            logger.lifecycle("[Boundary] 扫描分发目录: ${distDir.absolutePath}")
            distDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(distDir).path
                val reason = isForbiddenPath(relative)
                if (reason != null) {
                    violations.add("分发目录 '$relative' — $reason")
                }
                // 分发目录内不得出现 Minecraft 本体 JAR
                if (file.name.endsWith(".jar")) {
                    val n = file.name.lowercase()
                    if (n.startsWith("minecraft") || n.startsWith("server")
                        || n.startsWith("client")) {
                        violations.add("分发目录含疑似 Minecraft 本体 JAR: '$relative'")
                    }
                }
            }
        }

        // ── 3. 检查 release 目录 ────────────────────────────────────────────
        val releaseDir = layout.buildDirectory.dir("release").get().asFile
        if (releaseDir.exists()) {
            releaseDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(releaseDir).path
                val reason = isForbiddenPath(relative)
                if (reason != null) {
                    violations.add("release 目录 '$relative' — $reason")
                }
            }
        }

        // ── 4. 校验 SHA256SUMS 与 release-manifest.json 覆盖所有产物 ─────────
        val shaFile = releaseDir.resolve("SHA256SUMS")
        val manifestFile = releaseDir.resolve("release-manifest.json")
        if (!shaFile.exists()) {
            violations.add("缺少 SHA256SUMS —— 发布产物必须带校验和")
        }
        if (!manifestFile.exists()) {
            violations.add("缺少 release-manifest.json —— 发布产物必须带清单")
        }
        if (manifestFile.exists()) {
            val manifest = manifestFile.readText()
            for (required in listOf("\"platformId\"", "\"version\"", "\"abi\"",
                    "\"minecraft\"", "\"java\"", "\"assets\"")) {
                if (!manifest.contains(required)) {
                    violations.add("release-manifest.json 缺少字段 $required")
                }
            }
        }

        // ── 5. 结论 ────────────────────────────────────────────────────────
        if (violations.isNotEmpty()) {
            logger.error("[Boundary] 分发边界检查失败 —— ${violations.size} 项违规：")
            violations.forEach { logger.error("  ✗ $it") }
            throw GradleException(
                "分发边界违规：发布产物不得包含 Minecraft 本体、类或反编译源码。\n" +
                violations.joinToString("\n") { "  - $it" }
            )
        }

        logger.lifecycle("[Boundary] 分发边界检查通过 —— 未发现任何 Minecraft 内容")
        logger.lifecycle("[Boundary] 确认: 无 net/minecraft/, 无 com/mojang/, 无 Minecraft 本体 JAR, 无反编译源码")
    }
}

// 让 check 同时验证边界
tasks.named("check") {
    dependsOn(distributionBoundaryCheck)
}