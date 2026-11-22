plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tvremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tvremote"
        minSdk = 33          // GLOBAL_ACTION_DPAD_* need Android 13+
        targetSdk = 35       // behaves as 34 on the Android 14 onn box
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // CI signs with a fixed key from GitHub Secrets so every new APK installs
    // over the previous one. Without the secret, it falls back to the debug key.
    val ksFile = file("${rootDir}/release.jks")
    signingConfigs {
        if (ksFile.exists()) {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "tvremote"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile.exists()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Play Store lint checks don't matter for a sideloaded app.
    lint { checkReleaseBuilds = false }
}

dependencies {
    // Tiny embedded HTTP + WebSocket server (~150 KB). The only runtime dependency.
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
}
