package com.yehor.lightwatch

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.DegreesProp
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Arc
import androidx.wear.protolayout.LayoutElementBuilders.ArcAdapter
import androidx.wear.protolayout.LayoutElementBuilders.ArcLine
import androidx.wear.protolayout.LayoutElementBuilders.ArcSpacer
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import java.time.LocalDate

/**
 * The 24-hour ring around the tile, built from ProtoLayout arcs: 48 dashes (one per half hour, 00:00 at the
 * top), a few hours inside it and a white mark at the current time. It is the same picture as the day dial in
 * the app (see [DialModel] for the colours), and it needs no Context.
 */
object TileRing {
    private const val THICKNESS_DP = 8f
    // a round cap adds half the thickness at both ends of a line (about 2 degrees each on this ring), so the
    // line itself is short: 1.6 + 2 * 2 is a dash of about 5.6 degrees, 48 * (1.6 + 5.9) = 360
    private const val DASH_DEG = 1.6f
    private const val GAP_DEG = 5.9f

    /** The hours that get a label (the tile is small, the app shows all 24). */
    val LABELED_HOURS = listOf(0, 3, 6, 9, 12, 15, 18, 21)

    private fun color(role: DialModel.Role, past: Boolean): Int = when (role) {
        DialModel.Role.RED -> if (past) Palette.RED_DIM else Palette.RED
        DialModel.Role.AMBER -> if (past) Palette.AMBER_DIM else Palette.AMBER
        DialModel.Role.GREEN -> if (past) Palette.GREEN_DIM else Palette.GREEN
        DialModel.Role.NO_DATA -> Palette.NODATA
    }

    private fun fullBox(vararg content: LayoutElement): Box {
        val b = Box.Builder().setWidth(expand()).setHeight(expand())
        content.forEach { b.addContent(it) }
        return b.build()
    }

    /**
     * Today's 48 dashes. [flags] and [maybe] are the slots of the day, [curSlot] the current one (slots before
     * it are dark), [yellowAsOutage] makes the yellow slots amber instead of green.
     */
    fun dashes(flags: BooleanArray?, maybe: BooleanArray?, curSlot: Int, yellowAsOutage: Boolean): LayoutElement {
        val arc = Arc.Builder()
            .setAnchorAngle(degrees(0f))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
        for (i in 0 until Schedule.SLOTS) {
            val role = DialModel.roleOf(flags, maybe, i, yellowAsOutage)
            arc.addContent(
                ArcLine.Builder()
                    .setLength(degrees(DASH_DEG))
                    .setThickness(dp(THICKNESS_DP))
                    .setColor(argb(color(role, DialModel.isPast(i, curSlot))))
                    .setStrokeCap(LayoutElementBuilders.STROKE_CAP_ROUND)
                    .build()
            )
            arc.addContent(ArcSpacer.Builder().setLength(degrees(GAP_DEG)).build())
        }
        return fullBox(arc.build())
    }

    /** The labelled hours just inside the ring; each label stays upright. */
    fun hours(): LayoutElement {
        val labels = LABELED_HOURS.map { h ->
            Arc.Builder()
                .setAnchorAngle(degrees(h * 15f))
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_CENTER)
                .addContent(
                    ArcAdapter.Builder()
                        .setRotateContents(false)
                        .setContent(
                            Text.Builder()
                                .setText("%02d".format(h))
                                .setFontStyle(FontStyle.Builder().setSize(sp(9f)).setColor(argb(Palette.LABEL)).build())
                                .build()
                        )
                        .build()
                )
                .build()
        }
        val box = Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(THICKNESS_DP + 3f)).build())
                    .build()
            )
        labels.forEach { box.addContent(it) }
        return box.build()
    }

    /**
     * The current time as a white stroke across the ring. Its angle is computed by the watch from the platform
     * time (so it keeps moving between layouts); [nowMinute] is only the value used by renderers that cannot.
     */
    fun nowMark(date: LocalDate, nowMinute: Int): LayoutElement {
        val startOfDay = date.atStartOfDay(Schedule.ZONE).toInstant()
        val seconds = DynamicInstant.withSecondsPrecision(startOfDay)
            .durationUntil(DynamicInstant.platformTimeWithSecondsPrecision())
            .toIntSeconds()
        val angle = DegreesProp.Builder(DialModel.angleOfMinute(nowMinute))
            .setDynamicValue(seconds.asFloat().times(360f / 86_400f))
            .build()
        return fullBox(
            Arc.Builder()
                .setAnchorAngle(angle)
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_CENTER)
                .addContent(
                    ArcLine.Builder()
                        .setLength(degrees(1.2f))
                        .setThickness(dp(THICKNESS_DP + 6f))
                        .setColor(argb(Palette.WHITE))
                        .setStrokeCap(LayoutElementBuilders.STROKE_CAP_BUTT)
                        .build()
                )
                .build()
        )
    }
}
