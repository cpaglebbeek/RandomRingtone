plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

val appCodename = "Elton_John"
val appReleaseName = "Rocket_Man"

android {
    namespace = "nl.icthorse.randomringtone"
    compileSdk = 35

    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/randomringtone-release.jks")
            storePassword = "RandomRing2026!"
            keyAlias = "randomringtone"
            keyPassword = "RandomRing2026!"
        }
    }

    defaultConfig {
        applicationId = "nl.icthorse.randomringtone"
        minSdk = 26
        targetSdk = 35
        versionCode = 147
        versionName = "2.3.0"

        // Build metadata — automatisch bijgewerkt bij elke release
        buildConfigField("String", "CODENAME", "\"$appCodename\"")
        buildConfigField("String", "RELEASE_NAME", "\"$appReleaseName\"")
        buildConfigField("int", "BUILD_NUMBER", "143")
        buildConfigField("String", "BUILD_STATUS", "\"DEBUG\"")  // DEBUG of STABLE
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val suffix = if (variant.buildType.name == "release") "release" else "debug"
            output.outputFileName = "RandomRingtone-v${variant.versionName}-${appCodename}-${appReleaseName}-${suffix}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // NewPipeExtractor (java.nio/java.time op oudere Android)
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.icons.extended)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // SpotifyPreviewClient.parseEmbed in JVM-tests
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // WorkManager (scheduled ringtone changes)
    implementation(libs.work.runtime.ktx)

    // Room (playlist cache + schedule)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Network (Deezer API + MP3 download)
    implementation(libs.okhttp)

    // Serialization (JSON parsing)
    implementation(libs.kotlinx.serialization.json)

    // SAF (DocumentFile voor backup naar cloud)
    implementation(libs.documentfile)

    // Core
    implementation(libs.core.ktx)
    implementation(libs.datastore.preferences)

    // v2.3.0: YouTube zoeken + audio-extractie op het toestel (eigen IP) — GPL-3.0
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
