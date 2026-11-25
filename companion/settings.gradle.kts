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
        // Mapsforge (offline map rendering) is published on JitPack since 0.26.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "GetAfterIt"
include(":app")
