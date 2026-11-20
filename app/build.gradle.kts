plugins {
    id("com.android.application")
}

android {
    namespace = "app.wattlo"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.wattlo"
        minSdk = 30
        targetSdk = 35 // Google Play: Android 15 for Wear OS apps
        versionCode = rootProject.extra["watchVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
    }

    // The Google Play upload key: only when the environment gives it (tools/build_play_bundles.sh asks for the
    // passwords and sets these variables), never stored in the repository. Without them release builds are signed
    // with the debug key, so that they install over the debug build on the developer's own devices.
    signingConfigs {
        val store = System.getenv("WATTLO_UPLOAD_STORE")
        if (store != null) create("upload") {
            storeFile = file(store)
            storePassword = System.getenv("WATTLO_UPLOAD_STORE_PASSWORD")
            keyAlias = System.getenv("WATTLO_UPLOAD_KEY_ALIAS")
            keyPassword = System.getenv("WATTLO_UPLOAD_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8 strips unused library code: a much smaller APK and less RAM on the watch
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("com.google.guava:guava:33.3.1-android")
    testImplementation("junit:junit:4.13.2")
    // the proto types of ProtoLayout, to measure the size of the tile layouts in a unit test
    testImplementation("androidx.wear.protolayout:protolayout-proto:1.2.1")
}
