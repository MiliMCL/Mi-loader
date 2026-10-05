// mili-abi — Mili Application Binary Interface
// Pure interfaces + data types; no runtime/loader dependencies.
// Mods compile only against this module.

// ── 版本单一来源（由根项目从 gradle.properties 强制注入，此处只读取） ────────
val miliPlatformVersion: String = rootProject.extra["miliPlatformVersion"] as String
val miliAbiVersion: String = rootProject.extra["miliAbiVersion"] as String
val minecraftVersion: String = rootProject.extra["minecraftVersion"] as String


dependencies {
    // Zero runtime dependencies — this is the stable ABI surface.
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Mili ABI",
            "Implementation-Version" to version,
            "Mili-Platform" to miliPlatformVersion,
            "Mili-Abi" to miliAbiVersion
        )
    }
}
