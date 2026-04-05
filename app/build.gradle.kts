plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// mm-env-001: Read ANTHROPIC_API_KEY from .env (gitignored) so the user never
// has to touch source code. Falls back to empty string if .env is absent.
// Usage: add ANTHROPIC_API_KEY=sk-ant-... to MedusaMobile/.env
val envApiKey: String = run {
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .firstOrNull { it.startsWith("ANTHROPIC_API_KEY=") }
            ?.removePrefix("ANTHROPIC_API_KEY=")
            ?.trim()
            .orEmpty()
    } else ""
}

android {
    namespace = "com.medusa.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.medusa.mobile"
        minSdk = 26         // Android 8.0+ — covers 95%+ of active devices
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // mm-env-001: Expose API key to app via BuildConfig.ANTHROPIC_API_KEY
        // Empty string if .env not present (user will enter key in Settings).
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$envApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

dependencies {
    // ── Jetpack Compose (BOM-managed) ────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ── AndroidX Core ────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.8.9")

    // ── Networking (Claude API) ──────────────────────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // ── JSON ─────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ── Coroutines ───────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ── DataStore (settings persistence) ─────────────────────────────
    implementation("androidx.datastore:datastore-preferences:1.1.4")

    // ── Security (EncryptedSharedPreferences for API key) ──────────
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ── Room DB (mm-017: persistent memory store) ────────────────────
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // ── Google Sign-In + APIs (mm-019: Google Docs/Drive tool) ──────
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.google.api-client:google-api-client-android:2.7.1") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-docs:v1-rev20260323-2.0.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20260322-2.0.0")

    // ── Email / IMAP (mm-016: IMAP fallback for iCloud, Yahoo, Outlook) ─
    // android-mail is the JavaMail port for Android (javax.mail API, SSL/TLS IMAP)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // ── Testing ──────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
