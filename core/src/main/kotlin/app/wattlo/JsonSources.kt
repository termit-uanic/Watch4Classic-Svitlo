package app.wattlo

import app.wattlo.core.R
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The sources that publish JSON: the YASNO web app ([yasno]) and the files that volunteers keep on GitHub
 * ([mirror]). Both give a day as slot states (0 on, 1 off, 2 may be off) of the 48 half-hours.
 */
object JsonSources {
    /**
     * A file of the volunteers older than this is not trusted: their script may have stopped. "lastUpdated" is
     * the time the content changed (it stays put for hours on a quiet night), so it can only catch a long stop;
     * a file without the schedule of today is refused as well, see [mirror].
     */
    val MAX_AGE: Duration = Duration.ofHours(48)

    /**
     * planned-outages of one operator: `{"1.1": {"today": {"slots": [{"start": 60, "end": 300, "type": "Definite"}],
     * "date": "2026-11-21T00:00:00+02:00", "status": "ScheduleApplies"}, "tomorrow": {...}}, ...}`; start and end
     * are minutes from midnight. Only the days with the status "ScheduleApplies" (or "NoOutages") count: before
     * the schedule of tomorrow is published its status is "WaitingForSchedule" and the slots are empty; during
     * emergency outages ("EmergencyShutdowns") there is no schedule to show.
     */
    fun yasno(json: String, queue: String): Map<LocalDate, IntArray> {
        val group = JSONObject(json).optJSONObject(queue) ?: return emptyMap()
        val out = LinkedHashMap<LocalDate, IntArray>()
        for (day in listOf("today", "tomorrow")) {
            val d = group.optJSONObject(day) ?: continue
            val date = runCatching { LocalDate.parse(d.getString("date").take(10)) }.getOrNull() ?: continue
            val states = IntArray(Schedule.SLOTS)
            when (d.optString("status")) {
                "NoOutages" -> {}
                "ScheduleApplies" -> {
                    val slots = d.optJSONArray("slots") ?: continue
                    if (slots.length() == 0) continue
                    for (i in 0 until slots.length()) {
                        val s = slots.getJSONObject(i)
                        val from = s.getInt("start") / 30
                        val to = ((s.getInt("end") + 29) / 30).coerceAtMost(Schedule.SLOTS)
                        val state = when (s.optString("type")) { "NotPlanned" -> 0; "Definite" -> 1; else -> 2 }
                        for (k in from until to) if (k in 0 until Schedule.SLOTS) states[k] = state
                    }
                }
                else -> continue
            }
            out[date] = states
        }
        return out
    }

    /**
     * A region's file: `{"lastUpdated": "2026-11-21T10:31:22.702Z", "fact": {"data": {"<start of the day, unix
     * seconds>": {"GPV1.1": {"1": "yes", "2": "no", ...}}}}}`. Key "h" is the hour that ends at h:00; the values
     * are yes (on), no (off), first / second (off in the first / second half of the hour), maybe, mfirst, msecond
     * (the same, "may be off"). [source] is named in the message when the file is out of date: it has no day of
     * today (the script stopped, or the new day has not been collected yet) or "lastUpdated" is older than [MAX_AGE].
     */
    fun mirror(json: String, queue: String, source: String, now: Instant = Instant.now()): Map<LocalDate, IntArray> {
        val root = JSONObject(json)
        val updated = when (val v = root.opt("lastUpdated")) {
            is String -> runCatching { Instant.parse(v) }.getOrNull()
            is Number -> Instant.ofEpochSecond(v.toLong())
            else -> null
        }
        if (updated != null && Duration.between(updated, now) > MAX_AGE) throw FetchError(R.string.err_source_stale, source)
        val data = root.optJSONObject("fact")?.optJSONObject("data") ?: return emptyMap()
        val out = LinkedHashMap<LocalDate, IntArray>()
        for (key in data.keys()) {
            val start = key.toLongOrNull() ?: continue
            val date = Instant.ofEpochSecond(start).atZone(Schedule.ZONE).toLocalDate()
            val hours = data.getJSONObject(key).optJSONObject("GPV$queue") ?: continue
            val states = IntArray(Schedule.SLOTS)
            for (h in 1..24) {
                val (a, b) = when (hours.optString(h.toString())) {
                    "no" -> 1 to 1
                    "maybe" -> 2 to 2
                    "first" -> 1 to 0
                    "second" -> 0 to 1
                    "mfirst" -> 2 to 0
                    "msecond" -> 0 to 2
                    else -> 0 to 0
                }
                states[(h - 1) * 2] = a
                states[(h - 1) * 2 + 1] = b
            }
            out[date] = states
        }
        if (out.isNotEmpty() && now.atZone(Schedule.ZONE).toLocalDate() !in out) throw FetchError(R.string.err_source_stale, source)
        return out
    }
}
