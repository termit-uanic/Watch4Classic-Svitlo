plugins {
    id("com.android.library")
}

// Code shared by the watch app (:app) and, later, the phone app: the schedule and its parsers, the cache,
// the settings, the background refresh, the reminders, the day dial and the strings.
android {
    namespace = "com.yehor.lightwatch.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
}
