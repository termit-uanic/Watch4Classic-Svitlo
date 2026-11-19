package app.wattlo.phone

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import app.wattlo.Notify

// Pages of the system settings that the phone app sends the user to (shared by the settings and the setup wizard).

/**
 * Permission not asked yet, or may be asked again: show the system dialog.
 * If denied permanently or turned off in settings: open the app's notification settings.
 */
fun Activity.askNotificationPermission() {
    if (Notify.enabled(this)) return
    val perm = android.Manifest.permission.POST_NOTIFICATIONS
    val granted = android.os.Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val prefs = getSharedPreferences("notify", android.content.Context.MODE_PRIVATE)
    val askedBefore = prefs.getBoolean("asked", false)
    if (!granted && (!askedBefore || shouldShowRequestPermissionRationale(perm))) {
        prefs.edit().putBoolean("asked", true).apply()
        requestPermissions(arrayOf(perm), 1)
    } else {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }
}

/** The autostart page of the vendor's security app (Xiaomi), or else the app's own page in the system settings. */
fun Activity.openAutostart() {
    val xiaomi = Intent().setComponent(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    )
    if (!tryStart(xiaomi)) openAppDetails()
}

/** The list of apps with battery restrictions, or else the app's own page. */
fun Activity.openBattery() {
    if (!tryStart(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppDetails()
}

fun Activity.openAppDetails() {
    tryStart(
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    )
}

fun Activity.tryStart(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess
