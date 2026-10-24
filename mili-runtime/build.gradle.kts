// mili-runtime — Mili Runtime Kernel
// Scope/Scheduler/EventBus/Capability/Resource/lifecycle + Mod SDK + services
// No Minecraft or loader code here.

// ── 版本单一来源（由根项目从 gradle.properties 强制注入，此处只读取） ────────
val miliPlatformVersion: String = rootProject.extra["miliPlatformVersion"] as String
val miliAbiVersion: String = rootProject.extra["miliAbiVersion"] as String
val minecraftVersion: String = rootProject.extra["minecraftVersion"] as String

// ── ASM 版本（单一来源：根项目从 gradle.properties 注入） ─────────────────────
// Minecraft 26.2 的 class major = 69（Java 25），ASM 9.7 读不了。
val asmVersion: String = rootProject.extra["asmVersion"] as String
// asm-analysis / asm-util 独立锁定（离线缓存只有 9.8），原因见 gradle.properties。
val asmAnalysisVersion: String = rootProject.extra["asmAnalysisVersion"] as String

dependencies {
    api(project(":mili-abi"))

    // Transformation Engine 的字节码底座。
    //
    // 为什么放在 runtime 而不是 loader：引擎要接入 PermissionManager / AuditLog /
    // TickEngine，它们全在 runtime 侧；放 loader 会被迫反向依赖，且违反
    // ADR-0009（Minecraft 与字节码细节不进 Kernel 内部状态，仅作为纯函数处理字节码）。
    //
    // 引擎只把字节码当 byte[] 和字符串处理，不 import 任何 minecraft / loader 包，
    // 因此 ci.yml 的 "runtime must not import minecraft or loader" 检查依然通过。
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-tree:$asmVersion")
    implementation("org.ow2.asm:asm-commons:$asmVersion")
    // asm-analysis 走独立版本锁定，原因见 gradle.properties 的说明。
    implementation("org.ow2.asm:asm-analysis:$asmAnalysisVersion")
    // asm-util 必须是 implementation 而非 testImplementation：
    // BytecodeVerifier（主代码）用 CheckClassAdapter 做结构与帧校验 ——
    // 它在 defineClass 之前运行，是生产路径的一部分，不是测试专用。
    implementation("org.ow2.asm:asm-util:$asmAnalysisVersion")

    // 测试需要 ASM 来「生成一段待转换的字节码」。
    //
    // 为什么必须显式声明为 testImplementation 而非靠 implementation 传递：
    // implementation 不进入 test classpath 的编译期可见范围（Gradle 语义）。
    // 更重要的是——测试要验证的正是「转换后的字节码能否通过
    // CheckClassAdapter + Analyzer」，这个能力属于被测对象而非调用方，
    // 用 testImplementation 表达这份边界。
    testImplementation("org.ow2.asm:asm:$asmVersion")
    testImplementation("org.ow2.asm:asm-tree:$asmVersion")
    testImplementation("org.ow2.asm:asm-commons:$asmVersion")
    testImplementation("org.ow2.asm:asm-analysis:$asmAnalysisVersion")
    testImplementation("org.ow2.asm:asm-util:$asmAnalysisVersion")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Mili Runtime Kernel",
            "Implementation-Version" to version,
            "Mili-Platform" to miliPlatformVersion,
            "Mili-Abi" to miliAbiVersion
        )
    }
}

// ── B0: 热点微基准（独立 benchmark source set，不进 check/build）─────────────
//
// 为什么是 source set 而不是独立模块：只为两三百行基准代码开一个 Gradle 模块，
// 会引入新的发布/版本同步负担（MD B0 要求"不引入不必要生态"）。source set
// 足够隔离：不进 check、不进 build、不进发布产物，只在手动运行
// `:mili-runtime:benchmark` 时编译执行。
//
// 为什么不用 JMH：仓库坚持离线可构建（gradle.properties 有专门的离线缓存
// 锁定说明），JMH 及其注解处理器会破坏该约束。统计口径的取舍见
// BenchmarkMain 类注释 —— 定位是趋势对比，不是绝对值宣称。
val benchmarkSourceSet = sourceSets.create("benchmark") {
    java.srcDir("src/benchmark/java")
}
benchmarkSourceSet.compileClasspath +=
    sourceSets.main.get().output + configurations.compileClasspath.get()
benchmarkSourceSet.runtimeClasspath +=
    benchmarkSourceSet.output + benchmarkSourceSet.compileClasspath

tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description =
        "运行热点微基准（EventBus/Scheduler/Permission）。手动执行，不进 check —— " +
        "噪声敏感的基准进硬门禁会造成随机失败（MD B0 禁止）"
    classpath = benchmarkSourceSet.runtimeClasspath
    mainClass = "org.loader.runtime.benchmark.BenchmarkMain"
    // JVM 参数刻意保守：与生产 gradle.properties 的 512m 约束同数量级，
    // 避免"基准机配置"与"真实运行环境"脱节。
    jvmArgs("-Xmx512m")
}


