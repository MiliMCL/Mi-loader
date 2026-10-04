// mili-abi — Mili Application Binary Interface
// Pure interfaces + data types; no runtime/loader dependencies.
// Mods compile only against this module.

dependencies {
    // Zero runtime dependencies — this is the stable ABI surface.
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Mili ABI",
            "Implementation-Version" to version,
            "Mili-Platform" to (rootProject.findProperty("miliPlatformVersion") ?: "0.1.0"),
            "Mili-Abi" to (rootProject.findProperty("miliAbiVersion") ?: "1")
        )
    }
}
