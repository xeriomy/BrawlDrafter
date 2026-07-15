plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.xeriomy.brawldrafter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xeriomy.brawldrafter"
        minSdk = 26
        targetSdk = 34
        // CI injects versionCode / versionName via -P flags; local builds use defaults.
        versionCode = (findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionName") as? String) ?: "1.0.0"
    }

    buildTypes {
        debug {
            // Debug builds get a distinct applicationId suffix so they
            // can be installed side-by-side with a release build.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signing is only configured when the keystore env vars are present (CI).
            // Local release builds fall back to the default debug signing automatically.
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(ksFile)
                    keyAlias = System.getenv("KEYSTORE_KEY_ALIAS")
                    keyPassword = System.getenv("KEYSTORE_KEY_PASSWORD")
                    storePassword = System.getenv("KEYSTORE_STORE_PASSWORD")
                }
            }
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
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // ML Kit - Text Recognition (OCR)
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Room (local cache for meta data)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DI (Koin - lightweight for Android)
    implementation("io.insert-koin:koin-android:3.5.3")
    implementation("io.insert-koin:koin-androidx-compose:3.5.3")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // DataStore (preferences)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Coil (image loading for brawler icons)
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Security (API key encryption)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}