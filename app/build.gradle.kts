plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.historyprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.historyprobe"
        minSdk = 28
        targetSdk = 34
        versionCode = 11          // 第1段より大きければ上書きインストール可
        versionName = "0.3.1-record"
    }
    signingConfigs {
        create("pinned") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("pinned") }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
