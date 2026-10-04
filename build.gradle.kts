plugins {
    `java-library`
}

// Root project: aggregates submodules, no source code.
// All source lives in mili-abi, mili-runtime, mili-loader, mili-minecraft-integration.

subprojects {
    apply(plugin = "java-library")

    group = "org.loader"
    version = rootProject.findProperty("miliPlatformVersion") ?: "0.1.0"

    java {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
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
    }
}
