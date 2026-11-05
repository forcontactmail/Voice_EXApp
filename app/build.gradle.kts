plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.voiceex.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voiceex.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 116
        versionName = "1.2.103"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ВАЖНО: Выравнивание таргета компилятора Java на версию 17
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // ВАЖНО: Выравнивание таргета компилятора Kotlin на версию 17
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
