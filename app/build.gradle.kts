import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing reads a gitignored keystore.properties (storeFile/storePassword/keyAlias/keyPassword).
// Absent on most machines → the signingConfigs block below is skipped and release builds are simply
// unsigned; nothing breaks. Generating + backing up the permanent keystore is a deliberate follow-up.
// NEVER commit keystore.properties or the .jks.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.goushik.upiwallet"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.goushik.upiwallet"
        minSdk = 31
        targetSdk = 36
        versionCode = 7
        versionName = "1.1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Created only when keystore.properties exists (see top of file) — keeps debug/CI builds working
        // on machines without the keystore.
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed only when the release config above was created; otherwise the release APK is unsigned.
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Don't stamp the git commit into the APK. It makes the binary differ between builds of
            // identical source, which breaks the byte-for-byte rebuild F-Droid uses to verify that a
            // release really came from this source (and to publish it under the developer's key).
            vcsInfo {
                include = false
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // Don't embed the dependency-metadata blob AGP adds to the APK signing block. It's an opaque,
    // Play-Store-oriented record of the dependency tree that can't be verified from source, so
    // F-Droid's scanner rejects any APK carrying it ("extra signing block").
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.haze)
    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (the android.jar org.json is a stub that throws) — lets
    // BackupTest exercise the encode/decode round-trip without a device.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}