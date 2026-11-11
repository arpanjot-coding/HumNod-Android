plugins {
    id("com.android.application")
}

android {
    namespace = "ai.humnod.genai.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.humnod.genai.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 7
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.itextpdf:kernel:7.2.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
