// 中文交付版：生产编译参数与 v1.3.1 一致，仅移除未交付的测试配置。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jmu.text2scpi"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.owon.voicecontrol"
        minSdk = 31
        targetSdk = 31
        versionCode = 23
        versionName = "1.5.1-wakefix"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/capture-debug.jks")
            storePassword = "android"
            keyAlias = "capture-debug"
            keyPassword = "android"
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("debug"); isMinifyEnabled = false } }
    buildFeatures { viewBinding = true; buildConfig = true }

    packaging {
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += setOf("**/*.so") }
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    androidResources { noCompress += listOf("rkllm", "rknn", "onnx") }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(files("libs/sherpa-onnx-1.13.8-rknn.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

}
