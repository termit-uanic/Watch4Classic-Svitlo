package app.wattlo.phone

import android.app.Activity
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.wattlo.DialData
import app.wattlo.DialView
import app.wattlo.Fetcher
import app.wattlo.Fmt
import app.wattlo.Palette
import app.wattlo.Refresh
import app.wattlo.Schedule
import app.wattlo.Settings
import app.wattlo.Store
import app.wattlo.core.R as CoreR
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
    private lateinit var refreshBtn: ImageView
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var todayDial: DialView? = null
    private lateinit var queueBar: LinearLayout

    /** The queue shown: null is the main one; an extra queue only while it is still in the settings. */
    private val uiPrefs by lazy { getSharedPreferences("phone_ui", MODE_PRIVATE) }
    private var shown: String?
        get() = uiPrefs.getString("shown", null)?.takeIf { it in Settings.extraQueues(this) }
        set(v) { uiPrefs.edit().putString("shown", v).apply() }

    /** What the lists and the footer were built for: the day, the half-hour slot, the data and its error. */
    private var shownSlot = ""

    private fun slotKey(now: ZonedDateTime) =
        "${now.toLocalDate()}#${Schedule.slotOf(now)}#${Store.fetchedAt(this, shown)}#${Store.error(this, shown)}#" +
            "$shown#${Settings.queue(this)}#${Settings.extraQueues(this)}#" +
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
        // the first time: the setup wizard, on top of this screen (not again after a rotation)
        if (savedInstanceState == null && !SetupActivity.isDone(this)) startActivity(Intent(this, SetupActivity::class.java))

        head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ui.dp(10f).toInt(), 0, ui.dp(14f).toInt())
        }
        lists = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        queueBar = LinearLayout(this)
        footer = ui.label(12f, Palette.LABEL).apply { gravity = Gravity.CENTER }
        refreshBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_refresh)
            setColorFilter(Palette.GREY, PorterDuff.Mode.SRC_IN)
            val p = ui.dp(10f).toInt()
            setPadding(p, p, p, p)
            contentDescription = getString(CoreR.string.refresh)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.CARD) }
            setOnClickListener { refreshNow() }
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.label(26f, Palette.WHITE, bold = true).apply { text = getString(CoreR.string.app_name) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(refreshBtn, LinearLayout.LayoutParams(ui.dp(42f).toInt(), ui.dp(42f).toInt()))
        }

        val pad = ui.dp(16f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, ui.dp(100f).toInt())
            addView(bar, ui.lp())
            addView(queueBar, ui.lp(10f))
            addView(head, ui.lp(8f))
            addView(lists, ui.lp(8f))
            addView(footer, ui.lp(14f))
        }
        // a phone in landscape or a tablet: keep the content in a comfortable column in the middle
        val maxWidth = ui.dp(560f).toInt()
        val width = minOf(resources.displayMetrics.widthPixels, maxWidth)
        scroll = ScrollView(this).apply {
            addView(column, FrameLayout.LayoutParams(width, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        setContentView(withTabs(scroll, Tab.NOW))

        if (System.currentTimeMillis() - Store.fetchedAt(this) > 2 * 60_000L) refreshNow()
    }

    /** A segmented control with one segment per queue (the first is the main one); hidden without extra queues. */
    private fun renderQueueBar() {
        queueBar.removeAllViews()
        val extras = Settings.extraQueues(this)
        queueBar.visibility = if (extras.isEmpty()) View.GONE else View.VISIBLE
        val cur = shown
        val p = ui.dp(4f).toInt()
        queueBar.setPadding(p, p, p, p)
        queueBar.background = GradientDrawable().apply { cornerRadius = ui.dp(24f); setColor(Palette.CARD) }
        for (q in listOf<String?>(null) + extras) {
            val on = q == cur
            queueBar.addView(
                ui.label(15f, if (on) Palette.WHITE else Palette.LABEL, bold = on).apply {
                    text = q ?: Settings.queue(this@MainActivity)
                    gravity = Gravity.CENTER
                    setPadding(0, ui.dp(9f).toInt(), 0, ui.dp(9f).toInt())
                    if (on) background = GradientDrawable().apply { cornerRadius = ui.dp(20f); setColor(0xFF2B2F36.toInt()) }
                    setOnClickListener {
                        shown = q
                        render()
                        // a queue that was never fetched: ask the source now
                        if (Store.fetchedAt(this@MainActivity, q) == 0L) refreshNow()
                    }
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
        }
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
        refreshBtn.alpha = 0.4f
        refreshBtn.animate().rotationBy(360f).setDuration(900).start()
        thread {
            Fetcher.refreshAndNotify(applicationContext)
            runOnUiThread {
                busy = false
                refreshBtn.alpha = 1f
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
        val days = Store.load(this, Settings.maybeIsOff(this), shown)
        val maybe = Store.loadMaybe(this, shown)
        val now = ZonedDateTime.now(Schedule.ZONE)
        renderHead(Schedule.status(days, now), now, Schedule.at(maybe, now) == true)
        todayDial?.setNow(now.hour * 60 + now.minute)
    }

    private fun renderContent() {
        renderQueueBar()
        val days = Store.load(this, Settings.maybeIsOff(this), shown)
        val maybe = Store.loadMaybe(this, shown)
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
        val fetched = Store.fetchedAt(this, shown)
        if (fetched > 0) {
            val t = Instant.ofEpochMilli(fetched).atZone(Schedule.ZONE)
            sb.append(getString(CoreR.string.updated_at, t.format(if (t.toLocalDate() == today) HHMM else DDHHMM)))
        } else if (!busy) sb.append(getString(CoreR.string.never_updated))
        val err = Store.error(this, shown)
        err?.let { sb.append("\n").append(getString(CoreR.string.warning_prefix, it)) }
        val stale = Store.isStale(this, q = shown)
        if (stale && err == null) sb.insert(0, "⚠ ")
        footer.text = sb
        footer.setTextColor(if (err != null || stale) Palette.AMBER else Palette.LABEL)
    }

    private fun renderHead(st: Schedule.Status?, now: ZonedDateTime, switching: Boolean) {
        head.removeAllViews()
        head.background = null
        fun add(v: View, top: Float = 0f) = head.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.dp(top).toInt()
        })
        add(ui.label(13f, Palette.LABEL, bold = true).apply {
            val label = shown?.let { Settings.queueLabel(this@MainActivity, it) }.orEmpty()
            text = getString(CoreR.string.queue_upper, shown ?: Settings.queue(this@MainActivity)) +
                if (label.isEmpty()) "" else " · " + label.uppercase()
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
        head.background = halo(color)
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
            val left = Fmt.duration(this@MainActivity, st.remaining(now).plusSeconds(59)) // round up, like the tile
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

        lists.addView(dial, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = ui.dp(if (lists.childCount > 0) 24f else 0f).toInt()
        })

        if (flags == null) return
        val ranges = Schedule.offRanges(flags)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16f).toInt(), ui.dp(8f).toInt(), ui.dp(16f).toInt(), ui.dp(8f).toInt())
            background = GradientDrawable().apply { cornerRadius = ui.dp(22f); setColor(Palette.CARD) }
        }
        lists.addView(card, ui.lp(8f))
        if (ranges.isEmpty()) card.addView(ui.label(15f, Palette.GREEN).apply {
            text = getString(CoreR.string.power_all_day)
            setPadding(0, ui.dp(6f).toInt(), 0, ui.dp(6f).toInt())
        })
        for ((a, b) in ranges) {
            val past = curSlot >= 0 && b <= curSlot
            val active = curSlot in a until b
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(7f).toInt(), 0, ui.dp(7f).toInt())
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

    private fun hhmm(t: ZonedDateTime, now: ZonedDateTime): String = clock(t, now)

    /** A soft glow of the state's colour behind the countdown. */
    private fun halo(color: Int) = Halo(color)

    private companion object {
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DDHHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    }
}
