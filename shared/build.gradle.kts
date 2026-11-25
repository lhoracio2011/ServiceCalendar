// Gradle build of the `shared` module for Android Studio (Android target only).
// The source of truth is shared/module.yaml (Kotlin Toolchain); this file mirrors it and
// points Gradle at the Toolchain directory layout instead of src/<sourceSet>/kotlin.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(toolchain.plugins.kotlin.multiplatform)
    alias(toolchain.plugins.android.kmpLibrary)
    alias(toolchain.plugins.kotlin.serialization)
    alias(toolchain.plugins.compose.compiler)
    alias(toolchain.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "app.servicecalendar.shared"
        compileSdk = toolchain.versions.android.compileSdk.get().toInt()
        minSdk = toolchain.versions.android.minSdk.get().toInt()
        buildToolsVersion = toolchain.versions.android.buildTools.get()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        // Compose resources are packaged as Android assets.
        androidResources {
            enable = true
        }

        // JVM unit tests (test/ + test@android/), like `./kotlin test -p android`.
        withHostTest {}
    }

    sourceSets {
        commonMain {
            kotlin.srcDir("src")
            dependencies {
                implementation(toolchain.compose.runtime)
                implementation(toolchain.compose.foundation)
                implementation(toolchain.compose.material3)
                implementation(toolchain.compose.ui)
                implementation(toolchain.compose.components.resources)
                implementation(toolchain.compose.preview)
                implementation(toolchain.compose.hotReload.runtimeApi)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.androidx.sqlite.bundled)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
            }
        }
        androidMain {
            kotlin.srcDir("src@android")
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(toolchain.compose.uiTooling)
                implementation(libs.ktor.client.okhttp)
            }
        }
        commonTest {
            kotlin.srcDir("test")
            dependencies {
                implementation(toolchain.kotlin.test)
                implementation(libs.ktor.client.mock)
            }
        }
        getByName("androidHostTest") {
            kotlin.srcDir("test@android")
            dependencies {
                implementation(toolchain.kotlin.test.junit5)
                runtimeOnly(toolchain.junit.jupiter.engine)
                runtimeOnly(toolchain.junit.platform.launcher)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "app.servicecalendar.resources"
    publicResClass = true
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = provider { layout.projectDirectory.dir("composeResources") },
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // JVM database tests load native SQLite; JDK 22+ requires native access to be enabled.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
