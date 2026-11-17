package com.yehor.lightwatch.phone

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yehor.lightwatch.DialData
import com.yehor.lightwatch.DialView
import com.yehor.lightwatch.Fetcher
import com.yehor.lightwatch.Fmt
import com.yehor.lightwatch.Palette
import com.yehor.lightwatch.Refresh
import com.yehor.lightwatch.Schedule
import com.yehor.lightwatch.Settings
import com.yehor.lightwatch.Store
import com.yehor.lightwatch.core.R as CoreR
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * The phone's main screen: the state and the countdown, then today and tomorrow as day rings (one ring of 24 hours
 * or two of 12, by the setting) with the list of outages, and the "Refresh" button. The settings are on their own
 * screen ([SettingsActivity]).
 */
class MainActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private lateinit var head: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var lists: LinearLayout
    private lateinit var footer: TextView
    private lateinit var btn: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var todayDial: DialView? = null

    /** What the lists and the footer were built for: the day, the half-hour slot, the data and its error. */
    private var shownSlot = ""

    private fun slotKey(now: ZonedDateTime) =
        "${now.toLocalDate()}#${Schedule.slotOf(now)}#${Store.fetchedAt(this)}#${Store.error(this)}#" +
            "${Settings.appRings(this)}#${Settings.appInnerLabels(this)}#${Settings.maybeIsOff(this)}"

    private val tick = object : Runnable {
        override fun run() {
            // Between slot changes only the countdown moves: don't rebuild the whole screen
            if (slotKey(ZonedDateTime.now(Schedule.ZONE)) != shownSlot) render() else updateHead()
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 100L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Refresh.ensure(this)

        head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        lists = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        footer = ui.label(12f, Palette.LABEL).apply { gravity = Gravity.CENTER }
        btn = ui.pill(getString(CoreR.string.refresh), Palette.GREEN, Palette.BG).apply {
            setOnClickListener { refreshNow() }
        }
        val settings = ui.label(15f, Palette.GREY, bold = true).apply {
            text = getString(CoreR.string.settings)
            setPadding(ui.dp(12f).toInt(), ui.dp(8f).toInt(), ui.dp(12f).toInt(), ui.dp(8f).toInt())
            background = GradientDrawable().apply { cornerRadius = ui.dp(20f); setColor(Palette.CARD) }
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.label(20f, Palette.WHITE, bold = true).apply { text = getString(CoreR.string.app_name) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(settings)
        }

        val pad = ui.dp(16f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 2)
            addView(bar, ui.lp())
            addView(head, ui.lp(8f))
            addView(lists, ui.lp(8f))
            addView(footer, ui.lp(14f))
            addView(btn, ui.lp(10f))
        }
        // a phone in landscape or a tablet: keep the content in a comfortable column in the middle
        val maxWidth = ui.dp(560f).toInt()
        val width = minOf(resources.displayMetrics.widthPixels, maxWidth)
        scroll = ScrollView(this).apply {
            addView(column, FrameLayout.LayoutParams(width, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        setContentView(scroll)

        if (System.currentTimeMillis() - Store.fetchedAt(this) > 2 * 60_000L) refreshNow()
    }

    override fun onResume() {
        super.onResume()
        shownSlot = "" // the data may have changed while the screen was off: rebuild everything once
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    // ---------------------------------------------------------------- actions

    private fun refreshNow() {
        if (busy) return
        busy = true
        btn.text = getString(CoreR.string.refreshing)
        btn.alpha = 0.6f
        thread {
            Fetcher.refreshAndNotify(applicationContext)
            runOnUiThread {
                busy = false
                btn.text = getString(CoreR.string.refresh)
                btn.alpha = 1f
                render()
            }
        }
    }

    // ---------------------------------------------------------------- rendering

    /** Rebuilding the content must keep the scroll position. */
    private fun render() {
        if (!::scroll.isInitialized) return renderContent()
        val y = scroll.scrollY
        renderContent()
        scroll.post { scroll.scrollTo(0, y) }
    }

    /** Only the header (state and countdown): cheap, runs every minute. */
    private fun updateHead() {
        val days = Store.load(this, Settings.maybeIsOff(this))
        val maybe = Store.loadMaybe(this)
        val now = ZonedDateTime.now(Schedule.ZONE)
        renderHead(Schedule.status(days, now), now, Schedule.at(maybe, now) == true)
        todayDial?.setNow(now.hour * 60 + now.minute)
    }

    private fun renderContent() {
        val days = Store.load(this, Settings.maybeIsOff(this))
        val maybe = Store.loadMaybe(this)
        val now = ZonedDateTime.now(Schedule.ZONE)
        shownSlot = slotKey(now)
        renderHead(Schedule.status(days, now), now, Schedule.at(maybe, now) == true)

        lists.removeAllViews()
        todayDial = null
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        dayBlock(CoreR.string.today, today, days[today], maybe[today], Schedule.slotOf(now), now.hour * 60 + now.minute)
        dayBlock(CoreR.string.tomorrow, tomorrow, days[tomorrow], maybe[tomorrow], -1, -1)
        if (maybe.values.any { m -> m.any { it } }) {
            lists.addView(ui.label(12f, Palette.LABEL).apply {
                text = getString(CoreR.string.yellow_hint)
                setPadding(0, ui.dp(8f).toInt(), 0, 0)
            })
        }

        val sb = StringBuilder()
        val fetched = Store.fetchedAt(this)
        if (fetched > 0) {
            val t = Instant.ofEpochMilli(fetched).atZone(Schedule.ZONE)
            sb.append(getString(CoreR.string.updated_at, t.format(if (t.toLocalDate() == today) HHMM else DDHHMM)))
        } else if (!busy) sb.append(getString(CoreR.string.never_updated))
        val err = Store.error(this)
        err?.let { sb.append("\n").append(getString(CoreR.string.warning_prefix, it)) }
        val stale = Store.isStale(this)
        if (stale && err == null) sb.insert(0, "⚠ ")
        footer.text = sb
        footer.setTextColor(if (err != null || stale) Palette.AMBER else Palette.LABEL)
    }

    private fun renderHead(st: Schedule.Status?, now: ZonedDateTime, switching: Boolean) {
        head.removeAllViews()
        fun add(v: View, top: Float = 0f) = head.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.dp(top).toInt()
        })
        add(ui.label(13f, Palette.LABEL, bold = true).apply {
            text = getString(CoreR.string.queue_upper, Settings.queue(this@MainActivity))
            letterSpacing = 0.1f
        })
        if (st == null) {
            add(ui.label(20f, Palette.GREY, bold = true).apply { text = getString(CoreR.string.no_data) }, 18f)
            add(ui.label(14f, Palette.LABEL).apply {
                text = getString(if (busy) CoreR.string.loading else CoreR.string.press_refresh)
                gravity = Gravity.CENTER
            }, 4f)
            return
        }
        val color = when { switching -> Palette.AMBER; st.off -> Palette.RED; else -> Palette.GREEN }
        val title = getString(
            when {
                switching -> CoreR.string.state_switching_upper
                st.off -> CoreR.string.state_off_upper
                else -> CoreR.string.state_on_upper
            }
        )
        add(ui.pill(title, color, Palette.BG, 14f).apply {
            setPadding(ui.dp(14f).toInt(), ui.dp(6f).toInt(), ui.dp(14f).toInt(), ui.dp(6f).toInt())
        }, 10f)
        add(ui.label(46f, Palette.WHITE, bold = true).apply {
            val left = Fmt.duration(this@MainActivity, st.remaining(now))
            text = if (st.changeIsMinimum) getString(CoreR.string.at_least, left) else left
        }, 4f)
        add(ui.label(16f, Palette.GREY).apply {
            text = getString(
                when {
                    st.changeIsMinimum -> CoreR.string.until_no_change
                    st.off -> CoreR.string.power_on_at
                    else -> CoreR.string.power_off_at
                },
                hhmm(st.changeAt, now)
            )
        })
        st.nextLen?.let {
            val len = (if (st.nextEndIsMinimum) "≥ " else "") + Fmt.duration(this, it)
            add(ui.label(14f, Palette.LABEL).apply {
                text = getString(if (st.off) CoreR.string.next_with_power else CoreR.string.next_without_power, len)
            }, 2f)
        }
    }

    /** One day: the ring (the day, its date and the total in the middle), then the list of its outages. */
    private fun dayBlock(
        @androidx.annotation.StringRes titleRes: Int, date: LocalDate,
        flags: BooleanArray?, maybe: BooleanArray?, curSlot: Int, nowMinute: Int,
    ) {
        val summary: String
        val summaryColor: Int
        when {
            flags == null -> { summary = getString(CoreR.string.no_schedule_yet); summaryColor = Palette.LABEL }
            flags.none { it } -> { summary = getString(CoreR.string.power_all_day); summaryColor = Palette.GREEN }
            else -> {
                summary = getString(CoreR.string.minus, Fmt.duration(this, Schedule.offDuration(flags)))
                summaryColor = Palette.RED
            }
        }
        val dial = DialView(
            this,
            DialData(
                flags, maybe, curSlot, nowMinute, Settings.maybeIsOff(this),
                date.format(DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.getDefault())),
                getString(titleRes), summary, summaryColor,
                Settings.appRings(this), Settings.appInnerLabels(this)
            )
        )
        if (curSlot >= 0) todayDial = dial

        if (lists.childCount > 0) {
            lists.addView(View(this).apply { setBackgroundColor(Palette.CARD) },
                LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(2f).toInt()).apply {
                    topMargin = ui.dp(20f).toInt()
                    bottomMargin = ui.dp(12f).toInt()
                })
        }
        lists.addView(dial, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        if (flags == null) return
        val ranges = Schedule.offRanges(flags)
        if (ranges.isEmpty()) lists.addView(ui.label(15f, Palette.GREEN).apply { text = getString(CoreR.string.power_all_day) })
        for ((a, b) in ranges) {
            val past = curSlot >= 0 && b <= curSlot
            val active = curSlot in a until b
            lists.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(4f).toInt(), 0, ui.dp(4f).toInt())
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (past) Palette.RED_DIM else Palette.RED)
                    }
                }, LinearLayout.LayoutParams(ui.dp(9f).toInt(), ui.dp(9f).toInt()).apply { rightMargin = ui.dp(10f).toInt() })
                addView(ui.label(16f, if (past) Palette.LABEL else Palette.GREY, bold = active).apply {
                    text = getString(CoreR.string.time_range, Schedule.slotTime(a), Schedule.slotTime(b))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(ui.label(14f, if (active) Palette.RED else Palette.LABEL).apply {
                    text = if (active) getString(CoreR.string.now)
                    else Fmt.duration(this@MainActivity, Duration.ofMinutes((b - a) * 30L))
                })
            })
        }
    }

    private fun hhmm(t: ZonedDateTime, now: ZonedDateTime): String =
        if (t.hour == 0 && t.minute == 0 && t.toLocalDate() != now.toLocalDate()) "24:00" else t.format(HHMM)

    private companion object {
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DDHHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    }
}
