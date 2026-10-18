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
        versionCode = 7
        versionName = "1.5.0"
    }
    signingConfigs {
        create("nh") {
            storeFile = rootProject.file("nh-release.jks")
            storePassword = "nutraherbal"
            keyAlias = "nh"
            keyPassword = "nutraherbal"
            enableV1Signing = true   // old-style signature too (some phone installers still look for it)
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("nh")
        }
        getByName("debug") { signingConfig = signingConfigs.getByName("nh") }
        // Test build signed with Android's own standard debug key (used to find out whether
        // an install problem is caused by the release key or by the APK itself).
        create("testkey") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // Don't add the extra "dependency info" block to the APK signing block (some OEM installers choke on unknown blocks).
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
