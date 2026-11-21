package app.wattlo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class JsonSourcesTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun IntArray.digits() = joinToString("")
    private val day = LocalDate.of(2026, 11, 21)

    // ---------------------------------------------------------------- YASNO

    /** Real answer for Kyiv (groups 1.1 and 2.1), saved on 2026-11-21: tomorrow is not published yet. */
    @Test fun yasno_todayFromSlots() {
        val m = JsonSources.yasno(res("yasno_kyiv.json"), "1.1")
        // off 01:00-05:00, 11:30-15:30 and 22:00-24:00
        val expected = "00" + "1".repeat(8) + "0".repeat(13) + "1".repeat(8) + "0".repeat(13) + "1".repeat(4)
        assertEquals(expected, m.getValue(day).digits())
        assertEquals(Schedule.SLOTS, expected.length)
    }

    @Test fun yasno_waitingForScheduleGivesNoDay() =
        assertEquals(setOf(day), JsonSources.yasno(res("yasno_kyiv.json"), "1.1").keys)

    @Test fun yasno_unknownGroupIsEmpty() = assertTrue(JsonSources.yasno(res("yasno_kyiv.json"), "9.9").isEmpty())

    @Test fun yasno_noOutagesAndEmergency() {
        val json = """{"3.1": {"today": {"slots": [], "date": "2026-11-21T00:00:00+02:00", "status": "NoOutages"},
            "tomorrow": {"slots": [], "date": "2026-11-22T00:00:00+02:00", "status": "EmergencyShutdowns"}}}"""
        val m = JsonSources.yasno(json, "3.1")
        assertEquals("0".repeat(48), m.getValue(day).digits())
        assertEquals(setOf(day), m.keys) // an emergency day has no schedule to show
    }

    // ---------------------------------------------------------------- the volunteers' files

    /** Real file of Poltava region (groups 1.1 and 2.1), saved on 2026-11-21 at 09:41 UTC. */
    private val fresh = Instant.parse("2026-11-21T10:00:00Z")

    @Test fun mirror_dayFromTheKeyInKyivTime() {
        val m = JsonSources.mirror(res("mirror_poltava.json"), "1.1", "test", fresh)
        // the key 1795212000 is 22:00 UTC on 20 November: midnight of the 21st in Kyiv
        assertEquals(setOf(day), m.keys)
        assertEquals(Schedule.SLOTS, m.getValue(day).size)
    }

    @Test fun mirror_exactStates() {
        val s = JsonSources.mirror(res("mirror_poltava.json"), "1.1", "test", fresh).getValue(day)
        for (i in 30..35) assertEquals("slot $i", 1, s[i])   // 15:00-18:00 no
        assertEquals(2, s[36]); assertEquals(0, s[37])         // 18:00-19:00 mfirst
        for (i in 42..45) assertEquals("slot $i", 1, s[i])   // 21:00-23:00 no
        assertEquals(0, s[46]); assertEquals(2, s[47])         // 23:00-24:00 msecond
        assertEquals(0, s[0]); assertEquals(0, s[29])
    }

    @Test fun mirror_otherGroup() {
        val s = JsonSources.mirror(res("mirror_poltava.json"), "2.1", "test", fresh).getValue(day)
        assertEquals(1, s[32]) // 16:00-17:00 no for 2.1 (hour key 17)
        assertEquals(0, s[30])
    }

    @Test fun mirror_unknownGroupIsEmpty() =
        assertTrue(JsonSources.mirror(res("mirror_poltava.json"), "9.9", "test", fresh).isEmpty())

    @Test(expected = FetchError::class) fun mirror_veryOldFileIsRefused() {
        JsonSources.mirror(res("mirror_poltava.json"), "1.1", "test", Instant.parse("2026-11-24T10:00:00Z"))
    }

    /** The file has no day of today (the volunteers' script stopped, or the new day is not collected yet). */
    @Test(expected = FetchError::class) fun mirror_withoutTodayIsRefused() {
        JsonSources.mirror(res("mirror_poltava.json"), "1.1", "test", Instant.parse("2026-11-22T10:00:00Z"))
    }

    @Test fun mirror_aQuietNightIsNotStale() {
        // updated hours ago, but the content is today's: fine
        JsonSources.mirror(res("mirror_poltava.json"), "1.1", "test", Instant.parse("2026-11-21T13:30:00Z"))
    }

    @Test fun mirror_lastUpdatedAsNumber() {
        val json = """{"lastUpdated": 1795257108, "fact": {"data": {"1795212000": {"GPV1.1": {"1": "first", "2": "second"}}}}}"""
        val s = JsonSources.mirror(json, "1.1", "test", Instant.ofEpochSecond(1795257108 + 60)).getValue(day)
        // 00:00-00:30 off, 00:30-01:00 on, 01:00-01:30 on, 01:30-02:00 off
        assertEquals(1, s[0]); assertEquals(0, s[1]); assertEquals(0, s[2]); assertEquals(1, s[3])
    }
}
