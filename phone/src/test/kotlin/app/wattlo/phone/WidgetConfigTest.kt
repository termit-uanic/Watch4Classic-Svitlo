package app.wattlo.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetConfigTest {
    @Test fun rings_followTheAppUnlessTheWidgetHasItsOwn() {
        assertEquals(2, Widgets.resolveRings(Widgets.AS_APP, 2))
        assertEquals(1, Widgets.resolveRings(Widgets.AS_APP, 1))
        assertEquals(1, Widgets.resolveRings("1", 2))
        assertEquals(2, Widgets.resolveRings("2", 1))
    }

    @Test fun labels_followTheAppSetting() {
        assertEquals(RingLabels.BOTH, Widgets.resolveLabels(Widgets.AS_APP, true))
        assertEquals(RingLabels.OUTER, Widgets.resolveLabels(Widgets.AS_APP, false))
    }

    @Test fun labels_ownChoiceWins() {
        assertEquals(RingLabels.CURRENT, Widgets.resolveLabels("current", true))
        assertEquals(RingLabels.NONE, Widgets.resolveLabels("none", true))
        assertEquals(RingLabels.OUTER, Widgets.resolveLabels("outer", true))
        assertEquals(RingLabels.BOTH, Widgets.resolveLabels("both", false))
    }

    @Test fun labels_unknownValueFallsBackToTheApp() {
        assertEquals(RingLabels.OUTER, Widgets.resolveLabels("garbage", false))
    }
}
