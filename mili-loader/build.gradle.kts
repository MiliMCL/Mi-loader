// mili-loader — Mili Loader Bootstrap
// Discovery, classloading, GameProvider SPI, entrypoint hooks.
// Depends on runtime for Scope/kernel; transitively on abi.
// Depends on minecraft-integration because EntryPointHook uses MinecraftBootstrap/Events.

dependencies {
    implementation(project(":mili-runtime"))
    implementation(project(":mili-minecraft-integration"))
    implementation(project(":mili-abi"))
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "org.loader.loader.LoaderMain",
            "Implementation-Title" to "Mili Loader",
            "Implementation-Version" to version,
            "Mili-Platform" to (rootProject.findProperty("miliPlatformVersion") ?: "0.1.0"),
            "Mili-Abi" to (rootProject.findProperty("miliAbiVersion") ?: "1"),
            "Multi-Release" to "false"
        )
    }
}
