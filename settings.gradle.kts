pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.neoforged.net/releases/")
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "custom-loading-screen"

stonecutter {
    create(rootProject) {
        version("1.21.1-neoforge", "1.21.1").buildscript("build.neoforge.gradle.kts")
        vcsVersion = "1.21.1-neoforge"
    }
}
