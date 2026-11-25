// Gradle build for Android Studio (Android only). The primary build is the Kotlin Toolchain
// (project.yaml, */module.yaml, ./kotlin); this build reuses its source layout and versions.
// iOS is built only with ./kotlin and Xcode.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        // Same catalog the Toolchain uses ($libs.*).
        create("libs") { from(files("libs.versions.toml")) }
        // Versions the Toolchain provides implicitly ($compose.*, Kotlin, AGP, SDK levels).
        create("toolchain") { from(files("gradle/toolchain.versions.toml")) }
    }
}

rootProject.name = "service-calendar"

include(":shared")
include(":androidApp")
