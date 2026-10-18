plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sakir.copilot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sakir.copilot"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
}

// Bilerek hiç harici kütüphane yok: sadece Android çerçevesi ve Kotlin standart kütüphanesi.
dependencies {
}
