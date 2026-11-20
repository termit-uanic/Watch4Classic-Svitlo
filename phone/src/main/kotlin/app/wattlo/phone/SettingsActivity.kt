package app.wattlo.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.wattlo.Fetcher
import app.wattlo.Notify
import app.wattlo.Palette
import app.wattlo.QuietHours
import app.wattlo.Refresh
import app.wattlo.Settings
import app.wattlo.SettingsSync
import app.wattlo.WatchInfo
import app.wattlo.WatchState
import app.wattlo.Region
import app.wattlo.Source
import app.wattlo.core.R as CoreR
import kotlin.concurrent.thread

/** The phone's settings. They belong to this phone; the watch has its own (they will be synchronised later). */
class SettingsActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private lateinit var regionRow: TextView
    private lateinit var sourceRow: TextView
    private lateinit var queueRow: TextView
    private lateinit var extraQueuesRow: TextView
    private lateinit var queueLabelsRow: TextView
    private lateinit var yellow: SwitchRow
    private lateinit var ringsRow: TextView
    private lateinit var labelsRow: TextView
    private lateinit var tileRingsRow: TextView
    private lateinit var tileLabelsRow: TextView
    private lateinit var tileNext: SwitchRow
    private lateinit var wearRingsRow: TextView
    private lateinit var wearLabelsRow: TextView
    private lateinit var watchHint: TextView
    private lateinit var watchGroup: LinearLayout

    /** What the Data Layer says about the watch; the last answer is remembered so the screen does not flicker. */
    private var watchInfo: WatchInfo? = null
    private val uiPrefs by lazy { getSharedPreferences("phone_ui", MODE_PRIVATE) }
    private var watchReady: Boolean
        get() = uiPrefs.getBoolean("watchReady", false)
        set(v) { uiPrefs.edit().putBoolean("watchReady", v).apply() }
    private lateinit var notifyOffRow: TextView
    private lateinit var notifyOnRow: TextView
    private lateinit var notifyToday: SwitchRow
    private lateinit var notifyTomorrow: SwitchRow
    private lateinit var quietRow: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        regionRow = ui.groupRow(R.drawable.ic_tab_queues) { pickRegion() }
        sourceRow = ui.groupRow(R.drawable.ic_cloud) { pickSource() }
        queueRow = ui.groupRow(R.drawable.ic_tab_queues) { pickQueue() }
        extraQueuesRow = ui.groupRow(R.drawable.ic_add_circle) { pickExtraQueues() }
        queueLabelsRow = ui.groupRow(R.drawable.ic_text_size) { pickQueueLabels() }
        yellow = ui.switchRow(R.drawable.ic_warning) {
            Settings.setMaybeIsOff(this, !Settings.maybeIsOff(this))
            Fetcher.notifyAll(this)
            render()
        }
        ringsRow = ui.groupRow(R.drawable.ic_ring) { pickRings() }
        labelsRow = ui.groupRow(R.drawable.ic_text_size) { pickLabels() }
        tileRingsRow = ui.groupRow(R.drawable.ic_ring, watchOnly {
            pickOf(CoreR.string.tile_rings_title, rings(), Settings.tileRings(this) - 1) { Settings.setTileRings(this, it + 1) }
        })
        tileLabelsRow = ui.groupRow(R.drawable.ic_text_size, watchOnly {
            pickOf(CoreR.string.tile_labels_title, labels(), if (Settings.tileInnerLabels(this)) 1 else 0) {
                Settings.setTileInnerLabels(this, it == 1)
            }
        })
        tileNext = ui.switchRow(R.drawable.ic_tab_now, watchOnly {
            Settings.setTileShowNext(this, !Settings.tileShowNext(this))
            render()
        })
        wearRingsRow = ui.groupRow(R.drawable.ic_ring, watchOnly {
            pickOf(R.string.watch_app_rings_title, rings(), Settings.wearAppRings(this) - 1) { Settings.setWearAppRings(this, it + 1) }
        })
        wearLabelsRow = ui.groupRow(R.drawable.ic_text_size, watchOnly {
            pickOf(R.string.watch_app_labels_title, labels(), if (Settings.wearAppInnerLabels(this)) 1 else 0) {
                Settings.setWearAppInnerLabels(this, it == 1)
            }
        })
        watchHint = ui.label(13f, Palette.AMBER).apply { setPadding(ui.dp(6f).toInt(), 0, ui.dp(6f).toInt(), ui.dp(8f).toInt()) }
        notifyOffRow = ui.groupRow(R.drawable.ic_bell) { onLeadRow(off = true) }
        notifyOnRow = ui.groupRow(R.drawable.ic_bell) { onLeadRow(off = false) }
        notifyToday = ui.switchRow(R.drawable.ic_bell) {
            val on = !Settings.notifyTodayChanged(this)
            Settings.setNotifyTodayChanged(this, on)
            if (on) askNotificationPermission()
            render()
        }
        notifyTomorrow = ui.switchRow(R.drawable.ic_bell) {
            val on = !Settings.notifyTomorrowNew(this)
            Settings.setNotifyTomorrowNew(this, on)
            if (on) askNotificationPermission()
            render()
        }
        quietRow = ui.groupRow(R.drawable.ic_clock) { pickQuiet() }

        val pad = ui.dp(16f).toInt()
        val gap = 8f
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, ui.dp(100f).toInt())
            addView(ui.label(26f, Palette.WHITE, bold = true).apply { text = getString(CoreR.string.settings) })

            addView(ui.section(getString(R.string.data_title)))
            addView(ui.group(regionRow, sourceRow, queueRow, extraQueuesRow, queueLabelsRow, yellow.card), ui.lp())

            addView(ui.section(getString(R.string.display_title)))
            addView(ui.group(ringsRow, labelsRow), ui.lp())

            addView(ui.section(getString(R.string.watch_display_title)))
            addView(watchHint)
            watchGroup = ui.group(tileRingsRow, tileLabelsRow, tileNext.card, wearRingsRow, wearLabelsRow)
            addView(watchGroup, ui.lp())

            addView(ui.section(getString(CoreR.string.notifications)))
            addView(ui.group(notifyOffRow, notifyOnRow, notifyToday.card, notifyTomorrow.card, quietRow), ui.lp())

            addView(ui.section(getString(R.string.background_title)))
            addView(ui.label(13f, Palette.LABEL).apply {
                text = getString(R.string.background_hint)
                setPadding(ui.dp(6f).toInt(), 0, ui.dp(6f).toInt(), 0)
            })
            addView(
                ui.group(
                    ui.groupRow(R.drawable.ic_play) { openAutostart() }.apply { text = getString(R.string.autostart_button) },
                    ui.groupRow(R.drawable.ic_battery) { openBattery() }.apply { text = getString(R.string.battery_button) },
                ),
                ui.lp(10f)
            )

            addView(ui.section(getString(R.string.about_title)))
            addView(
                ui.group(
                    ui.infoRow(R.drawable.ic_info).apply {
                        text = getString(CoreR.string.two_lines, getString(CoreR.string.app_name), getString(R.string.about_version, versionName()))
                    },
                    ui.groupRow(R.drawable.ic_cloud) { showSources() }.apply {
                        text = getString(CoreR.string.two_lines, getString(R.string.sources_title), getString(R.string.sources_hint))
                    },
                    ui.groupRow(R.drawable.ic_description) { startActivity(Intent(this@SettingsActivity, LicensesActivity::class.java)) }.apply {
                        text = getString(CoreR.string.two_lines, getString(R.string.licenses_title), getString(R.string.licenses_hint))
                    },
                    ui.groupRow(R.drawable.ic_tab_now) { startActivity(Intent(this@SettingsActivity, SetupActivity::class.java)) }.apply {
                        text = getString(CoreR.string.two_lines, getString(R.string.setup_rerun_title), getString(R.string.setup_rerun_hint))
                    },
                ),
                ui.lp()
            )
        }
        val width = minOf(resources.displayMetrics.widthPixels, ui.dp(560f).toInt())
        setContentView(withTabs(ScrollView(this).apply {
            addView(column, FrameLayout.LayoutParams(width, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }, Tab.SETTINGS))
    }

    override fun onPause() {
        super.onPause()
        Widgets.updateAll(this) // a ring setting may have changed
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = openTab(Tab.NOW)

    override fun onResume() {
        super.onResume()
        SettingsSync.watchInfo(this) { info ->
            if (info != null) {
                watchInfo = info
                watchReady = info.state == WatchState.READY
                render()
            }
        }
        render() // the permission or the battery mode may have changed in the system settings
    }

    // ---------------------------------------------------------------- pickers

    /** Another region has its own queues and sites: the source and the main queue start again from the defaults. */
    private fun pickRegion() {
        val all = Region.entries
        AlertDialog.Builder(this)
            .setTitle(getString(CoreR.string.region))
            .setSingleChoiceItems(all.map { getString(it.nameRes) }.toTypedArray(), all.indexOf(Settings.region(this))) { d, i ->
                d.dismiss()
                applyDataSetting { Settings.setRegion(this, all[i]) }
            }
            .show()
    }

    private fun pickSource() {
        val all = Settings.region(this).sources
        AlertDialog.Builder(this)
            .setTitle(getString(CoreR.string.source))
            .setSingleChoiceItems(all.map { it.host }.toTypedArray(), all.indexOf(Settings.source(this))) { d, i ->
                d.dismiss()
                applyDataSetting { Settings.set(this, source = all[i]) }
            }
            .show()
    }

    private fun pickQueue() {
        val all = Settings.queues(this)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.queue_main_title))
            .setSingleChoiceItems(
                all.map { getString(CoreR.string.queue_name, it) }.toTypedArray(), all.indexOf(Settings.queue(this))
            ) { d, i ->
                d.dismiss()
                applyDataSetting { Settings.set(this, queue = all[i]) }
            }
            .show()
    }

    /** The extra queues the main screen can switch to; only the main one goes to the watch. */
    private fun pickExtraQueues() {
        val main = Settings.queue(this)
        val all = Settings.queues(this).filter { it != main }
        val chosen = Settings.extraQueues(this).toMutableSet()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.queues_extra_title))
            .setMultiChoiceItems(
                all.map { getString(CoreR.string.queue_name, it) }.toTypedArray(),
                BooleanArray(all.size) { all[it] in chosen }
            ) { _, i, on -> if (on) chosen += all[i] else chosen -= all[i] }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Settings.setExtraQueues(this, chosen)
                render()
                Refresh.now(this) // fetch the schedules of the new queues
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Short names of the extra queues ("parents", "sister"): shown where the main queue says "main". */
    private fun pickQueueLabels() {
        val extras = Settings.extraQueues(this)
        if (extras.isEmpty()) { pickExtraQueues(); return } // nothing to name yet
        val pad = ui.dp(20f).toInt()
        val fields = extras.associateWith { q ->
            android.widget.EditText(this).apply {
                setText(Settings.queueLabel(this@SettingsActivity, q))
                hint = getString(R.string.queue_labels_hint)
                setHintTextColor(Palette.LABEL) // clearly dimmer than the typed text
                setSingleLine()
                filters = arrayOf(android.text.InputFilter.LengthFilter(Settings.MAX_LABEL))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, ui.dp(8f).toInt(), pad, 0)
            for ((q, field) in fields) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { text = q; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) },
                    LinearLayout.LayoutParams(ui.dp(44f).toInt(), WRAP_CONTENT))
                addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.queue_labels_title)
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                for ((q, field) in fields) Settings.setQueueLabel(this, q, field.text.toString())
                render()
                Widgets.updateAll(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Where the schedules come from, with a link to each site (bezsvitla.com.ua asks for an active link). */
    private fun showSources() {
        val sites = listOf(
            "poe.pl.ua" to "https://www.poe.pl.ua/disconnection/power-outages/",
            "bezsvitla.com.ua" to "https://bezsvitla.com.ua/",
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.sources_title)
            .setItems(sites.map { it.first }.toTypedArray()) { _, i -> tryStart(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(sites[i].second))) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A new source or queue: the cached schedule is not valid any more, fetch again. */
    private fun applyDataSetting(change: () -> Unit) {
        val before = Triple(Settings.region(this), Settings.source(this), Settings.queue(this))
        change()
        render()
        if (before == Triple(Settings.region(this), Settings.source(this), Settings.queue(this))) return
        Fetcher.notifyAll(this)
        thread { Fetcher.refreshAndNotify(applicationContext) }
    }

    /** The settings of the watch need a watch with the app; otherwise a tap explains what to do. */
    private fun watchOnly(action: () -> Unit): () -> Unit = { if (watchReady) action() else showWatchDialog() }

    private fun showWatchDialog() {
        val info = watchInfo
        val b = AlertDialog.Builder(this)
        if (info?.state == WatchState.NO_APP) {
            b.setTitle(R.string.watch_noapp_title).setMessage(R.string.watch_noapp_message)
                .setPositiveButton(R.string.watch_install) { _, _ -> installOnWatch(info.nodeIds) }
                .setNegativeButton(android.R.string.cancel, null)
        } else {
            b.setTitle(R.string.watch_none_title).setMessage(R.string.watch_none_message)
            companionIntent()?.let { i -> b.setPositiveButton(R.string.watch_open_companion) { _, _ -> tryStart(i) } }
            b.setNegativeButton(android.R.string.cancel, null)
        }
        b.show()
    }

    /** The app that pairs and manages the watch (Wear OS by Google, or Galaxy Wearable on Samsung watches). */
    private fun companionIntent(): Intent? =
        listOf("com.google.android.wearable.app", "com.samsung.android.app.watchmanager")
            .firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }

    /** Opens this app's page in Google Play on the watch (the watch app has the same package name). */
    private fun installOnWatch(nodeIds: List<String>) {
        val node = nodeIds.firstOrNull()
        val ok = node != null && runCatching {
            val page = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.parse("market://details?id=$packageName"))
            androidx.wear.remote.interactions.RemoteActivityHelper(this).startRemoteActivity(page, node)
        }.isSuccess
        android.widget.Toast.makeText(
            this, if (ok) R.string.watch_install_started else R.string.watch_install_failed, android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun rings() = arrayOf(getString(CoreR.string.tile_rings_one), getString(CoreR.string.tile_rings_two))
    private fun labels() = arrayOf(getString(CoreR.string.tile_labels_outer), getString(CoreR.string.tile_labels_both))

    /** A single-choice dialog; [onPick] gets the index, then the screen is redrawn. */
    private fun pickOf(title: Int, items: Array<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(getString(title))
            .setSingleChoiceItems(items, current) { d, i ->
                d.dismiss()
                onPick(i)
                render()
            }
            .show()
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


    // ---------------------------------------------------------------- rendering

    private fun Ui.two(title: Int, value: Int) = getString(CoreR.string.two_lines, getString(title), getString(value))

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    private fun hourLabel(h: Int) = "%02d:00".format(h)

    private fun render() {
        regionRow.text = getString(CoreR.string.two_lines, getString(CoreR.string.region), getString(Settings.region(this).nameRes))
        sourceRow.text = getString(CoreR.string.two_lines, getString(CoreR.string.source), Settings.source(this).host)
        queueRow.text = getString(CoreR.string.two_lines, getString(R.string.queue_main_title), Settings.queue(this))
        extraQueuesRow.text = getString(
            CoreR.string.two_lines, getString(R.string.queues_extra_title),
            Settings.extraQueues(this).joinToString(", ").ifEmpty { getString(R.string.queues_extra_none) }
        )
        queueLabelsRow.text = getString(
            CoreR.string.two_lines, getString(R.string.queue_labels_title),
            Settings.extraQueues(this).filter { Settings.queueLabel(this, it).isNotEmpty() }
                .joinToString(", ") { "$it — " + Settings.queueLabel(this, it) }
                .ifEmpty { getString(if (Settings.extraQueues(this).isEmpty()) R.string.queue_labels_add_first else R.string.queue_labels_none) }
        )
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

        tileRingsRow.text = ui.two(CoreR.string.tile_rings_title, if (Settings.tileRings(this) == 2) CoreR.string.tile_rings_two else CoreR.string.tile_rings_one)
        tileLabelsRow.text = ui.two(CoreR.string.tile_labels_title, if (Settings.tileInnerLabels(this)) CoreR.string.tile_labels_both else CoreR.string.tile_labels_outer)
        tileNext.label.text = getString(CoreR.string.tile_next_title)
        tileNext.sw.isChecked = Settings.tileShowNext(this)
        wearRingsRow.text = ui.two(R.string.watch_app_rings_title, if (Settings.wearAppRings(this) == 2) CoreR.string.tile_rings_two else CoreR.string.tile_rings_one)
        wearLabelsRow.text = ui.two(R.string.watch_app_labels_title, if (Settings.wearAppInnerLabels(this)) CoreR.string.tile_labels_both else CoreR.string.tile_labels_outer)

        val ready = watchReady
        watchGroup.alpha = if (ready) 1f else 0.45f
        watchHint.visibility = if (ready) View.GONE else View.VISIBLE
        watchHint.text = getString(if (watchInfo?.state == WatchState.NO_APP) R.string.watch_hint_no_app else R.string.watch_hint_none)

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
