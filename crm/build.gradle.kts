plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nutraherbal.crmapp"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.nutraherbal.crmapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
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
