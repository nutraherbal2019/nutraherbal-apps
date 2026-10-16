plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nutraherbal.calling"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.nutraherbal.calling"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }
    signingConfigs {
        create("nh") {
            storeFile = rootProject.file("nh-release.jks")
            storePassword = "nutraherbal"
            keyAlias = "nh"
            keyPassword = "nutraherbal"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("nh")
        }
        getByName("debug") { signingConfig = signingConfigs.getByName("nh") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
