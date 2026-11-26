pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // v2.3.0: NewPipeExtractor (YouTube op het toestel) wordt alleen via JitPack gepubliceerd
    }
}

rootProject.name = "RandomRingtone"
include(":app")
