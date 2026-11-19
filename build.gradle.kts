plugins {
    id("com.android.application") version "9.5.1" apply false
}

// the version of both apps, from gradle.properties
apply(from = "gradle/versioning.gradle.kts")
