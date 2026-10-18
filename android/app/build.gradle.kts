plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pwrlive.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pwrlive.app"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("PWR_VERSION_CODE") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("PWR_VERSION_CODE") ?: "1")
    }

    // A fixed signing key so each new build installs over the previous one.
    signingConfigs {
        create("stable") {
            storeFile = file("pwrlive.keystore")
            storePassword = "pwrlive"
            keyAlias = "pwrlive"
            keyPassword = "pwrlive"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("stable") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
