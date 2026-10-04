// mili-runtime — Mili Runtime Kernel
// Scope/Scheduler/EventBus/Capability/Resource/lifecycle + Mod SDK + services
// No Minecraft or loader code here.

dependencies {
    api(project(":mili-abi"))
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Mili Runtime Kernel",
            "Implementation-Version" to version,
            "Mili-Platform" to (rootProject.findProperty("miliPlatformVersion") ?: "0.1.0"),
            "Mili-Abi" to (rootProject.findProperty("miliAbiVersion") ?: "1")
        )
    }
}
