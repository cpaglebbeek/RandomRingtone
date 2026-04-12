plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

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
    }

    defaultConfig {
        applicationId = "nl.icthorse.randomringtone"
        minSdk = 26
        targetSdk = 35
        versionCode = 106
        versionName = "1.8.5"

        // Build metadata — automatisch bijgewerkt bij elke release
        buildConfigField("String", "CODENAME", "\"Michael_Jackson\"")
        buildConfigField("String", "RELEASE_NAME", "\"The_Way_You_Make_Me_Feel\"")
        buildConfigField("int", "BUILD_NUMBER", "106")
        buildConfigField("String", "BUILD_STATUS", "\"DEBUG\"")  // DEBUG of STABLE
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
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
            output.outputFileName = "RandomRingtone-v${variant.versionName}-Michael_Jackson-HIStory-${variant.buildType.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
}
