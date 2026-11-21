plugins {
    id("com.android.library")
}

// Code shared by the watch app (:app) and, later, the phone app: the schedule and its parsers, the cache,
// the settings, the background refresh, the reminders, the day dial and the strings.
android {
    namespace = "app.wattlo.core"
    compileSdk = 36

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
    // Wear OS Data Layer: the shared settings of the watch and the phone (SettingsSync)
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    testImplementation("junit:junit:4.13.2")
    // org.json of the Android runtime is only a stub in unit tests
    testImplementation("org.json:json:20240303")
}
