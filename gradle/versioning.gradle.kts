// The version of the watch app and the phone app, computed from one place (gradle.properties: appVersion).
//
// versionCode = F * 100_000_000 + major * 1_000_000 + minor * 10_000 + patch * 100 + build
//   F = 1 for the phone, 2 for the watch (3 is kept for another form factor): Google Play wants a version code that
//   is unique across the form factors of one app; build (00..99) is -PbuildNo, raised for every upload.
// versionName = appVersion + versionSuffix, e.g. "1.0.0-beta".
fun versionCodeOf(formFactor: Int, version: String, build: Int): Int {
    val p = version.split(".").map { it.toInt() } + listOf(0, 0, 0)
    require(formFactor in 1..9 && p.take(3).all { it in 0..99 } && build in 0..99) { "bad version: $version build $build" }
    return formFactor * 100_000_000 + p[0] * 1_000_000 + p[1] * 10_000 + p[2] * 100 + build
}

val appVersion = providers.gradleProperty("appVersion").get()
val buildNo = providers.gradleProperty("buildNo").orElse("0").get().toInt()

extra["phoneVersionCode"] = versionCodeOf(1, appVersion, buildNo)
extra["watchVersionCode"] = versionCodeOf(2, appVersion, buildNo)
extra["appVersionName"] = appVersion + providers.gradleProperty("versionSuffix").orElse("").get()
