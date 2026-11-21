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
    BEZSVITLA("bezsvitla.com.ua");

    /**
     * Dates a [load] call asks the source about. A date that is requested but missing from the result
     * has no (or no longer has a) published schedule. [hour] is the current hour in [Schedule.ZONE].
     * POE needs a separate request per day, so tomorrow can be skipped with [withTomorrow]
     * (the other source returns both days in one response).
     */
    fun requested(today: LocalDate, hour: Int, withTomorrow: Boolean = true): List<LocalDate> =
        // POE publishes tomorrow's schedule in the evening, so it is not requested before noon
        if (this == POE && (hour < 12 || !withTomorrow)) listOf(today) else listOf(today, today.plusDays(1))

    fun load(
        region: Region, queue: String, today: LocalDate, hour: Int = java.time.LocalTime.now(Schedule.ZONE).hour,
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
        BEZSVITLA -> Schedule.parseBezsvitla(Http.request("https://bezsvitla.com.ua/${region.slug}"), today, queue)
            .mapValues { bits(it.value) }
    }

    private companion object {
        val POE_DATE: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")
        fun bits(f: BooleanArray) = IntArray(f.size) { if (f[it]) 1 else 0 }
    }
}

/**
 * The regions with a published schedule: the page of the region on bezsvitla.com.ua is [slug]; [queues] are the
 * groups the region's operator uses (Kyiv has 60 sub-queues, everywhere else 12); [sources] are the sites that
 * have its schedule, the first one is the default. Poltava also has the official schedule of its own power
 * company (poe.pl.ua).
 */
enum class Region(
    val slug: String, @androidx.annotation.StringRes val nameRes: Int, val queues: List<String>, val sources: List<Source>,
) {
    POLTAVA("poltavska-oblast", R.string.region_poltava, TWELVE, listOf(Source.POE, Source.BEZSVITLA)),
    KHARKIV("kharkivska-oblast", R.string.region_kharkiv, TWELVE, listOf(Source.BEZSVITLA)),
    DNIPRO("dnipropetrovska-oblast", R.string.region_dnipro, TWELVE, listOf(Source.BEZSVITLA)),
    ODESA("odeska-oblast", R.string.region_odesa, TWELVE, listOf(Source.BEZSVITLA)),
    KYIV_OBLAST("kyivska-oblast", R.string.region_kyiv_oblast, TWELVE, listOf(Source.BEZSVITLA)),
    CHERKASY("cherkaska-oblast", R.string.region_cherkasy, TWELVE, listOf(Source.BEZSVITLA)),
    CHERNIHIV("chernihivska-oblast", R.string.region_chernihiv, TWELVE, listOf(Source.BEZSVITLA)),
    IVANO_FRANKIVSK("ivano-frankivska-oblast", R.string.region_ivano_frankivsk, TWELVE, listOf(Source.BEZSVITLA)),
    KHMELNYTSKYI("khmelnytska-oblast", R.string.region_khmelnytskyi, TWELVE, listOf(Source.BEZSVITLA)),
    KIROVOHRAD("kirovohradska-oblast", R.string.region_kirovohrad, TWELVE, listOf(Source.BEZSVITLA)),
    LVIV("lvivska-oblast", R.string.region_lviv, TWELVE, listOf(Source.BEZSVITLA)),
    MYKOLAIV("mykolaivska-oblast", R.string.region_mykolaiv, TWELVE, listOf(Source.BEZSVITLA)),
    RIVNE("rivnenska-oblast", R.string.region_rivne, TWELVE, listOf(Source.BEZSVITLA)),
    SUMY("sumska-oblast", R.string.region_sumy, TWELVE, listOf(Source.BEZSVITLA)),
    TERNOPIL("ternopilska-oblast", R.string.region_ternopil, TERNOPIL_QUEUES, listOf(Source.BEZSVITLA)),
    VINNYTSIA("vinnytska-oblast", R.string.region_vinnytsia, TWELVE, listOf(Source.BEZSVITLA)),
    VOLYN("volynska-oblast", R.string.region_volyn, TWELVE, listOf(Source.BEZSVITLA)),
    ZAKARPATTIA("zakarpatska-oblast", R.string.region_zakarpattia, TWELVE, listOf(Source.BEZSVITLA)),
    ZAPORIZHZHIA("zaporizka-oblast", R.string.region_zaporizhzhia, TWELVE, listOf(Source.BEZSVITLA)),
    ZHYTOMYR("zhytomyrska-oblast", R.string.region_zhytomyr, TWELVE, listOf(Source.BEZSVITLA)),
    KYIV("kyiv", R.string.region_kyiv, (1..60).map { "$it.1" }, listOf(Source.BEZSVITLA));

    companion object {
        /** Every queue name of every region: their caches share a naming scheme. */
        val ALL_QUEUES: List<String> get() = entries.flatMap { it.queues }.distinct()
        fun of(name: String?): Region = entries.firstOrNull { it.name == name } ?: POLTAVA

        /** All regions in the alphabetical order of their names in the language of [c] (for the lists to choose from). */
        fun sorted(c: android.content.Context): List<Region> {
            val collator = java.text.Collator.getInstance(c.resources.configuration.locales[0])
            return entries.sortedWith { a, b -> collator.compare(c.getString(a.nameRes), c.getString(b.nameRes)) }
        }
    }
}

// the 12 groups of most operators: 1.1, 1.2, 2.1 ... 6.2
private val TWELVE = (1..6).flatMap { q -> (1..2).map { "$q.$it" } }

// Ternopil region: 1.1 ... 6.2 plus 7.2 and 12.2, as the site lists them
private val TERNOPIL_QUEUES = listOf("1.1", "1.2", "2.1", "2.2", "3.1", "3.2", "4.1", "4.2", "5.1", "5.2", "6.1", "6.2", "7.2", "12.2")

/** A fetch failure with a localized message: resolved with a Context where the error is stored. */
class FetchError(@androidx.annotation.StringRes val res: Int, vararg val args: Any) : Exception()

object Http {
    /** GET, or a form-urlencoded POST when [form] is given. */
    fun request(url: String, form: String? = null): String {
        val conn = java.net.URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Wattlo/1.0 (Wear OS and Android app; +https://github.com/termit-uanic/wattlo)")
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

    /** The 12 groups of the official poe.pl.ua table (and of most regions); a region can have its own, see [Region]. */
    val QUEUES = Region.POLTAVA.queues

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Poltava region until the user picks another (the setup wizard asks first thing). */
    fun region(c: Context): Region = Region.of(p(c).getString("region", null))

    /** The queues of the chosen region. */
    fun queues(c: Context): List<String> = region(c).queues

    /** The stored source if the region has it, else the region's own (a source that is gone, like energy-ua.info, too). */
    fun source(c: Context): Source {
        val r = region(c)
        val stored = runCatching { Source.valueOf(p(c).getString("source", null)!!) }.getOrNull()
        return stored?.takeIf { it in r.sources } ?: r.sources.first()
    }

    /** The queue until the user picks one (the setup wizard asks first thing). */
    const val DEFAULT_QUEUE = "1.1"

    fun queue(c: Context): String = p(c).getString("queue", null)?.takeIf { it in queues(c) } ?: DEFAULT_QUEUE

    /**
     * The user picked this queue (the setup wizard): store it and share it with the other device even when it is
     * the queue that was in force anyway, so that both devices end up with an explicit choice.
     */
    fun chooseQueue(c: Context, queue: String) {
        if (queue != queue(c)) set(c, queue = queue)
        else { p(c).edit().putString("queue", queue).apply(); touch(c) }
    }

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
        val r = region(c)
        val src = source.takeIf { it in r.sources } ?: r.sources.first()
        val q = queue.takeIf { it in r.queues } ?: DEFAULT_QUEUE
        if (src == source(c) && q == queue(c)) return
        val sourceChanged = src != source(c)
        p(c).edit().putString("source", src.name).putString("queue", q).apply()
        // the new main queue is no longer an extra one
        if (q in extraQueues(c)) writeExtras(c, extraQueues(c) - q)
        if (sourceChanged) Store.clearAll(c) else { Store.clear(c); Store.clear(c, q) }
        touch(c)
    }

    /**
     * Another region: its operator has its own queues and its own sites, so the source goes to the region's
     * default, the queue to [DEFAULT_QUEUE], the extra queues and their names go away, and no cache applies.
     */
    fun setRegion(c: Context, region: Region) {
        if (region == region(c)) return
        val e = p(c).edit().putString("region", region.name).putString("source", region.sources.first().name)
            .putString("queue", DEFAULT_QUEUE).putString("extraQueues", "")
        Region.ALL_QUEUES.forEach { e.remove("label_$it") }
        e.apply()
        Store.clearAll(c)
        touch(c)
    }

    /**
     * Extra queues, for the phone app only: it can show their state next to the main queue [queue]. They are not
     * synchronised, and the watch, the widgets and the reminders only ever use the main queue.
     */
    fun extraQueues(c: Context): List<String> =
        (p(c).getString("extraQueues", "") ?: "").split(",").filter { it in queues(c) }.distinct().filter { it != queue(c) }

    fun setExtraQueues(c: Context, list: Collection<String>) {
        val old = extraQueues(c)
        val all = queues(c)
        val new = list.filter { it in all && it != queue(c) }.distinct().sortedBy { all.indexOf(it) }
        writeExtras(c, new)
        (old - new.toSet()).forEach { Store.clear(c, it) } // no cache for a queue that is no longer shown
    }

    const val MAX_LABEL = 16

    /** A short name of an extra queue ("parents", "sister"), for the phone app only like the extra queues; "" when none. */
    fun queueLabel(c: Context, q: String): String = p(c).getString("label_$q", "").orEmpty()

    fun setQueueLabel(c: Context, q: String, label: String) {
        if (q !in Region.ALL_QUEUES) return
        val clean = label.trim().take(MAX_LABEL)
        p(c).edit().apply { if (clean.isEmpty()) remove("label_$q") else putString("label_$q", clean) }.apply()
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
        // a device without a region (an older build) is in the Poltava region
        val region: String = Region.POLTAVA.name,
    ) {
        /** Values from the other device are not trusted blindly: anything out of range falls back to the defaults. */
        fun sanitized(): Synced {
            val r = Region.of(region)
            return copy(
                region = r.name,
                source = if (r.sources.any { it.name == source }) source else r.sources.first().name,
                queue = queue.takeIf { it in r.queues } ?: DEFAULT_QUEUE,
            ).clamped()
        }

        private fun clamped() = copy(
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
        tileRings(c), tileInnerLabels(c), tileShowNext(c), wearAppRings(c), wearAppInnerLabels(c), region(c).name,
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
        val before = Triple(region(c), source(c), queue(c))
        p(c).edit()
            .putString("region", v.region).putString("source", v.source).putString("queue", v.queue).putBoolean("maybeOff", v.maybeOff)
            .putInt("notifyOff", v.notifyOff).putInt("notifyOn", v.notifyOn)
            .putInt("quietFrom", v.quietFrom).putInt("quietTo", v.quietTo)
            .putBoolean("notifyTodayChanged", v.notifyToday).putBoolean("notifyTomorrowNew", v.notifyTomorrow)
            .putInt("tileRings", v.tileRings).putBoolean("tileInnerLabels", v.tileInnerLabels)
            .putBoolean("tileShowNext", v.tileShowNext)
            .putInt(if (isWatch) "appRings" else "wearAppRings", v.wearAppRings)
            .putBoolean(if (isWatch) "appInnerLabels" else "wearAppInnerLabels", v.wearAppInnerLabels)
            .putLong("syncTs", ts).apply()
        val dataChanged = before != Triple(region(c), source(c), queue(c))
        if (dataChanged) {
            // the old schedule no longer applies; a new main queue is no longer an extra one
            if (before.first != region(c)) {
                // another region: its extra queues and their names do not apply either
                writeExtras(c, emptyList())
                Region.ALL_QUEUES.forEach { p(c).edit().remove("label_$it").apply() }
                Store.clearAll(c)
            } else {
                if (before.second != source(c)) Store.clearAll(c) else { Store.clear(c); Store.clear(c, queue(c)) }
                writeExtras(c, extraQueues(c))
            }
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
        Region.ALL_QUEUES.forEach { clear(c, it) }
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
        val region = Settings.region(c)
        val source = Settings.source(c)
        val queue = extra ?: Settings.queue(c)
        val today = LocalDate.now(Schedule.ZONE)
        val hour = java.time.LocalTime.now(Schedule.ZONE).hour
        // Once tomorrow's schedule is cached it rarely changes: recheck it hourly instead of on every refresh
        // (for POE that saves a whole extra request). Until it is published, keep asking.
        val now = System.currentTimeMillis()
        val tomorrow = today.plusDays(1)
        val withTomorrow = tomorrow !in Store.raw(c, extra) || now - Store.tomorrowCheckedAt(c, extra) >= TOMORROW_RECHECK_MS
        val days = source.load(region, queue, today, hour, withTomorrow)
        if (days.isEmpty()) throw FetchError(R.string.err_schedule_not_found, queue)
        // settings changed while the request was in flight: the result is stale
        if (region != Settings.region(c) || source != Settings.source(c) || queue != (extra ?: Settings.queue(c))) return false
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
