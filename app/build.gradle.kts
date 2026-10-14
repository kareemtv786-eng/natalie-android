plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.natalie.assistant"
    compileSdk = 35
    defaultConfig {
        // Side-by-side test application: avoids a signature conflict with any
        // existing com.natalie.assistant application installed on the phone.
        applicationId = "com.natalie.assistant.m1test"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-m1test"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Official quick-start dependency from the source project's README.
    // The Android library AAR must transitively supply ONNX Runtime and models.
    implementation("com.github.msnilsen:openwakeword-android:0.1.0")
}
