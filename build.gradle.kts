// 注意：`java.time.Duration` 不能写成全限定名 —— `java` 在 Gradle 脚本里
// 已被 java 扩展（JavaPluginExtension）遮蔽，`java.time` 会解析失败。
import java.time.Duration

plugins {
    `java-library`
}

// Root project: aggregates submodules, no source code.
// All source lives in mili-abi, mili-runtime, mili-loader, mili-minecraft-integration.

// ── 版本单一来源 ─────────────────────────────────────────────────────────────
// gradle.properties 是唯一权威来源。任何模块都不得自带版本字面量兜底
// （历史上出现过 25 处 ?: "0.1.0" 这类兜底，是版本漂移的主要来源）。
// 缺失即构建失败 —— 这比静默使用错误的默认值安全得多。
extra["miliPlatformVersion"] = requireNotNull(findProperty("miliPlatformVersion")) {
    "gradle.properties 缺少 miliPlatformVersion"
}
extra["miliAbiVersion"] = requireNotNull(findProperty("miliAbiVersion")) {
    "gradle.properties 缺少 miliAbiVersion"
}
extra["minecraftVersion"] = requireNotNull(findProperty("minecraftVersion")) {
    "gradle.properties 缺少 minecraftVersion"
}
extra["javaVersion"] = requireNotNull(findProperty("javaVersion")) {
    "gradle.properties 缺少 javaVersion"
}
extra["asmVersion"] = requireNotNull(findProperty("asmVersion")) {
    "gradle.properties 缺少 asmVersion"
}
extra["asmAnalysisVersion"] = requireNotNull(findProperty("asmAnalysisVersion")) {
    "gradle.properties 缺少 asmAnalysisVersion（离线构建时 asm-analysis/asm-util 需独立锁定）"
}

val miliPlatformVersion: String = rootProject.extra["miliPlatformVersion"] as String
val miliAbiVersion: String = rootProject.extra["miliAbiVersion"] as String
val minecraftVersion: String = rootProject.extra["minecraftVersion"] as String
val javaVersion: String = rootProject.extra["javaVersion"] as String
val asmVersion: String = rootProject.extra["asmVersion"] as String

subprojects {
    apply(plugin = "java-library")

    group = "org.loader"
    version = miliPlatformVersion

    // 子项目通过 rootProject.extra 读取，杜绝重复字面量
    extra["miliPlatformVersion"] = miliPlatformVersion
    extra["miliAbiVersion"] = miliAbiVersion
    extra["minecraftVersion"] = minecraftVersion
    extra["javaVersion"] = javaVersion
    extra["asmVersion"] = asmVersion

    java {
        sourceCompatibility = JavaVersion.toVersion(javaVersion)
        targetCompatibility = JavaVersion.toVersion(javaVersion)
        withSourcesJar()
    }

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.test {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed")
            showStandardStreams = false
        }
        // 没有超时上限时，一个死锁的测试会让整个 build 无限期挂住 ——
        // 本机 8G 内存下还会拖垮所有并行的 Gradle 工作。
        // 平台涉及 ClassLoader、tick 循环与真实 Minecraft，都是容易死锁的地方，
        // 因此在这里统一兜底：单个测试 2 分钟没结束就判失败并打印线程栈。
        timeout.set(Duration.ofMinutes(10))
        systemProperty("junit.jupiter.execution.timeout.default", "2m")
        systemProperty("junit.jupiter.execution.timeout.mode", "enabled")
        maxParallelForks = 1
    }
}

/**
 * 校验代码内的版本常量（VersionInfo / MiliSymbol）与 gradle.properties 一致。
 * 版本漂移会让已编译 Mod 无法加载，因此必须在构建期拦住。
 */
val verifyVersionConstants = tasks.register("verifyVersionConstants") {
    group = "verification"
    description = "校验 VersionInfo 与 MiliSymbol 中的版本常量和 gradle.properties 完全一致"

    val sourceFile = file("mili-abi/src/main/java/org/loader/api/VersionInfo.java")
    val symbolFile = file("mili-abi/src/main/java/org/loader/api/transform/symbol/MiliSymbol.java")
    inputs.file(sourceFile)
    inputs.file(symbolFile)
    inputs.property("platform", miliPlatformVersion)
    inputs.property("abi", miliAbiVersion)
    inputs.property("minecraft", minecraftVersion)
    inputs.property("java", javaVersion)
    inputs.property("asm", asmVersion)

    doLast {
        val text = sourceFile.readText()
        val symbolText = symbolFile.readText()
        val problems = mutableListOf<String>()

        fun requireConst(pattern: String, expected: String, label: String) {
            val m = Regex(pattern).find(text)
            if (m == null) {
                problems.add("$label 未在 VersionInfo 中定义")
            } else if (m.groupValues[1] != expected) {
                problems.add("$label = ${m.groupValues[1]}，但 gradle.properties 为 $expected")
            }
        }

        requireConst("""CURRENT_VERSION\s*=\s*"([^"]+)"""", miliPlatformVersion, "CURRENT_VERSION")
        requireConst("""ABI_VERSION\s*=\s*(\d+)""", miliAbiVersion, "ABI_VERSION")
        requireConst("""TARGET_JAVA\s*=\s*(\d+)""", javaVersion, "TARGET_JAVA")
        requireConst("""TARGET_MINECRAFT\s*=\s*"([^"]+)"""", minecraftVersion, "TARGET_MINECRAFT")

        // 符号表声明的 Minecraft 版本必须与 gradle.properties 一致。
        // 本次审计发现仓库文档里同一方法的描述符有两处互相矛盾的写法，
        // 而符号表将成为转换器引用的唯一坐标来源 —— 它必须与单一版本源对齐，
        // 否则符号表自身就会成为新的漂移点。
        val symbolVersion = Regex("""MINECRAFT_VERSION\s*=\s*"([^"]+)"""")
            .find(symbolText)?.groupValues?.get(1)
        if (symbolVersion == null) {
            problems.add("MiliSymbol.MINECRAFT_VERSION 未定义")
        } else if (symbolVersion != minecraftVersion) {
            problems.add(
                "MiliSymbol.MINECRAFT_VERSION = $symbolVersion，" +
                        "但 gradle.properties 为 $minecraftVersion"
            )
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "版本单一来源校验失败:\n" + problems.joinToString("\n") { "  - $it" }
            )
        }
        logger.lifecycle(
            "[version] 常量一致: platform=$miliPlatformVersion abi=$miliAbiVersion " +
                    "minecraft=$minecraftVersion java=$javaVersion asm=$asmVersion"
        )
    }
}

tasks.named("check") {
    dependsOn(verifyVersionConstants)
    // 符号表校验也挂进 check：有Minecraft jar 时它必须通过，
    // 没有时它自行 SKIPPED（本地开发机不该持有 Minecraft —— 禁止分发）。
    dependsOn(verifyTransformationSymbols)
}

/**
 * 校验 MiliSymbol 中的方法坐标在真实 Minecraft jar 里确实存在。
 *
 * ## 为什么这道关卡不可省略
 *
 * MiliSymbol 是全部转换器的坐标来源（ADR-0011）。Mojang 跨版本
 * 改名、改描述符是常态，因此符号表**天然会过期**。
 *
 * 而符号表过期是一种极其危险的失效：转换器注入不到任何东西，
 * 游戏照常运行，Mod 的功能悄悄消失，日志里什么都没有。
 * 本仓库反复记录过同类问题（注册链死代码、TickBridge 零调用者）。
 *
 * 因此 CI 必须拿**真实的 Minecraft jar**（不是 mock、不是反编译源码）
 * 逐条比对。这也让「禁止用 Mock Minecraft 声称集成可用」这条要求
 * 有了机械保障：符号不存在，构建立即失败。
 *
 * ## 为什么本机跑不了
 *
 * Minecraft jar 不在仓库里（禁止分发），只在 CI 上由
 * MinecraftDiscovery 下载到 test_client/。jar 缺失时本任务 SKIPPED
 * 而不是 FAILED —— 因为本地开发机本就不该有它。
 *
 * ## 校验的是什么
 *
 * 精确比对 owner + name + descriptor 三元组。**只比方法名是不够的**：
 * Minecraft 里大量方法重载（tick() 与 tick(BooleanSupplier)），
 * 按名字匹配会命中错误的那个，生成出调用错误方法的字节码 ——
 * 这类错误同样不报错，只是行为诡异。
 */
val verifyTransformationSymbols = tasks.register("verifyTransformationSymbols") {
    group = "verification"
    description = "用真实 Minecraft jar 校验 MiliSymbol 的方法坐标确实存在"

    val symbolFile = file("mili-abi/src/main/java/org/loader/api/transform/symbol/MiliSymbol.java")
    val verifierSource = file("tools/SymbolVerifier.java")
    inputs.file(symbolFile)
    inputs.file(verifierSource)
    inputs.property("minecraft", minecraftVersion)

    // CI 把 Minecraft 下到 test_client/<version>.jar（见 ci.yml 的 Fetch Minecraft 步骤），
    // 本地开发则通常没有 —— 那种情况下本任务 SKIPPED 而非 FAILED：
    // 本地开发机本就不该持有 Minecraft（禁止分发）。
    val mcJar = file("test_client/$minecraftVersion.jar")
    val toolsOut = layout.buildDirectory.dir("symbol-verifier")

    doLast {
        if (!mcJar.exists()) {
            logger.lifecycle(
                "[symbols] SKIPPED —— 未找到 ${mcJar.path}。" +
                        "本地开发无需 Minecraft；CI 会先下载再校验。"
            )
            return@doLast
        }

        // 单文件源码用 javac 直接编译：tools/ 不是 Gradle 模块，
        // 把它做成模块只会为了一个 400 行的校验器引入新的构建配置负担。
        //
        // 用 ProcessBuilder 而非 exec {}：此处是 Task 的 doLast 闭包，
        // Project.exec 在这个作用域取不到（编译期报 Unresolved reference）。
        // 本仓库既有的外部进程调用（mili-loader / mili-minecraft-integration
        // 的 git 版本探测）一律用 ProcessBuilder，这里保持一致。
        //
        // 同理不能用 java.io.ByteArrayOutputStream 全限定名：Gradle 脚本里
        // `java` 被 java 扩展遮蔽（见本文件顶部注释），必须走 import。
        val classesDir = toolsOut.get().asFile
        classesDir.mkdirs()
        val javaBin = File(System.getProperty("java.home"), "bin")

        val compileProc = ProcessBuilder(
            File(javaBin, "javac").absolutePath,
            "-d", classesDir.absolutePath,
            verifierSource.absolutePath
        ).redirectErrorStream(true).start()
        val compileOut = compileProc.inputStream.bufferedReader().readText()
        compileProc.waitFor()
        if (compileProc.exitValue() != 0) {
            throw GradleException(
                "SymbolVerifier 编译失败（exit=${compileProc.exitValue()}）\n$compileOut"
            )
        }

        val abiClasses = project(":mili-abi").layout.buildDirectory.get()
            .asFile.resolve("classes/java/main").absolutePath

        val verifyProc = ProcessBuilder(
            File(javaBin, "java").absolutePath,
            "-cp", "${classesDir.absolutePath}${File.pathSeparator}$abiClasses",
            "SymbolVerifier",
            symbolFile.absolutePath,
            mcJar.absolutePath,
            minecraftVersion
        ).redirectErrorStream(true).start()
        val out = verifyProc.inputStream.bufferedReader().readText().trim()
        verifyProc.waitFor()

        if (out.isNotEmpty()) logger.lifecycle(out)
        if (verifyProc.exitValue() != 0) {
            throw GradleException(
                "MiliSymbol 校验失败（exit=${verifyProc.exitValue()}）。\n" +
                        "符号表中的方法坐标在真实 Minecraft $minecraftVersion 中不存在。\n" +
                        "这会让相关转换器**静默不注入** —— 游戏照常运行，Mod 功能消失，" +
                        "日志无任何错误。请更新 MiliSymbol.java 使其与实际 Minecraft 一致。\n" +
                        "校验器输出：\n$out"
            )
        }
        }
    }
