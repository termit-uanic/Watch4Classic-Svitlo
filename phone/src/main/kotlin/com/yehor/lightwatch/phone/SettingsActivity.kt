package com.yehor.lightwatch.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yehor.lightwatch.Fetcher
import com.yehor.lightwatch.Notify
import com.yehor.lightwatch.Palette
import com.yehor.lightwatch.QuietHours
import com.yehor.lightwatch.Settings
import com.yehor.lightwatch.Source
import com.yehor.lightwatch.core.R as CoreR
import kotlin.concurrent.thread

/** The phone's settings. They belong to this phone; the watch has its own (they will be synchronised later). */
class SettingsActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private lateinit var sourceRow: TextView
    private lateinit var queueRow: TextView
    private lateinit var yellow: SwitchRow
    private lateinit var ringsRow: TextView
    private lateinit var labelsRow: TextView
    private lateinit var notifyOffRow: TextView
    private lateinit var notifyOnRow: TextView
    private lateinit var notifyToday: SwitchRow
    private lateinit var notifyTomorrow: SwitchRow
    private lateinit var quietRow: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sourceRow = ui.settingRow { pickSource() }
        queueRow = ui.settingRow { pickQueue() }
        yellow = ui.switchRow {
            Settings.setMaybeIsOff(this, !Settings.maybeIsOff(this))
            Fetcher.notifyAll(this)
            render()
        }
        ringsRow = ui.settingRow { pickRings() }
        labelsRow = ui.settingRow { pickLabels() }
        notifyOffRow = ui.settingRow { onLeadRow(off = true) }
        notifyOnRow = ui.settingRow { onLeadRow(off = false) }
        notifyToday = ui.switchRow {
            val on = !Settings.notifyTodayChanged(this)
            Settings.setNotifyTodayChanged(this, on)
            if (on) askNotificationPermission()
            render()
        }
        notifyTomorrow = ui.switchRow {
            val on = !Settings.notifyTomorrowNew(this)
            Settings.setNotifyTomorrowNew(this, on)
            if (on) askNotificationPermission()
            render()
        }
        quietRow = ui.settingRow { pickQuiet() }

        val pad = ui.dp(16f).toInt()
        val gap = 8f
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 2)
            addView(ui.label(22f, Palette.WHITE, bold = true).apply { text = getString(CoreR.string.settings) })

            addView(ui.section(getString(R.string.data_title)))
            addView(sourceRow, ui.lp())
            addView(queueRow, ui.lp(gap))
            addView(yellow.card, ui.lp(gap))

            addView(ui.section(getString(R.string.display_title)))
            addView(ringsRow, ui.lp())
            addView(labelsRow, ui.lp(gap))

            addView(ui.section(getString(CoreR.string.notifications)))
            addView(notifyOffRow, ui.lp())
            addView(notifyOnRow, ui.lp(gap))
            addView(notifyToday.card, ui.lp(gap))
            addView(notifyTomorrow.card, ui.lp(gap))
            addView(quietRow, ui.lp(gap))

            addView(ui.section(getString(R.string.background_title)))
            addView(ui.label(13f, Palette.LABEL).apply { text = getString(R.string.background_hint) })
            addView(ui.settingRow { openAutostart() }.apply { text = getString(R.string.autostart_button) }, ui.lp(10f))
            addView(ui.settingRow { openBattery() }.apply { text = getString(R.string.battery_button) }, ui.lp(gap))
        }
        val width = minOf(resources.displayMetrics.widthPixels, ui.dp(560f).toInt())
        setContentView(ScrollView(this).apply {
            addView(column, FrameLayout.LayoutParams(width, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        })
    }

    override fun onResume() {
        super.onResume()
        render() // the permission or the battery mode may have changed in the system settings
    }

    // ---------------------------------------------------------------- pickers

    private fun pickSource() {
        val all = Source.entries
        AlertDialog.Builder(this)
            .setTitle(getString(CoreR.string.source))
            .setSingleChoiceItems(all.map { it.host }.toTypedArray(), all.indexOf(Settings.source(this))) { d, i ->
                d.dismiss()
                applyDataSetting { Settings.set(this, source = all[i]) }
            }
            .show()
    }

    private fun pickQueue() {
        val all = Settings.QUEUES
        AlertDialog.Builder(this)
            .setTitle(getString(CoreR.string.queue))
            .setSingleChoiceItems(
                all.map { getString(CoreR.string.queue_name, it) }.toTypedArray(), all.indexOf(Settings.queue(this))
            ) { d, i ->
                d.dismiss()
                applyDataSetting { Settings.set(this, queue = all[i]) }
            }
            .show()
    }

    /** A new source or queue: the cached schedule is not valid any more, fetch again. */
    private fun applyDataSetting(change: () -> Unit) {
        val before = Settings.source(this) to Settings.queue(this)
        change()
        render()
        if (before == Settings.source(this) to Settings.queue(this)) return
        Fetcher.notifyAll(this)
        thread { Fetcher.refreshAndNotify(applicationContext) }
    }

    private fun pickRings() {
        val items = arrayOf(getString(CoreR.string.tile_rings_one), getString(CoreR.string.tile_rings_two))
        AlertDialog.Builder(this)
            .setTitle(getString(CoreR.string.app_rings_title))
            .setSingleChoiceItems(items, Settings.appRings(this) - 1) { d, i ->
                d.dismiss()
                Settings.setAppRings(this, i + 1)
                render()
            }
            .show()
    }

    private fun pickLabels() {
        val items = arrayOf(getString(CoreR.string.tile_labels_outer), getString(CoreR.string.tile_labels_both))
        AlertDialog.Builder(this)
            .setTitle(getString(CoreR.string.app_labels_title))
            .setSingleChoiceItems(items, if (Settings.appInnerLabels(this)) 1 else 0) { d, i ->
                d.dismiss()
                Settings.setAppInnerLabels(this, i == 1)
                render()
            }
            .show()
    }

    /** Notifications on but blocked by the system: ask for permission first, otherwise pick the time. */
    private fun onLeadRow(off: Boolean) {
        val lead = if (off) Settings.notifyOff(this) else Settings.notifyOn(this)
        if (lead > 0 && !Notify.enabled(this)) askNotificationPermission() else pickLead(off)
    }

    private fun pickLead(off: Boolean) {
        val all = Notify.LEADS
        val cur = if (off) Settings.notifyOff(this) else Settings.notifyOn(this)
        AlertDialog.Builder(this)
            .setTitle(getString(if (off) CoreR.string.before_off else CoreR.string.before_on))
            .setSingleChoiceItems(
                all.map { Notify.leadLabel(this, it) }.toTypedArray(), all.indexOf(cur).coerceAtLeast(0)
            ) { d, i ->
                d.dismiss()
                if (off) Settings.setNotify(this, off = all[i]) else Settings.setNotify(this, on = all[i])
                if (all[i] > 0) askNotificationPermission()
                Notify.schedule(this)
                render()
            }
            .show()
    }

    /**
     * Permission not asked yet, or may be asked again: show the system dialog.
     * If denied permanently or turned off in settings: open the app's notification settings.
     */
    private fun askNotificationPermission() {
        if (Notify.enabled(this)) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        val granted = android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences("notify", MODE_PRIVATE)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Notify.schedule(this)
        render()
    }

    /** Quiet notifications: off, a ready-made window, or "Custom…" (the start hour, then the end hour). */
    private fun pickQuiet() {
        val from = Settings.quietFrom(this)
        val to = Settings.quietTo(this)
        val presets = QuietHours.PRESETS
        val items = listOf(getString(CoreR.string.quiet_off)) +
            presets.map { (f, t) -> getString(CoreR.string.time_range, hourLabel(f), hourLabel(t)) } +
            getString(CoreR.string.quiet_custom)
        val current = when {
            !QuietHours.enabled(from, to) -> 0
            else -> presets.indexOf(from to to).let { if (it >= 0) it + 1 else items.lastIndex }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(CoreR.string.quiet_hours))
            .setSingleChoiceItems(items.toTypedArray(), current) { d, i ->
                d.dismiss()
                when (i) {
                    0 -> setQuiet(0, 0)
                    items.lastIndex -> pickHour(CoreR.string.quiet_from, from) { f ->
                        pickHour(CoreR.string.quiet_to, to) { t -> setQuiet(f, t) }
                    }
                    else -> presets[i - 1].let { (f, t) -> setQuiet(f, t) }
                }
            }
            .show()
    }

    private fun setQuiet(from: Int, to: Int) {
        Settings.setQuiet(this, from, to)
        render()
    }

    private fun pickHour(@androidx.annotation.StringRes title: Int, current: Int, onPicked: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(getString(title))
            .setSingleChoiceItems((0..23).map { hourLabel(it) }.toTypedArray(), current) { d, i ->
                d.dismiss()
                onPicked(i)
            }
            .show()
    }

    // ---------------------------------------------------------------- background work

    /** The autostart page of the vendor's security app (Xiaomi), or else the app's own page in the system settings. */
    private fun openAutostart() {
        val xiaomi = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        if (!tryStart(xiaomi)) openAppDetails()
    }

    /** The list of apps with battery restrictions, or else the app's own page. */
    private fun openBattery() {
        if (!tryStart(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppDetails()
    }

    private fun openAppDetails() {
        tryStart(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun tryStart(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess

    // ---------------------------------------------------------------- rendering

    private fun hourLabel(h: Int) = "%02d:00".format(h)

    private fun render() {
        sourceRow.text = getString(CoreR.string.two_lines, getString(CoreR.string.source), Settings.source(this).host)
        queueRow.text = getString(CoreR.string.two_lines, getString(CoreR.string.queue), Settings.queue(this))
        val maybeOff = Settings.maybeIsOff(this)
        yellow.label.text = getString(if (maybeOff) CoreR.string.yellow_as_outage else CoreR.string.yellow_as_power)
        yellow.sw.isChecked = maybeOff

        ringsRow.text = getString(
            CoreR.string.two_lines, getString(CoreR.string.app_rings_title),
            getString(if (Settings.appRings(this) == 2) CoreR.string.tile_rings_two else CoreR.string.tile_rings_one)
        )
        labelsRow.text = getString(
            CoreR.string.two_lines, getString(CoreR.string.app_labels_title),
            getString(if (Settings.appInnerLabels(this)) CoreR.string.tile_labels_both else CoreR.string.tile_labels_outer)
        )

        val blocked = !Notify.enabled(this)
        for ((row, title, lead) in listOf(
            Triple(notifyOffRow, getString(CoreR.string.before_off), Settings.notifyOff(this)),
            Triple(notifyOnRow, getString(CoreR.string.before_on), Settings.notifyOn(this)),
        )) {
            val warn = blocked && lead > 0
            val label = Notify.leadLabel(this, lead)
            row.text = if (warn) getString(CoreR.string.three_lines, title, label, getString(CoreR.string.notifications_blocked))
            else getString(CoreR.string.two_lines, title, label)
            row.setTextColor(if (warn) Palette.AMBER else Palette.GREY)
        }
        for ((row, title, on) in listOf(
            Triple(notifyToday, getString(CoreR.string.notify_setting_today), Settings.notifyTodayChanged(this)),
            Triple(notifyTomorrow, getString(CoreR.string.notify_setting_tomorrow), Settings.notifyTomorrowNew(this)),
        )) {
            val warn = blocked && on
            row.label.text = if (warn) getString(CoreR.string.two_lines, title, getString(CoreR.string.notifications_blocked)) else title
            row.label.setTextColor(if (warn) Palette.AMBER else Palette.GREY)
            row.sw.isChecked = on
        }
        quietRow.text = getString(
            CoreR.string.two_lines, getString(CoreR.string.quiet_hours),
            if (!QuietHours.enabled(Settings.quietFrom(this), Settings.quietTo(this))) getString(CoreR.string.quiet_off)
            else getString(CoreR.string.time_range, hourLabel(Settings.quietFrom(this)), hourLabel(Settings.quietTo(this)))
        )
    }
}
