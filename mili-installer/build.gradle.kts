// mili-installer — Minecraft 环境安装器
//
// 职责：从 Mojang 官方分发源拉取 Minecraft 客户端及其运行所需资源，按官方
// SHA-1 逐个校验，铺成标准 Minecraft 安装布局，使 mili-loader 的
// MinecraftDiscovery / LibraryResolver 零改动即可发现。
//
// 关键约束：
//   * 只做「环境就绪」，不做账号认证 —— 登录仍由官方流程完成。
//   * 绝不内嵌或再分发 Minecraft 二进制产物到本仓库；全部运行时从官方 CDN 拉取。
//   * 零第三方依赖：仅用 JDK（HttpClient / ZipFile / MessageDigest）。

plugins {
    `java-library`
    application
}

// ── 版本单一来源（由根项目从 gradle.properties 强制注入，此处只读取） ────────
val miliPlatformVersion: String = rootProject.extra["miliPlatformVersion"] as String
val miliAbiVersion: String = rootProject.extra["miliAbiVersion"] as String
val minecraftVersion: String = rootProject.extra["minecraftVersion"] as String


application {
    mainClass.set("org.loader.installer.InstallerMain")
}

dependencies {
    // Zero third-party dependencies — JDK only.
    // Deliberately does NOT depend on loader/runtime: the installer is a
    // standalone bootstrap tool that must run before anything else exists.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
        showStandardStreams = false
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Mili Minecraft Installer",
            "Implementation-Version" to version,
            "Main-Class" to "org.loader.installer.InstallerMain",
            "Mili-Platform" to miliPlatformVersion,
            "Mili-Abi" to miliAbiVersion,
            "Mili-Minecraft" to minecraftVersion
        )
    }
}
