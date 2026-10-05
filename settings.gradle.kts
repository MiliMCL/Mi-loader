pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("com.gradleup.shadow") version "9.4.1" apply false
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "mili-platform"

include("mili-abi")
include("mili-runtime")
include("mili-loader")
include("mili-minecraft-integration")
include("mili-installer")
