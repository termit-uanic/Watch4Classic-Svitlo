package app.wattlo

import org.junit.Assert.assertEquals
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
}
