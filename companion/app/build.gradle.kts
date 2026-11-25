import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.luckyobserver.getafterit"
    // compileSdk bumped to 36 because transitive deps (notably androidx.core
    // 1.17.0 from PebbleKitAndroid2 1.1.0's dep closure) require Android 36
    // APIs to be available. targetSdk stays at 35; bumping
    // compileSdk doesn't change runtime behavior, only what APIs the code is
    // allowed to call against.
    compileSdk = 36

    defaultConfig {
        applicationId = "org.luckyobserver.getafterit"
        minSdk = 26
        targetSdk = 35    // runtime behavior, kept on 35
        // Release builds on GitHub set these so each release installs over the last.
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        // The release key comes from the environment (GitHub secrets in CI);
        // without it a release build fails rather than producing an unsigned APK.
        create("release") {
            storeFile = System.getenv("RELEASE_KEYSTORE")?.let(::file)
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = "getafterit"
            keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Kotlin source dirs (we use src/main/kotlin instead of src/main/java).
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
}

// Kotlin 2.3 removed the legacy `android { kotlinOptions { … } }` DSL.
// Use the top-level `kotlin { compilerOptions { … } }` block instead.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // === AndroidX core ===
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // === Compose ===
    val composeBom = platform("androidx.compose:compose-bom:2026.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // === PebbleKitAndroid2 ===
    // Published to Maven Central as of v1.0.0.
    implementation("io.rebble.pebblekit2:client:1.1.0")

    // === Mapsforge: renders offline map tiles from a user-supplied .map file ===
    // LGPL v3 with the relinking clauses (§4d, §4e) waived. Not on Maven Central after 0.25.
    implementation("com.github.mapsforge.mapsforge:mapsforge-map-android:0.30.0")
    implementation("com.github.mapsforge.mapsforge:mapsforge-themes:0.30.0")

    testImplementation("junit:junit:4.13.2")
}
