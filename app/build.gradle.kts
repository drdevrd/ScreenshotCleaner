plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.drdevrd.screenshotcleaner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.drdevrd.screenshotcleaner"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // On-device text recognition (OCR) — bundled model, no network
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // On-device image labeling — bundled model, no network
    implementation("com.google.mlkit:image-labeling:17.0.9")
    // TFLite EfficientNet-Lite0 image classifier — 1000 ImageNet classes, on-device
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // Coroutine support for Google Tasks (ML Kit returns Task<T>)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Coil for local image + video-frame thumbnail loading
    implementation("io.coil-kt:coil:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")

    // OkHttp for optional Claude Vision API integration (opt-in Deep Categorize feature)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ONNX Runtime for on-device CLIP image + text encoders (semantic search)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")
}
