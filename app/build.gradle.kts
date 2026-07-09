plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Overridable from the command line (used by CI to inject the release tag as the
// version name, e.g. -PversionNameOverride=1.5), so a GitHub Release's tag becomes
// what shows up in Settings > Apps automatically. Falls back to a sensible default
// for local Android Studio builds where nothing is passed in.
val appVersionName: String = (project.findProperty("versionNameOverride") as String?) ?: "1.4"
val appVersionCode: Int = (project.findProperty("versionCodeOverride") as String?)?.toIntOrNull() ?: 2

android {
    namespace = "no.hanss.alarmclock"
    compileSdk = 34

    defaultConfig {
        applicationId = "no.hanss.alarmclock"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        getByName("debug") {
            // A fixed, committed debug keystore (not the AGP-generated one, which
            // differs per machine/CI-run) so every debug build -- local or from
            // GitHub Actions -- is signed with the same key. Without this, installing
            // a newer build over an older one fails with "signatures don't match"
            // unless you uninstall first. This is a debug-only key with no
            // confidentiality requirement, so it's fine to commit to the repo.
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Deliberately signed with the SAME committed keystore as debug
            // builds: identical signature means a release APK installs cleanly
            // over the previously-shipped debug-signed installs (and vice
            // versa) with no uninstall. CI ships this variant because Compose
            // performance in debuggable builds is drastically worse -- the
            // debug variant is only for local inspection now.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
