package app.wattlo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSyncTest {
    private val ok = Settings.Synced("POE", "4.1", false, 15, 5, 22, 7, true, true)

    @Test fun sanitized_keepsValidValues() = assertEquals(ok, ok.sanitized())

    @Test fun sanitized_fallsBackOnUnknownSourceAndQueue() {
        val s = ok.copy(source = "nope", queue = "9.9").sanitized()
        assertEquals(Source.POE.name, s.source)
        assertEquals(Settings.DEFAULT_QUEUE, s.queue)
    }

    @Test fun defaultQueueIsOneOfTheQueues() = org.junit.Assert.assertTrue(Settings.DEFAULT_QUEUE in Settings.QUEUES)

    @Test fun sanitized_ringsAreOneOrTwo() {
        val s = ok.copy(tileRings = 7, wearAppRings = 0).sanitized()
        assertEquals(1, s.tileRings)
        assertEquals(1, s.wearAppRings)
        assertEquals(2, ok.copy(tileRings = 2, wearAppRings = 2).sanitized().tileRings)
    }

    @Test fun sanitized_clampsNumbers() {
        val s = ok.copy(notifyOff = -5, notifyOn = -1, quietFrom = 30, quietTo = -2).sanitized()
        assertEquals(0, s.notifyOff)
        assertEquals(0, s.notifyOn)
        assertEquals(23, s.quietFrom)
        assertEquals(0, s.quietTo)
    }

    @Test fun sanitized_noRegionMeansPoltava() = assertEquals(Region.POLTAVA.name, ok.sanitized().region)

    @Test fun sanitized_unknownRegionFallsBackToPoltava() = assertEquals(Region.POLTAVA.name, ok.copy(region = "MARS").sanitized().region)

    @Test fun sanitized_queueIsCheckedAgainstTheRegion() {
        val kyiv = ok.copy(region = Region.KYIV.name, source = Source.BEZSVITLA.name, queue = "42.1").sanitized()
        assertEquals("42.1", kyiv.queue)
        // 42.1 is a Kyiv sub-queue, there is no such queue in the Poltava region
        assertEquals(Settings.DEFAULT_QUEUE, ok.copy(queue = "42.1").sanitized().queue)
        // and 3.2 is no queue of Kyiv
        assertEquals(Settings.DEFAULT_QUEUE, kyiv.copy(queue = "3.2").sanitized().queue)
    }

    @Test fun sanitized_sourceIsCheckedAgainstTheRegion() {
        // poe.pl.ua has the schedule of its own region only
        assertEquals(Source.BEZSVITLA.name, ok.copy(region = Region.KHARKIV.name, source = Source.POE.name).sanitized().source)
        assertEquals(Source.POE.name, ok.copy(source = Source.POE.name).sanitized().source)
        // a source that is gone (energy-ua.info) falls back to the region's own
        assertEquals(Source.POE.name, ok.copy(source = "ENERGY_UA").sanitized().source)
    }

    @Test fun regions_haveQueuesAndSources() {
        for (r in Region.entries) {
            assertTrue(r.name, r.sources.isNotEmpty() && r.queues.isNotEmpty())
            assertTrue(r.name, Settings.DEFAULT_QUEUE in r.queues)
        }
        assertEquals(60, Region.KYIV.queues.size)
        assertEquals(12, Region.KHARKIV.queues.size)
        assertTrue("60.1" in Region.ALL_QUEUES)
        assertEquals(21, Region.entries.size)
        assertEquals(Region.entries.size, Region.entries.map { it.slug }.toSet().size)
        assertEquals(14, Region.TERNOPIL.queues.size)
        assertTrue("12.2" in Region.ALL_QUEUES)
        assertEquals("12.2", ok.copy(region = Region.TERNOPIL.name, source = Source.BEZSVITLA.name, queue = "12.2").sanitized().queue)
    }
}
