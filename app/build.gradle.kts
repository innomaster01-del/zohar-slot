plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zohar.slotar"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.zohar.slotar"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "2.7"
    }
    splits { abi { isEnable = false } }
    // fixed sideload key, so a new APK installs over the old one (not a Play Store key)
    signingConfigs {
        create("zohar") { storeFile = file("zohar-sideload.jks"); storePassword = "zoharplay"; keyAlias = "zohar"; keyPassword = "zoharplay" }
    }
    buildTypes {
        release {
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }   // phones; the debug build keeps x86_64 for the emulator smoke test
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("zohar")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("task", "glb", "wav", "filamat", "ktx") }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }

dependencies {
    implementation("io.github.sceneview:arsceneview:4.60.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.26") { exclude(group = "com.google.auto.value", module = "auto-value") }
    testImplementation("junit:junit:4.13.2")
}
