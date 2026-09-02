plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.deepseek.harness"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.deepseek.harness"
        // API 26 is the floor for the adaptive launcher icon and the modern
        // notification channel API; older devices are also too small for a
        // Node runtime to be usable.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // arm64 is every phone made in the last several years. Adding
            // armeabi-v7a would roughly double the APK for a shrinking
            // audience; build it separately if you need it.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // Populated from environment/properties in CI. When absent the
            // release variant falls back to the debug key below so a build
            // always produces an installable artifact.
            val storePath = System.getenv("DSH_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank() && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = System.getenv("DSH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DSH_KEY_ALIAS")
                keyPassword = System.getenv("DSH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // The payload is JS in assets; shrinking only touches the thin
            // Kotlin shell and would risk stripping WebView entry points for
            // no meaningful size gain.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (System.getenv("DSH_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    packaging {
        jniLibs {
            // The Node executable must land on disk as a real file the
            // installer marks executable; leaving it compressed inside the
            // APK would make it unexecutable.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/*")
        }
    }

    androidResources {
        // payload.zip is already deflated at maximum; re-compressing it in
        // the APK wastes build time and can actually grow the file.
        noCompress += listOf("zip")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
