package app.wattlo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Fixtures are real site responses saved on 2026-11-10 at 00:54 (Europe/Kiev).
 * If a site changes its markup: save a new response to src/test/resources and update the expected values.
 */
class ParsersTest {
    private fun res(name: String) =
        javaClass.classLoader!!.getResource(name)!!.readText(Charsets.UTF_8)

    private val day = LocalDate.of(2026, 11, 10)

    /** One "0"/"1"/"2" per slot, so the expected value reads like the website. */
    private fun IntArray.str() = joinToString("")
    private fun BooleanArray.str() = joinToString("") { if (it) "1" else "0" }

    // ---------------------------------------------------------------- POE

    @Test fun poe_queue32() {
        val s = Schedule.parsePoe(res("poe.html"), "3.2")!!
        assertEquals("111112000000011111111200011111111200011111112000", s.str())
    }

    @Test fun poe_allQueuesParse() {
        val html = res("poe.html")
        for (q in Settings.QUEUES) assertEquals(q, Schedule.SLOTS, Schedule.parsePoe(html, q)?.size)
    }

    @Test fun poe_unknownQueue() = assertNull(Schedule.parsePoe(res("poe.html"), "9.9"))

    @Test fun poe_emptyResponse() = assertNull(Schedule.parsePoe("", "3.2"))

    // ---------------------------------------------------------------- bezsvitla

    @Test fun bezsvitla_queue32() {
        val m = Schedule.parseBezsvitla(res("bezsvitla.html"), day, "3.2")
        // tomorrow was not published yet when the fixture was saved ("[]")
        assertEquals(setOf(day), m.keys)
        assertEquals("11111" + "00000000" + "11111111" + "0000" + "11111111" + "0000" + "1111111" + "0000", m[day]!!.str())
    }

    @Test fun bezsvitla_otherQueueNotInFixture() =
        assertTrue(Schedule.parseBezsvitla(res("bezsvitla.html"), day, "1.1").isEmpty())

    // ---------------------------------------------------------------- bezsvitla: Kyiv (60 sub-queues "1.1" .. "60.1")

    /** Real cards of the Kyiv page (queues 1.1, 10.1 and 11.1), saved on 2026-11-20. */
    @Test fun bezsvitla_kyivSubQueue() {
        val m = Schedule.parseBezsvitla(res("bezsvitla_kyiv.html"), LocalDate.of(2026, 11, 20), "10.1")
        // the card has no schedule for tomorrow yet ("[]")
        assertEquals(setOf(LocalDate.of(2026, 11, 20)), m.keys)
        assertEquals("0".repeat(22) + "1".repeat(10) + "0".repeat(10) + "1".repeat(6), m.getValue(LocalDate.of(2026, 11, 20)).str())
    }

    @Test fun bezsvitla_kyivQueue1_1IsNotQueue11_1() {
        val d = LocalDate.of(2026, 11, 20)
        val a = Schedule.parseBezsvitla(res("bezsvitla_kyiv.html"), d, "1.1").getValue(d)
        val b = Schedule.parseBezsvitla(res("bezsvitla_kyiv.html"), d, "11.1").getValue(d)
        assertEquals(Schedule.SLOTS, a.size)
        assertTrue(a.str() != b.str())
        // 1.1: no power from 04:30 to 07:30
        assertEquals("111111", a.str().substring(9, 15))
    }

    @Test fun bezsvitla_kyivQueueNotInFixture() =
        assertTrue(Schedule.parseBezsvitla(res("bezsvitla_kyiv.html"), LocalDate.of(2026, 11, 20), "2.1").isEmpty())

    // ---------------------------------------------------------------- notifications

    @Test fun notify_next() {
        val raw = Schedule.parsePoe(res("poe.html"), "3.2")!!
        val days = mapOf(day to BooleanArray(48) { raw[it] != 0 })
        val now = ZonedDateTime.of(2026, 11, 10, 0, 54, 0, 0, Schedule.ZONE)
        // notify only before power-off: next outage at 06:30, so at 06:15
        val (fire, off, at) = Notify.next(days, now, offLead = 15, onLead = 0)!!
        assertTrue(off)
        assertEquals("06:30", "%02d:%02d".format(at.hour, at.minute))
        assertEquals("06:15", "%02d:%02d".format(fire.hour, fire.minute))
        // 10 min before power-on: power at 03:00, so at 02:50
        val (fire2, off2, _) = Notify.next(days, now, offLead = 0, onLead = 10)!!
        assertTrue(!off2)
        assertEquals("02:50", "%02d:%02d".format(fire2.hour, fire2.minute))
        // both off: nothing
        assertNull(Notify.next(days, now, 0, 0))
        // change already closer than the lead time: notify right away
        val late = ZonedDateTime.of(2026, 11, 10, 2, 55, 0, 0, Schedule.ZONE)
        val (fire3, _, at3) = Notify.next(days, late, offLead = 0, onLead = 10)!!
        assertEquals(late, fire3)
        assertEquals(3, at3.hour)
        // ...but not twice: 03:00 was already notified, so take the next power-on (11:00)
        val shown = at3.toInstant().toEpochMilli()
        val (fire4, _, at4) = Notify.next(days, late, offLead = 0, onLead = 10, shownAt = shown)!!
        assertEquals(11, at4.hour)
        assertEquals("10:50", "%02d:%02d".format(fire4.hour, fire4.minute))
    }

    @Test fun notify_stillValid() {
        val raw = Schedule.parsePoe(res("poe.html"), "3.2")!!
        val today = BooleanArray(48) { raw[it] != 0 }
        val days = mapOf(day to today)
        fun t(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 10, h, m, 0, 0, Schedule.ZONE)
        // regular changes during the day
        assertTrue(Notify.stillValid(days, t(3, 0), off = false))
        assertTrue(Notify.stillValid(days, t(6, 30), off = true))
        // schedule changed: 06:30 is no longer a power-on
        assertTrue(!Notify.stillValid(days, t(6, 30), off = false))
        assertTrue(!Notify.stillValid(days, t(7, 0), off = true)) // already off at 06:30, so not a boundary
        // midnight: yesterday is not cached, the notification must not be lost
        assertTrue(Notify.stillValid(days, t(0, 0), off = true))
        // if yesterday is cached and it was also off, it's not a boundary: no notification
        val withYesterday = days + (day.minusDays(1) to BooleanArray(48) { true })
        assertTrue(!Notify.stillValid(withYesterday, t(0, 0), off = true))
        // the event's day is not cached at all: don't notify
        assertTrue(!Notify.stillValid(days, t(0, 0).plusDays(2), off = true))
    }

    // ---------------------------------------------------------------- state

    @Test fun status_onPoeData() {
        val raw = Schedule.parsePoe(res("poe.html"), "3.2")!!
        val days = mapOf(day to BooleanArray(48) { raw[it] != 0 })
        val now = ZonedDateTime.of(2026, 11, 10, 0, 54, 0, 0, Schedule.ZONE)
        val st = Schedule.status(days, now)!!
        assertTrue(st.off)
        assertEquals(3, st.changeAt.hour) // yellow 02:30–03:00 counts as "off"
        // if yellow counts as "on", power is back at 02:30
        val days2 = mapOf(day to BooleanArray(48) { raw[it] == 1 })
        val st2 = Schedule.status(days2, now)!!
        assertEquals(2, st2.changeAt.hour)
        assertEquals(30, st2.changeAt.minute)
    }
}
