import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    kotlin("android")
}

// Every debug build gets a distinct version string (shown in the app header)
// so a fresh install is trivially distinguishable from the previous one
// during iterative testing.
val debugVersionSuffix = "-D" + SimpleDateFormat("yyyyMMdd.HHmm").format(Date())

android {
    namespace = "online.mmpg.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "online.mmpg.remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_MOCK", "true")
            versionNameSuffix = debugVersionSuffix
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

    packaging {
        resources {
            // Multiple Bouncy Castle jars (bcpkix/bcutil/bcprov) each ship an
            // identical multi-release-jar manifest at this path; only one
            // copy needs to survive packaging, and none of it is read at
            // runtime on Android anyway.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Used only to build the self-signed X.509 certificate for the client
    // TLS identity (see CertificateStore) - Android has no public API to
    // build a certificate from an arbitrary software-generated KeyPair.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
}
