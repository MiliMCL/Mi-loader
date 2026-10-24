// mili-test —— 纯 JVM Mod 测试框架（MD 里程碑 A4）。
//
// 边界约束（对应 A4 验收）：
// 1. 本模块只依赖 mili-runtime（传递 mili-abi），绝不依赖 loader /
//    minecraft-integration / installer —— 在无 Minecraft 的环境里可用；
// 2. 测试 API 不进入运行时 ABI：本模块是"测试依赖"，没有任何生产模块
//    反向依赖它（CI 的依赖图校验保证）；
// 3. JUnit 版本与根构建脚本统一注入的 testImplementation 版本保持一致
//    （5.11.3）；作为 api 暴露是因为本模块的公开适配器签名携带 JUnit 注解。
plugins {
    `java-library`
}

dependencies {
    // 真实实现：Scheduler/Scope/EventBus 尽量不用 Mock（MD A4 要求），
    // 只有 MC 桥接边界才允许 Stub —— 本模块根本不触碰 MC 桥接。
    api(project(":mili-runtime"))

    // JUnit 适配面：基类 MiliTest 的生命周期注解属于公开 API。
    // 版本必须与根 build.gradle.kts 的 testImplementation 一致。
    api("org.junit.jupiter:junit-jupiter:5.11.3")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Mili Test Framework",
            "Mili-Platform" to rootProject.extra["miliPlatformVersion"],
            "Mili-Abi" to rootProject.extra["miliAbiVersion"]
        )
    }
}
