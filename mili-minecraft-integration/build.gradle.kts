// mili-minecraft-integration — Minecraft 26.x Bridge
// Tick poller, event bridge, registry bridge.
// Depends on runtime (no direct loader dependency).

dependencies {
    implementation(project(":mili-runtime"))
    implementation(project(":mili-abi"))
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Mili Minecraft 26.x Integration",
            "Implementation-Version" to version,
            "Mili-Platform" to (rootProject.findProperty("miliPlatformVersion") ?: "0.1.0"),
            "Mili-Abi" to (rootProject.findProperty("miliAbiVersion") ?: "1"),
            "Mili-Minecraft" to (rootProject.findProperty("minecraftVersion") ?: "26.2")
        )
    }
}
