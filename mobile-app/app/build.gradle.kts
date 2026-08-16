plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.naderai.appstore"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.naderai.appstore"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../signing/release.keystore")
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "naderai2024"
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "naderai"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "naderai2024"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions { jvmTarget = "1.8" }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
