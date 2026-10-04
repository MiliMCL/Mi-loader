plugins {
    java
}

group = "org.loader"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "org.loader.loader.LoaderMain",
            "Implementation-Title" to "Mili Runtime Loader",
            "Implementation-Version" to version,
            "Multi-Release" to "false"
        )
    }
}
