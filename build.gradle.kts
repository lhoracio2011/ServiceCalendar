// Root of the Gradle build for Android Studio. See settings.gradle.kts.

plugins {
    alias(toolchain.plugins.android.application) apply false
    alias(toolchain.plugins.android.kmpLibrary) apply false
    alias(toolchain.plugins.kotlin.multiplatform) apply false
    alias(toolchain.plugins.kotlin.serialization) apply false
    alias(toolchain.plugins.compose.compiler) apply false
    alias(toolchain.plugins.compose.multiplatform) apply false
}

// ./build belongs to the Kotlin Toolchain (artifacts, tasks, incremental.state).
// Keep Gradle's root-level output (e.g. the problems report) out of it.
layout.buildDirectory.set(layout.projectDirectory.dir("build-gradle"))
