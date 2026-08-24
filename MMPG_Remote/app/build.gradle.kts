plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "online.mmpg.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "online.mmpg.remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_MOCK", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "ALLOW_MOCK", "false")

            val ksFile = System.getenv("ANDROID_KEYSTORE_FILE")
            if (!ksFile.isNullOrBlank()) {
                signingConfig = signingConfigs.create("releaseFromEnv") {
                    storeFile = file(ksFile)
                    storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                    keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
