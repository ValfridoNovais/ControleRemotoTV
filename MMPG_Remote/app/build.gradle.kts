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
    // Play Billing Library 8+ é obrigatória para apps novos/atualizações a
    // partir de 31/08/2026 — ver https://developer.android.com/google/play/billing/deprecation-faq
    // Presa em 8.0.0 (a mais nova é 9.1.0): a 9.x é compilada com metadata
    // Kotlin 2.3, incompatível com o Kotlin 2.0.21 deste projeto — subir
    // para 9.x exige primeiro atualizar o plugin Kotlin em build.gradle.kts.
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    // Used only to build the self-signed X.509 certificate for the client
    // TLS identity (see CertificateStore) - Android has no public API to
    // build a certificate from an arbitrary software-generated KeyPair.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    // WebSocket transport for LgWebOsProvider (SSAP protocol) - Android has
    // no built-in WebSocket client; OkHttp's is the de facto standard one.
    // Pinned to the 4.x line (not the newer 5.x) for the same reason as the
    // Billing Library above: known-safe Kotlin metadata compatibility with
    // this project's Kotlin 2.0.21, verified by a local build.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
