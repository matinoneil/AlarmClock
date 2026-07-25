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
        // No custom debug signingConfig any more. The committed keystore that used
        // to live here was PUBLIC (see PROJECT_NOTES) and was retired at V2.3.1;
        // AGP's own per-machine debug key is used instead. Entry 0.3's reason for
        // pinning it -- consistent signing so CI debug builds installed over each
        // other -- no longer applies, because CI ships the RELEASE variant signed
        // from repository secrets. A build with no secrets set therefore produces
        // an APK that will not install over a real one, which is the safe outcome.

        // Release signing comes from CI secrets and NEVER from the repo -- see the
        // "THE REPO IS PUBLIC, and the signing key is in it" section in
        // PROJECT_NOTES. The workflow base64-decodes the keystore to a runner temp
        // path and exports KEYSTORE_PATH; this config only exists when that has
        // happened, so a local build, a fork, or any run without the secrets falls
        // through to debug signing below instead of failing.
        val releaseKeystore = System.getenv("KEYSTORE_PATH")
        if (!releaseKeystore.isNullOrBlank() && file(releaseKeystore).exists()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
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
            // Prefer the secret-backed release key; fall back to the committed debug
            // key so nothing breaks on a machine or run without the secrets.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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

// Room writes the schema JSON for every DB version here. exportSchema is on
// (entry #71d) so a version bump that forgets its Migration shows up as a schema
// diff in review instead of silently wiping every alarm through the destructive
// fallback, which stays in place deliberately. NOTE: the JSON is emitted at BUILD
// time, so schemas/ only populates once someone builds; commit it to get the
// review benefit.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
