plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gymclub.manager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gymclub.manager"
        // 26 = Android 8.0: floor for the adaptive launcher icon and the
        // notification channel API used by the dues reminders.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // No obfuscation: the whole app is a few thousand lines and a
            // readable stack trace beats a few hundred saved bytes.
            isMinifyEnabled = false
            isShrinkResources = false
            // Signed with the Android debug key so the build always produces
            // something installable for side-loading. Not store-publishable.
            signingConfig = signingConfigs.getByName("debug")
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
        viewBinding = true
    }
}

dependencies {
    // Deliberately tiny: no Room/KSP, no kapt, no Firebase, no networking
    // library. Storage is plain SQLite and the app declares no INTERNET
    // permission at all.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    testImplementation("junit:junit:4.13.2")
}
