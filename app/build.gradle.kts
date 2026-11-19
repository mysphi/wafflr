plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "app.wafflr"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.wafflr"
        minSdk = 30
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.0.$runNumber-spike"
        ndk {
            // Pixel 8 Pro (and almost every modern phone) is arm64. Keeps the APK small.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Debug-only key committed on purpose so every CI build installs over the last one.
        // Not for Play Store releases.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // sherpa-onnx calls Kotlin callbacks through JNI by their typed invoke() method,
        // which only class-based lambdas have.
        freeCompilerArgs += listOf("-Xlambdas=class")
    }

    androidResources {
        noCompress += listOf("onnx", "bin")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // sherpa-onnx offline TTS engine (Kokoro + Piper). Downloaded by scripts/fetch-deps.sh.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    // Unpacks downloaded voice packs (.tar.bz2)
    implementation("org.apache.commons:commons-compress:1.27.1")
    // On-device text recognition for drawn areas (model bundled in the APK, works offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
