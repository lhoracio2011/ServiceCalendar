// Gradle build of the Android app for Android Studio.
// The source of truth is androidApp/module.yaml (Kotlin Toolchain); this file mirrors it,
// including what the Toolchain sets implicitly (SDK levels, R8 for release, signing).

import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no org.jetbrains.kotlin.android plugin.
    alias(toolchain.plugins.android.application)
    alias(toolchain.plugins.compose.compiler)
}

// Release signing, same as the Toolchain: androidApp/keystore.properties with
// storeFile / storePassword / keyAlias / keyPassword (storeFile relative to androidApp/).
// Without the file the release APK is built unsigned instead of failing.
val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropertiesFile.isFile
if (!hasReleaseKeystore) {
    logger.warn("androidApp/keystore.properties not found: the release APK will be unsigned.")
}

android {
    namespace = "app.servicecalendar"
    compileSdk = toolchain.versions.android.compileSdk.get().toInt()
    buildToolsVersion = toolchain.versions.android.buildTools.get()

    defaultConfig {
        applicationId = "app.servicecalendar"
        minSdk = toolchain.versions.android.minSdk.get().toInt()
        targetSdk = toolchain.versions.android.targetSdk.get().toInt()
        versionCode = 3
        versionName = "1.2"
    }

    // Toolchain layout: androidApp/src (Kotlin + AndroidManifest.xml), androidApp/res, androidApp/test.
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/AndroidManifest.xml")
            kotlin.directories.add("src")
            res.directories.add("res")
        }
        getByName("test") {
            kotlin.directories.add("test")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // debug: AGP default, signed with ~/.android/debug.keystore (as with ./kotlin).
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.auth)
    implementation(toolchain.compose.preview)
    implementation(toolchain.compose.uiTooling)
    // Implicit in the Toolchain when Compose is enabled.
    implementation(toolchain.compose.runtime)
    implementation(toolchain.compose.components.resources)

    testImplementation(toolchain.kotlin.test.junit5)
    testRuntimeOnly(toolchain.junit.jupiter.engine)
    testRuntimeOnly(toolchain.junit.platform.launcher)
}
