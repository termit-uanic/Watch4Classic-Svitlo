plugins {
    id("com.android.application")
}

// The phone app. It shares the package name with the watch app (:app) and is signed with the same key, which the
// Wear OS Data Layer needs to pair the two apps later; the code shared by both lives in :core.
android {
    namespace = "app.wattlo.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.wattlo"
        minSdk = 26
        targetSdk = 36 // Google Play: Android 16 for new apps and updates
        versionCode = rootProject.extra["phoneVersionCode"] as Int
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
    // opens Google Play on the watch to install the app there (RemoteActivityHelper)
    implementation("androidx.wear:wear-remote-interactions:1.1.0")
    testImplementation("junit:junit:4.13.2")
}
