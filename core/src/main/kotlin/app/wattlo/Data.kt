package app.wattlo

import app.wattlo.core.R
import android.app.job.JobScheduler
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Where the schedule comes from. Slot states: 0 = power on, 1 = off, 2 = switching (may be off). */
enum class Source(val host: String) {
    POE("poe.pl.ua"),
    BEZSVITLA("bezsvitla.com.ua"),
    ENERGY_UA("energy-ua.info");

    /**
     * Dates a [load] call asks the source about. A date that is requested but missing from the result
     * has no (or no longer has a) published schedule. [hour] is the current hour in [Schedule.ZONE].
     * POE needs a separate request per day, so tomorrow can be skipped with [withTomorrow]
     * (the other sources return both days in one response).
     */
    fun requested(today: LocalDate, hour: Int, withTomorrow: Boolean = true): List<LocalDate> =
        // POE publishes tomorrow's schedule in the evening, so it is not requested before noon
        if (this == POE && (hour < 12 || !withTomorrow)) listOf(today) else listOf(today, today.plusDays(1))

    fun load(
        queue: String, today: LocalDate, hour: Int = java.time.LocalTime.now(Schedule.ZONE).hour,
        withTomorrow: Boolean = true,
    ): Map<LocalDate, IntArray> = when (this) {
        POE -> {
            // newgpv-info.php returns an HTML table of all queues for the date; empty means not published yet
            val out = LinkedHashMap<LocalDate, IntArray>()
            for (d in requested(today, hour, withTomorrow)) {
                val body = "seldate=" + java.net.URLEncoder.encode(
                    """{"date_in":"${d.format(POE_DATE)}"}""", "UTF-8"
                )
                val html = Http.request("https://www.poe.pl.ua/customs/newgpv-info.php", body)
                Schedule.parsePoe(html, queue)?.let { out[d] = it }
            }
            out
        }
        BEZSVITLA -> Schedule.parseBezsvitla(Http.request("https://bezsvitla.com.ua/poltavska-oblast"), today, queue)
            .mapValues { bits(it.value) }
        ENERGY_UA -> Schedule.parseEnergyUa(Http.request("https://energy-ua.info/cherga/${queue.replace('.', '-')}"), today)
            .mapValues { bits(it.value) }
    }

    private companion object {
        val POE_DATE: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")
        fun bits(f: BooleanArray) = IntArray(f.size) { if (f[it]) 1 else 0 }
    }
}

/** A fetch failure with a localized message: resolved with a Context where the error is stored. */
class FetchError(@androidx.annotation.StringRes val res: Int, vararg val args: Any) : Exception()

object Http {
    /** GET, or a form-urlencoded POST when [form] is given. */
    fun request(url: String, form: String? = null): String {
        val conn = java.net.URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "LightWatch/1.0 (personal Wear OS tile)")
            conn.setRequestProperty("Accept-Language", "uk")
            if (form != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code == 403 || code == 503) throw FetchError(R.string.err_blocked, code)
            if (code != 200) error("HTTP $code")
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if ("Just a moment" in html) throw FetchError(R.string.err_browser_check)
            return html
        } finally {
            conn.disconnect()
        }
    }
}

/** Settings: source + queue, display and notification options. */
object Settings {
    /** Set by the watch app: its display options are the ones the phone sets for the watch (see [Synced]). */
    @Volatile var isWatch = false

    val QUEUES = (1..6).flatMap { q -> (1..2).map { "$q.$it" } }

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun source(c: Context): Source =
        runCatching { Source.valueOf(p(c).getString("source", null)!!) }.getOrDefault(Source.POE)

    fun queue(c: Context): String = p(c).getString("queue", null)?.takeIf { it in QUEUES } ?: "3.2"

    /** Whether "switching" (yellow) slots count as no power in the countdown (tile and complication). Defaults to true. */
    fun maybeIsOff(c: Context): Boolean = p(c).getBoolean("maybeOff", true)

    fun setMaybeIsOff(c: Context, v: Boolean) { p(c).edit().putBoolean("maybeOff", v).apply(); touch(c) }

    /** How many minutes before power goes off / on to notify (0 = don't notify). */
    fun notifyOff(c: Context): Int = p(c).getInt("notifyOff", 0)
    fun notifyOn(c: Context): Int = p(c).getInt("notifyOn", 0)
    fun setNotify(c: Context, off: Int = notifyOff(c), on: Int = notifyOn(c)) {
        p(c).edit().putInt("notifyOff", off).putInt("notifyOn", on).apply()
        touch(c)
    }

    /**
     * Quiet hours: from this hour (0..23) to that hour, exclusive, possibly across midnight. Notifications
     * that arrive then are posted silently. Equal hours mean off, which is the default.
     */
    fun quietFrom(c: Context): Int = p(c).getInt("quietFrom", 0)
    fun quietTo(c: Context): Int = p(c).getInt("quietTo", 0)
    fun setQuiet(c: Context, from: Int, to: Int) { p(c).edit().putInt("quietFrom", from).putInt("quietTo", to).apply(); touch(c) }

    /** How the day is drawn on the tile: 1 = one ring of 24 hours, 2 = two rings of 12 hours. */
    fun tileRings(c: Context): Int = if (p(c).getInt("tileRings", 1) == 2) 2 else 1
    fun setTileRings(c: Context, rings: Int) { p(c).edit().putInt("tileRings", if (rings == 2) 2 else 1).apply(); touch(c) }

    /** With two rings on the tile: also label the hours 12-24 inside the inner ring (default), or only 0-12 outside. */
    fun tileInnerLabels(c: Context): Boolean = p(c).getBoolean("tileInnerLabels", true)
    fun setTileInnerLabels(c: Context, v: Boolean) { p(c).edit().putBoolean("tileInnerLabels", v).apply(); touch(c) }

    /** Show "then 2h without power" under the time of the next change on the tile. On by default. */
    fun tileShowNext(c: Context): Boolean = p(c).getBoolean("tileShowNext", true)
    fun setTileShowNext(c: Context, v: Boolean) { p(c).edit().putBoolean("tileShowNext", v).apply(); touch(c) }

    /** The same two settings for the day dials inside the app (kept apart from the tile's). */
    /** The number of rings until the user picks one: the watch starts with 1, the phone app sets 2 at start. */
    @Volatile var appRingsDefault = 1
    fun appRings(c: Context): Int = if (p(c).getInt("appRings", appRingsDefault) == 2) 2 else 1
    fun setAppRings(c: Context, rings: Int) { p(c).edit().putInt("appRings", if (rings == 2) 2 else 1).apply(); if (isWatch) touch(c) }
    fun appInnerLabels(c: Context): Boolean = p(c).getBoolean("appInnerLabels", true)
    fun setAppInnerLabels(c: Context, v: Boolean) { p(c).edit().putBoolean("appInnerLabels", v).apply(); if (isWatch) touch(c) }

    /**
     * The app screens of the watch as the phone sets them. On the watch they are its own [appRings] and
     * [appInnerLabels]; on the phone, which has its own app settings, they are separate copies.
     */
    fun wearAppRings(c: Context): Int =
        if (isWatch) appRings(c) else if (p(c).getInt("wearAppRings", 1) == 2) 2 else 1
    fun setWearAppRings(c: Context, rings: Int) {
        if (isWatch) return setAppRings(c, rings)
        p(c).edit().putInt("wearAppRings", if (rings == 2) 2 else 1).apply(); touch(c)
    }
    fun wearAppInnerLabels(c: Context): Boolean =
        if (isWatch) appInnerLabels(c) else p(c).getBoolean("wearAppInnerLabels", true)
    fun setWearAppInnerLabels(c: Context, v: Boolean) {
        if (isWatch) return setAppInnerLabels(c, v)
        p(c).edit().putBoolean("wearAppInnerLabels", v).apply(); touch(c)
    }

    /**
     * The watch tells the phone its display options once, so the phone's settings screen starts with the watch's
     * real values instead of the defaults (and the phone's first change does not reset them).
     */
    fun publishOnce(c: Context) {
        if (p(c).getBoolean("published", false)) return
        p(c).edit().putBoolean("published", true).apply()
        touch(c)
    }

    /** Notify when the schedule for today changed. Off by default. */
    fun notifyTodayChanged(c: Context): Boolean = p(c).getBoolean("notifyTodayChanged", false)
    fun setNotifyTodayChanged(c: Context, v: Boolean) { p(c).edit().putBoolean("notifyTodayChanged", v).apply(); touch(c) }

    /** Notify when the schedule for tomorrow appeared. Off by default. */
    fun notifyTomorrowNew(c: Context): Boolean = p(c).getBoolean("notifyTomorrowNew", false)
    fun setNotifyTomorrowNew(c: Context, v: Boolean) { p(c).edit().putBoolean("notifyTomorrowNew", v).apply(); touch(c) }

    /** Changing the source/queue clears the cache: the old schedule no longer applies. */
    fun set(c: Context, source: Source = source(c), queue: String = queue(c)) {
        if (source == source(c) && queue == queue(c)) return
        val sourceChanged = source != source(c)
        p(c).edit().putString("source", source.name).putString("queue", queue).apply()
        // the new main queue is no longer an extra one
        if (queue in extraQueues(c)) writeExtras(c, extraQueues(c) - queue)
        if (sourceChanged) Store.clearAll(c) else { Store.clear(c); Store.clear(c, queue) }
        touch(c)
    }

    /**
     * Extra queues, for the phone app only: it can show their state next to the main queue [queue]. They are not
     * synchronised, and the watch, the widgets and the reminders only ever use the main queue.
     */
    fun extraQueues(c: Context): List<String> =
        (p(c).getString("extraQueues", "") ?: "").split(",").filter { it in QUEUES }.distinct().filter { it != queue(c) }

    fun setExtraQueues(c: Context, list: Collection<String>) {
        val old = extraQueues(c)
        val new = list.filter { it in QUEUES && it != queue(c) }.distinct().sortedBy { QUEUES.indexOf(it) }
        writeExtras(c, new)
        (old - new.toSet()).forEach { Store.clear(c, it) } // no cache for a queue that is no longer shown
    }

    private fun writeExtras(c: Context, list: List<String>) {
        p(c).edit().putString("extraQueues", list.joinToString(",")).apply()
    }

    /**
     * The settings shared between the watch and the phone (synchronised over the Wear OS Data Layer, see
     * [SettingsSync]); the display options of the tile and of the app screens stay per device.
     */
    data class Synced(
        val source: String, val queue: String, val maybeOff: Boolean,
        val notifyOff: Int, val notifyOn: Int, val quietFrom: Int, val quietTo: Int,
        val notifyToday: Boolean, val notifyTomorrow: Boolean,
        // how the watch looks: the tile, and the app screens of the watch
        val tileRings: Int = 1, val tileInnerLabels: Boolean = true, val tileShowNext: Boolean = true,
        val wearAppRings: Int = 1, val wearAppInnerLabels: Boolean = true,
    ) {
        /** Values from the other device are not trusted blindly: anything out of range falls back to the defaults. */
        fun sanitized() = copy(
            source = if (Source.values().any { it.name == source }) source else Source.POE.name,
            queue = queue.takeIf { it in QUEUES } ?: "3.2",
            notifyOff = notifyOff.coerceAtLeast(0),
            notifyOn = notifyOn.coerceAtLeast(0),
            quietFrom = quietFrom.coerceIn(0, 23),
            quietTo = quietTo.coerceIn(0, 23),
            tileRings = if (tileRings == 2) 2 else 1,
            wearAppRings = if (wearAppRings == 2) 2 else 1,
        )
    }

    fun synced(c: Context) = Synced(
        source(c).name, queue(c), maybeIsOff(c), notifyOff(c), notifyOn(c), quietFrom(c), quietTo(c),
        notifyTodayChanged(c), notifyTomorrowNew(c),
        tileRings(c), tileInnerLabels(c), tileShowNext(c), wearAppRings(c), wearAppInnerLabels(c),
    )

    /** When the shared settings were last changed on any device (ms since epoch; 0 = never). */
    fun syncTs(c: Context): Long = p(c).getLong("syncTs", 0L)

    /** A shared setting was changed here: stamp it and hand it to the other device. */
    private fun touch(c: Context) {
        p(c).edit().putLong("syncTs", System.currentTimeMillis()).apply()
        SettingsSync.push(c)
    }

    /**
     * Takes the shared settings of the other device if they are newer (the newest change wins). Writes the
     * preferences directly, not through the setters, so the change is not sent back.
     * Returns what changed: null = nothing was applied, otherwise whether the source or queue changed too.
     */
    fun applySynced(c: Context, v: Synced, ts: Long): Boolean? {
        if (ts <= syncTs(c)) return null
        val v = v.sanitized()
        val before = source(c) to queue(c)
        p(c).edit()
            .putString("source", v.source).putString("queue", v.queue).putBoolean("maybeOff", v.maybeOff)
            .putInt("notifyOff", v.notifyOff).putInt("notifyOn", v.notifyOn)
            .putInt("quietFrom", v.quietFrom).putInt("quietTo", v.quietTo)
            .putBoolean("notifyTodayChanged", v.notifyToday).putBoolean("notifyTomorrowNew", v.notifyTomorrow)
            .putInt("tileRings", v.tileRings).putBoolean("tileInnerLabels", v.tileInnerLabels)
            .putBoolean("tileShowNext", v.tileShowNext)
            .putInt(if (isWatch) "appRings" else "wearAppRings", v.wearAppRings)
            .putBoolean(if (isWatch) "appInnerLabels" else "wearAppInnerLabels", v.wearAppInnerLabels)
            .putLong("syncTs", ts).apply()
        val dataChanged = before != source(c) to queue(c)
        if (dataChanged) {
            // the old schedule no longer applies; a new main queue is no longer an extra one
            if (before.first != source(c)) Store.clearAll(c) else { Store.clear(c); Store.clear(c, queue(c)) }
            writeExtras(c, extraQueues(c))
        }
        return dataChanged
    }
}

/**
 * Cache in SharedPreferences: "2026-11-08=0000111...;2026-11-09=..." (48 slots of 30 min).
 *
 * Every function takes an optional [q]: null is the main queue (what the watch, the widgets and the reminders use);
 * an extra queue of the phone app (see [Settings.extraQueues]) has a cache file of its own.
 */
object Store {
    private fun name(q: String?) = if (q == null) "sched" else "sched_$q"
    private fun p(c: Context, q: String?) = c.getSharedPreferences(name(q), Context.MODE_PRIVATE)

    /**
     * Merges new days into the old ones (new ones win), drops days older than yesterday
     * and the days in [missing]: the source no longer publishes them, so the cached copy is stale.
     */
    fun merge(
        old: Map<LocalDate, IntArray>, fresh: Map<LocalDate, IntArray>, today: LocalDate,
        missing: Collection<LocalDate> = emptyList(),
    ): java.util.SortedMap<LocalDate, IntArray> =
        (old.filterKeys { it !in missing } + fresh)
            .filterKeys { !it.isBefore(today.minusDays(1)) }.toSortedMap()

    fun save(c: Context, fresh: Map<LocalDate, IntArray>, missing: Collection<LocalDate> = emptyList(), q: String? = null) {
        val today = LocalDate.now(Schedule.ZONE)
        val merged = merge(raw(c, q), fresh, today, missing)
        val s = merged.entries.joinToString(";") { (d, f) -> "$d=" + f.joinToString("") }
        p(c, q).edit().putString("days", s).putLong("fetched", System.currentTimeMillis())
            .remove("error").apply()
    }

    /** The last parsed cache string and its result: the tile, the complication and the app read it all the time. */
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Map<LocalDate, IntArray>>>()

    /** Parses "2026-11-08=0000111...;2026-11-09=..."; broken entries (bad date, wrong length) are skipped. */
    fun parseDays(s: String): Map<LocalDate, IntArray> = s.split(";").mapNotNull {
        val parts = it.split("=")
        if (parts.size != 2 || parts[1].length != Schedule.SLOTS) return@mapNotNull null
        runCatching { LocalDate.parse(parts[0]) }.getOrNull()
            ?.let { d -> d to IntArray(Schedule.SLOTS) { i -> parts[1][i].digitToIntOrNull() ?: 0 } }
    }.toMap()

    /** Slot states 0/1/2 per day. The result is shared between callers: it must not be modified. */
    fun raw(c: Context, q: String? = null): Map<LocalDate, IntArray> {
        val s = p(c, q).getString("days", null) ?: return emptyMap()
        parsed[name(q)]?.let { if (it.first == s) return it.second }
        return parseDays(s).also { parsed[name(q)] = s to it }
    }

    /**
     * Slot states (0 on, 1 off, 2 yellow) to "no power" flags. A yellow slot is the time needed for switching
     * after an outage ("power may be absent"): it counts as no power when [maybeIsOff], else as power on.
     * So the setting only moves the end of an outage, never its start.
     */
    fun flagsOf(raw: Map<LocalDate, IntArray>, maybeIsOff: Boolean): Map<LocalDate, BooleanArray> =
        raw.mapValues { (_, a) -> BooleanArray(a.size) { a[it] == 1 || (maybeIsOff && a[it] == 2) } }

    /** true: no power (including "may be off" while switching). */
    fun load(c: Context, maybeIsOff: Boolean = true, q: String? = null): Map<LocalDate, BooleanArray> =
        flagsOf(raw(c, q), maybeIsOff)

    /** true: switching slot, power may be off. */
    fun loadMaybe(c: Context, q: String? = null): Map<LocalDate, BooleanArray> =
        raw(c, q).mapValues { (_, a) -> BooleanArray(a.size) { a[it] == 2 } }

    /** What the user sees: schedule + error. The fetch time is deliberately left out. */
    fun snapshot(c: Context, q: String? = null): String = (p(c, q).getString("days", "") ?: "") + "|" + (error(c, q) ?: "")

    fun clear(c: Context, q: String? = null) { p(c, q).edit().clear().apply() }

    /** The main queue's cache and the caches of all extra queues: the source changed, none of them applies. */
    fun clearAll(c: Context) {
        clear(c)
        Settings.QUEUES.forEach { clear(c, it) }
    }

    fun fetchedAt(c: Context, q: String? = null): Long = p(c, q).getLong("fetched", 0L)

    /** When tomorrow's schedule was last asked of the source (see [Fetcher]: it is rechecked at most hourly). */
    fun tomorrowCheckedAt(c: Context, q: String? = null): Long = p(c, q).getLong("tomorrowChecked", 0L)
    fun markTomorrowChecked(c: Context, at: Long, q: String? = null) { p(c, q).edit().putLong("tomorrowChecked", at).apply() }
    /** Data older than [STALE_WARN_MS]: show a warning, the schedule may have changed. */
    fun isStale(c: Context, now: Long = System.currentTimeMillis(), q: String? = null): Boolean =
        fetchedAt(c, q).let { it > 0 && now - it > STALE_WARN_MS }

    const val STALE_WARN_MS = 3 * 60 * 60_000L

    fun error(c: Context, q: String? = null): String? = p(c, q).getString("error", null)
    fun setError(c: Context, msg: String, q: String? = null) { p(c, q).edit().putString("error", msg).apply() }
}

object Fetcher {
    /** Blocking call; true on success. The error is written to Store.error. */
    fun refresh(c: Context, extra: String? = null): Boolean = try { fetch(c, extra) } catch (e: java.io.IOException) {
        Store.setError(c, c.getString(R.string.err_no_connection), extra)
        false
    } catch (e: FetchError) {
        Store.setError(c, c.getString(e.res, *e.args), extra)
        false
    } catch (e: Exception) {
        Store.setError(c, e.message ?: e.toString(), extra)
        false
    }

    /** The extra queues of the phone app (none on the watch). A failure of one does not stop the others. */
    fun refreshExtras(c: Context) {
        for (q in Settings.extraQueues(c)) refresh(c, q)
    }

    /** [extra] = null: the main queue; otherwise one of the extra queues, which only fills its own cache. */
    private fun fetch(c: Context, extra: String?): Boolean {
        val source = Settings.source(c)
        val queue = extra ?: Settings.queue(c)
        val today = LocalDate.now(Schedule.ZONE)
        val hour = java.time.LocalTime.now(Schedule.ZONE).hour
        // Once tomorrow's schedule is cached it rarely changes: recheck it hourly instead of on every refresh
        // (for POE that saves a whole extra request). Until it is published, keep asking.
        val now = System.currentTimeMillis()
        val tomorrow = today.plusDays(1)
        val withTomorrow = tomorrow !in Store.raw(c, extra) || now - Store.tomorrowCheckedAt(c, extra) >= TOMORROW_RECHECK_MS
        val days = source.load(queue, today, hour, withTomorrow)
        if (days.isEmpty()) throw FetchError(R.string.err_schedule_not_found, queue)
        // settings changed while the request was in flight: the result is stale
        if (source != Settings.source(c) || queue != (extra ?: Settings.queue(c))) return false
        if (extra != null && extra !in Settings.extraQueues(c)) return false
        // Tomorrow was asked for but is gone (the source withdrew it): don't keep showing the old copy.
        // Today is kept: a missing today is more likely a glitch of the site than a real change.
        val asked = source.requested(today, hour, withTomorrow)
        val missing = asked.filter { it.isAfter(today) && it !in days }
        val before = Store.raw(c, extra)
        Store.save(c, days, missing, extra)
        if (tomorrow in asked) Store.markTomorrowChecked(c, now, extra)
        // reminders and the surfaces follow the main queue only
        if (extra == null) Notify.onScheduleSaved(c, before, Store.raw(c))
        return true
    }

    private const val TOMORROW_RECHECK_MS = 60 * 60_000L

    /**
     * Refresh the cache and redraw what shows it. The tile and the complication are redrawn if the schedule or
     * the error changed (POE usually returns the same schedule, no need to wake the watch face for nothing).
     * If only the fetch time changed, just the tile is redrawn: it is the only place that shows the time
     * ("updated HH:MM") and it would otherwise disagree with the app until its next hourly rebuild.
     */
    fun refreshAndNotify(c: Context): Boolean {
        val before = Store.snapshot(c)
        val fetchedBefore = Store.fetchedAt(c)
        val ok = refresh(c)
        if (Store.snapshot(c) != before) notifyAll(c)
        else if (Store.fetchedAt(c) != fetchedBefore) Surfaces.fetched(c)
        Notify.schedule(c)
        refreshExtras(c)
        return ok
    }

    fun notifyAll(c: Context) {
        // A BroadcastReceiver's own context may not bind to services, which the tile and complication update
        // requests do, so always go through the application context.
        val app = c.applicationContext
        Surfaces.changed(app)
        Notify.schedule(app)
    }
}

class RefreshWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val ok = withContext(Dispatchers.IO) { Fetcher.refreshAndNotify(applicationContext) }
        return if (ok || runAttemptCount >= 2) Result.success() else Result.retry()
    }
}

object Refresh {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    /** The background job also waits for a battery that is not low: refreshes can wait, the watch cannot. */
    private val netBatteryOk = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
    /**
     * The tile, the complication and the app trigger an extra refresh when the data is older than this.
     * It is longer than the periodic job interval (30 min + flex), so that they only step in if that job
     * is stuck, and do not add their own network wake-ups on top of it.
     */
    private const val STALE_MS = 45 * 60_000L

    /**
     * Periodic refresh every 30 min. The countdown is computed by the watch itself, so the data only has to be
     * fresh enough to notice a changed schedule; the 10 min flex window lets the system batch the wake-up
     * with other network jobs.
     */
    fun ensure(c: Context) {
        // the tile, the complication and the app call this on every request: do the work once per process
        if (ensured) return
        synchronized(this) {
            if (ensured) return
            val wm = WorkManager.getInstance(c.applicationContext)
            wm.cancelUniqueWork("periodic") // old 30-minute job
            wm.cancelUniqueWork("periodic15") // old 15-minute job
            // KEEP trusts the WorkManager database and never looks at the system job, but after a reboot the
            // JobScheduler job can be gone while the database still says "enqueued" (seen on the watch: no
            // refresh job for 21 h after a reboot; neither KEEP nor UPDATE brings it back). So look at
            // JobScheduler itself: if our periodic job is not there, replace the work, which registers it.
            // Only the periodic job requires "battery not low", this tells it from the one-off refresh job.
            val hasJob = c.getSystemService(JobScheduler::class.java).allPendingJobs.any {
                it.service.className == JOB_SERVICE && it.isRequireBatteryNotLow
            }
            wm.enqueueUniquePeriodicWork(
                "periodic30",
                if (hasJob) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES, 10, TimeUnit.MINUTES)
                    .setConstraints(netBatteryOk).build()
            )
            ensured = true
        }
    }

    @Volatile private var ensured = false

    private const val JOB_SERVICE = "androidx.work.impl.background.systemjob.SystemJobService"

    /** One-off refresh now. */
    fun now(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork(
            "once", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(net).build()
        )
    }

    /** ensure() + a one-off refresh if the cache is stale. */
    fun ifStale(c: Context) {
        ensure(c)
        if (System.currentTimeMillis() - Store.fetchedAt(c) > STALE_MS) now(c)
    }
}
