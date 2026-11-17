plugins {
    id("com.android.application")
}

// The phone app. It shares the package name with the watch app (:app) and is signed with the same key, which the
// Wear OS Data Layer needs to pair the two apps later; the code shared by both lives in :core.
android {
    namespace = "com.yehor.lightwatch.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yehor.lightwatch"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
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
    testImplementation("junit:junit:4.13.2")
}
