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

