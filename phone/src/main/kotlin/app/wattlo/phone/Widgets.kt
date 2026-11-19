package app.wattlo.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import app.wattlo.Fmt
import app.wattlo.Palette
import app.wattlo.Refresh
import app.wattlo.Schedule
import app.wattlo.Settings
import app.wattlo.Store
import app.wattlo.core.R as CoreR
import java.time.Duration
import java.time.ZonedDateTime

/** Three home-screen widgets: a compact status, a status with the day ring, and today with tomorrow. */
class SmallWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = Widgets.update(c, m, this::class.java, ids)
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) =
        Widgets.update(c, m, this::class.java, intArrayOf(id))
    override fun onEnabled(c: Context) = Widgets.updateAll(c)
    override fun onDisabled(c: Context) = Widgets.updateAll(c)
    override fun onDeleted(c: Context, ids: IntArray) = Widgets.forget(c, ids)
}

class MediumWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = Widgets.update(c, m, this::class.java, ids)
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) =
        Widgets.update(c, m, this::class.java, intArrayOf(id))
    override fun onEnabled(c: Context) = Widgets.updateAll(c)
    override fun onDisabled(c: Context) = Widgets.updateAll(c)
    override fun onDeleted(c: Context, ids: IntArray) = Widgets.forget(c, ids)
}

class LargeWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = Widgets.update(c, m, this::class.java, ids)
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) =
        Widgets.update(c, m, this::class.java, intArrayOf(id))
    override fun onEnabled(c: Context) = Widgets.updateAll(c)
    override fun onDisabled(c: Context) = Widgets.updateAll(c)
    override fun onDeleted(c: Context, ids: IntArray) = Widgets.forget(c, ids)
}

/** The minute tick: redraws the widgets so that the countdown is always right, and plans the next tick. */
class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Widgets.updateAll(c)
        Refresh.ifStale(c) // a widget is for fresh information: do not wait for the periodic job if it is late
    }
}

object Widgets {
    const val BG_DARK = "dark"
    const val BG_STATE = "state"
    const val BG_CLEAR = "clear"

    private fun prefs(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    /** The extra queue a widget shows; null is the main queue (also when the chosen queue is no longer an extra one). */
    fun queueOf(c: Context, id: Int): String? =
        prefs(c).getString("q_$id", null)?.takeIf { it in Settings.extraQueues(c) }

    fun bgOf(c: Context, id: Int): String = prefs(c).getString("bg_$id", null) ?: BG_DARK

    /** How opaque the background is, 0..100 percent (100 = as drawn); it does not touch the text. */
    fun opacityOf(c: Context, id: Int): Int = prefs(c).getInt("a_$id", 100).coerceIn(0, 100)

    const val AS_APP = "app"

    /** The ring of a widget: the number of rings and the hour numbers, unless the widget has its own choice. */
    class RingConfig(val rings: Int, val labels: RingLabels)

    /** Stored choices: [ringsSetting] is "app", "1" or "2"; [labelsSetting] is "app", "both", "outer", "current" or "none". */
    fun ringsSettingOf(c: Context, id: Int): String = prefs(c).getString("r_$id", null) ?: AS_APP
    fun labelsSettingOf(c: Context, id: Int): String = prefs(c).getString("l_$id", null) ?: AS_APP

    fun ringConfig(c: Context, id: Int) = RingConfig(
        resolveRings(ringsSettingOf(c, id), Settings.appRings(c)),
        resolveLabels(labelsSettingOf(c, id), Settings.appInnerLabels(c))
    )

    /** "As in the app" takes the phone's own setting. */
    internal fun resolveRings(setting: String, appRings: Int): Int = when (setting) { "1" -> 1; "2" -> 2; else -> appRings }

    internal fun resolveLabels(setting: String, appInnerLabels: Boolean): RingLabels = when (setting) {
        "both" -> RingLabels.BOTH
        "outer" -> RingLabels.OUTER
        "current" -> RingLabels.CURRENT
        "none" -> RingLabels.NONE
        else -> if (appInnerLabels) RingLabels.BOTH else RingLabels.OUTER
    }

    fun setConfig(
        c: Context, id: Int, queue: String?, bg: String, opacity: Int,
        ringsSetting: String = AS_APP, labelsSetting: String = AS_APP,
    ) {
        prefs(c).edit().putString("q_$id", queue).putString("bg_$id", bg).putInt("a_$id", opacity.coerceIn(0, 100))
            .putString("r_$id", ringsSetting).putString("l_$id", labelsSetting).apply()
    }

    /** Widgets that were removed from the home screen: their settings are not needed any more. */
    fun forget(c: Context, ids: IntArray) {
        val e = prefs(c).edit()
        for (id in ids) e.remove("q_$id").remove("bg_$id").remove("a_$id").remove("r_$id").remove("l_$id")
        e.apply()
    }

    private val providers = listOf(SmallWidget::class.java, MediumWidget::class.java, LargeWidget::class.java)

    /** Redraws every widget of the app; plans the next minute tick while there is one, cancels it otherwise. */
    fun updateAll(c: Context) {
        val app = c.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        var any = false
        for (p in providers) {
            val ids = mgr.getAppWidgetIds(ComponentName(app, p))
            if (ids.isEmpty()) continue
            any = true
            update(app, mgr, p, ids)
        }
        if (any) scheduleTick(app) else cancelTick(app)
    }

    fun update(c: Context, mgr: AppWidgetManager, provider: Class<*>, ids: IntArray) {
        val now = ZonedDateTime.now(Schedule.ZONE)
        val snaps = HashMap<String?, Snapshot>() // widgets of the same queue share one reading of the data
        for (id in ids) {
            val snap = snaps.getOrPut(queueOf(c, id)) { Snapshot(c, now, queueOf(c, id)) }
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            val ring = ringConfig(c, id)
            val views = when (provider) {
                SmallWidget::class.java -> small(c, snap, w, h)
                MediumWidget::class.java -> medium(c, snap, w, ring)
                else -> large(c, snap, h, ring)
            }
            // the background is a layer of its own, so that its opacity can change without the text fading
            views.setImageViewResource(R.id.bg, background(bgOf(c, id), snap))
            views.setInt(R.id.bg, "setImageAlpha", opacityOf(c, id) * 255 / 100)
            views.setOnClickPendingIntent(R.id.root, open(c))
            mgr.updateAppWidget(id, views)
        }
    }

    // ---------------------------------------------------------------- what the widgets say

    /** The state of the main queue at one moment and the words for it. */
    private class Snapshot(val c: Context, val now: ZonedDateTime, val q: String?) {
        val days = Store.load(c, Settings.maybeIsOff(c), q)
        val maybe = Store.loadMaybe(c, q)
        val st = Schedule.status(days, now)
        val switching = st != null && Schedule.at(maybe, now) == true
        val today = now.toLocalDate()
        private val midnight = today.plusDays(1).atStartOfDay(Schedule.ZONE)

        val color = when { st == null -> Palette.LABEL; switching -> Palette.AMBER; st.off -> Palette.RED; else -> Palette.GREEN }

        val stateWord: String = c.getString(
            when { st == null -> CoreR.string.no_data; switching -> R.string.widget_state_switching; st.off -> R.string.widget_state_off; else -> R.string.widget_state_on }
        )

        val queue: String = c.getString(CoreR.string.queue_upper, q ?: Settings.queue(c))

        /** "1h 23m", rounded up like everywhere else; "≥ 1h 23m" when the data ends before the change. */
        val countdown: String = st?.let {
            val left = Fmt.duration(c, it.remaining(now).plusSeconds(59))
            if (it.changeIsMinimum) c.getString(CoreR.string.at_least, left) else left
        }.orEmpty()

        /** The current phase within today: "20:00–24:00". */
        val range: String = st?.let {
            val from = if (it.since.isAfter(today.atStartOfDay(Schedule.ZONE))) it.since else today.atStartOfDay(Schedule.ZONE)
            val to = if (it.changeAt.isBefore(midnight)) it.changeAt else midnight
            c.getString(CoreR.string.time_range, clock(from, now), clock(to, now))
        }.orEmpty()

        /** What comes next today, or that nothing more changes today. */
        val next: String = st?.let {
            val changesToday = !it.changeIsMinimum && it.changeAt.isBefore(midnight)
            when {
                changesToday -> {
                    val end = it.nextEnd?.let { e -> if (e.isBefore(midnight)) e else midnight }
                    val span = if (end == null) clock(it.changeAt, now)
                    else c.getString(CoreR.string.time_range, clock(it.changeAt, now), clock(end, now))
                    c.getString(if (it.off) R.string.widget_then_on else R.string.widget_then_off, span)
                }
                // the state really changes at midnight (tomorrow's schedule is known)
                !it.changeIsMinimum && it.changeAt == midnight ->
                    c.getString(if (it.off) CoreR.string.power_on_at else CoreR.string.power_off_at, "24:00")
                else -> c.getString(R.string.widget_no_more_changes)
            }
        }.orEmpty()

        fun ring(date: java.time.LocalDate, isToday: Boolean, rings: Int, labels: RingLabels): android.graphics.Bitmap =
            WidgetRing.render(
                days[date], maybe[date], if (isToday) Schedule.slotOf(now) else -1,
                if (isToday) now.hour * 60 + now.minute else -1, rings, Settings.maybeIsOff(c), labels
            )
    }

    /** The background of a widget: dark, tinted by the state, or none at all. */
    private fun background(bg: String, s: Snapshot): Int = when (bg) {
        BG_CLEAR -> R.drawable.widget_bg_clear
        BG_STATE -> when (s.color) {
            Palette.GREEN -> R.drawable.widget_bg_green
            Palette.RED -> R.drawable.widget_bg_red
            Palette.AMBER -> R.drawable.widget_bg_amber
            else -> R.drawable.widget_bg
        }
        else -> R.drawable.widget_bg
    }

    // ---------------------------------------------------------------- the three layouts

    private fun small(c: Context, s: Snapshot, widthDp: Int, heightDp: Int): RemoteViews {
        // one launcher row is about 105 dp high on a phone, two rows about 235 dp
        val tall = heightDp >= 160
        val v = RemoteViews(c.packageName, if (tall) R.layout.widget_small_tall else R.layout.widget_small)
        v.setInt(R.id.dot, "setColorFilter", s.color)
        v.setTextViewText(R.id.state, s.stateWord)
        v.setTextViewText(R.id.range, s.range)
        v.setTextViewText(R.id.line2, s.next)
        v.setTextViewText(R.id.countdown, s.countdown)
        // a wide compact widget has room for the countdown on the right
        if (!tall) v.setViewVisibility(R.id.countdown, if (widthDp >= 200 && s.st != null) android.view.View.VISIBLE else android.view.View.GONE)
        return v
    }

    private fun medium(c: Context, s: Snapshot, widthDp: Int, ring: RingConfig): RemoteViews {
        val wide = widthDp >= 250
        val v = RemoteViews(c.packageName, if (wide) R.layout.widget_medium else R.layout.widget_medium_sq)
        // a 2x2 ring is too small for all the numbers: it keeps only the current hour
        val labels = if (!wide && (ring.labels == RingLabels.BOTH || ring.labels == RingLabels.OUTER)) RingLabels.CURRENT else ring.labels
        v.setImageViewBitmap(R.id.ring, s.ring(s.today, true, ring.rings, labels))
        v.setTextViewText(R.id.state, s.stateWord)
        v.setTextColor(R.id.state, s.color)
        v.setTextViewText(R.id.countdown, s.countdown)
        if (wide) {
            v.setTextViewText(R.id.queue, s.queue)
            v.setTextViewText(R.id.range, s.range)
            v.setTextViewText(R.id.line2, s.next)
        }
        return v
    }

    private fun large(c: Context, s: Snapshot, heightDp: Int, ring: RingConfig): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_large)
        v.setTextViewText(R.id.queue, s.queue)
        v.setTextViewText(R.id.state, s.stateWord)
        v.setTextColor(R.id.state, s.color)
        v.setTextViewText(R.id.countdown, s.countdown)
        val tomorrow = s.today.plusDays(1)
        // the ring takes the height the lists leave (it is scaled to fit), so it only needs a little room; a 4x4 widget
        // on a launcher with low cells is about 330 dp high, a two-row one about 235 dp and shows the lists only
        val rings = heightDp >= 260
        for ((ringView, date, isToday) in listOf(Triple(R.id.ringToday, s.today, true), Triple(R.id.ringTomorrow, tomorrow, false))) {
            if (rings) v.setImageViewBitmap(ringView, s.ring(date, isToday, ring.rings, ring.labels))
            v.setViewVisibility(ringView, if (rings) android.view.View.VISIBLE else android.view.View.GONE)
        }
        day(c, v, s, s.today, true, R.id.titleToday, R.id.listToday)
        day(c, v, s, tomorrow, false, R.id.titleTomorrow, R.id.listTomorrow)
        return v
    }

    /** The title of a day and the list of its outages. */
    private fun day(c: Context, v: RemoteViews, s: Snapshot, date: java.time.LocalDate, isToday: Boolean, title: Int, list: Int) {
        val flags = s.days[date]
        val cur = if (isToday) Schedule.slotOf(s.now) else -1
        val name = c.getString(if (isToday) CoreR.string.today else CoreR.string.tomorrow)
        v.setTextViewText(title, if (flags == null || flags.none { it }) name else "$name  " + c.getString(CoreR.string.minus, Fmt.duration(c, Schedule.offDuration(flags))))
        v.removeAllViews(list)
        if (flags == null) {
            v.addView(list, row(c, Palette.LABEL, c.getString(CoreR.string.no_schedule_yet), "", false))
            return
        }
        val ranges = Schedule.offRanges(flags)
        if (ranges.isEmpty()) v.addView(list, row(c, Palette.GREEN, c.getString(CoreR.string.power_all_day), "", false))
        for ((a, b) in ranges) {
            val past = cur >= 0 && b <= cur
            val active = cur in a until b
            v.addView(list, row(
                c, if (past) Palette.RED_DIM else Palette.RED,
                c.getString(CoreR.string.time_range, Schedule.slotTime(a), Schedule.slotTime(b)),
                if (active) c.getString(CoreR.string.now) else Fmt.duration(c, Duration.ofMinutes((b - a) * 30L)),
                active, past
            ))
        }
    }

    private fun row(c: Context, dot: Int, range: String, duration: String, active: Boolean, past: Boolean = false): RemoteViews {
        val r = RemoteViews(c.packageName, R.layout.widget_row)
        r.setInt(R.id.dot, "setColorFilter", dot)
        r.setTextViewText(R.id.range, range)
        r.setTextColor(R.id.range, if (active) Palette.WHITE else if (past) Palette.LABEL else Palette.GREY)
        r.setTextViewText(R.id.duration, duration)
        r.setTextColor(R.id.duration, if (active) Palette.RED else Palette.LABEL)
        return r
    }

    private fun open(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // ---------------------------------------------------------------- the minute tick

    private fun tickIntent(c: Context) = PendingIntent.getBroadcast(
        c, 1, Intent(c, WidgetTickReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * An alarm at the start of the next minute. It is not a waking alarm: while the screen is off nobody looks at
     * the widget, and the first thing after waking up is the overdue tick, so the widget is right when it is seen.
     */
    private fun scheduleTick(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val at = (System.currentTimeMillis() / 60_000 + 1) * 60_000 + 300
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC, at, tickIntent(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC, at, tickIntent(c))
    }

    private fun cancelTick(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(tickIntent(c))
    }
}
