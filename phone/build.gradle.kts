plugins {
    id("com.android.application")
}

// The phone app. It shares the package name with the watch app (:app) and is signed with the same key, which the
// Wear OS Data Layer needs to pair the two apps later; the code shared by both lives in :core.
android {
    namespace = "app.wattlo.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.wattlo"
        minSdk = 26
        targetSdk = 34
        versionCode = rootProject.extra["phoneVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // personal app: sign with the debug key so it installs over the debug build
            signingConfig = signingConfigs.getByName("debug")
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
