plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sidephone.aviary"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sidephone.aviary"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // SP-01 is arm64; ship only that ABI to keep the APK small.
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Sign with a committed, well-known Android debug key so CI and local builds produce the SAME
    // signature (release APKs stay update-compatible). Not a secret — it's the standard debug key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign with the debug key so release builds install directly for on-device testing.
            // The win here is a non-debuggable, AOT-friendly build — Compose scrolling is far
            // smoother than a debug build on low-end hardware.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("main") {
            // libaviary_imessage.so (rustpush + on-device anisette), built by cargo-ndk.
            jniLibs.srcDir("src/main/rust/aviary_imessage/jniLibs")
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // libsignal's testing native lib is not needed at runtime.
        jniLibs.excludes += "**/libsignal_jni_testing.so"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Unified message store, encrypted at rest (lesson learned from Sunbird 2023)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.5.6@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Signal linked-device provisioning + protocol
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Async image loading (avatars, media thumbnails) so scrolling never decodes on the UI thread.
    implementation("io.coil-kt:coil-compose:2.6.0")
    // Animated GIF/WebP decoding (via Android's ImageDecoder) for received media.
    implementation("io.coil-kt:coil-gif:2.6.0")
    // Video playback (Instagram reels) with correct aspect ratio + built-in controls.
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("com.google.zxing:core:3.5.2")
    implementation("org.signal:libsignal-android:0.86.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Watchdog: a periodic worker that restarts the receive service if the OEM killed it.
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    debugImplementation("androidx.compose.ui:ui-tooling")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
