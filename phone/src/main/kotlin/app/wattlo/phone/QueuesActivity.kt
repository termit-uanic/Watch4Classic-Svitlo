package app.wattlo.phone

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import app.wattlo.Fetcher
import app.wattlo.Fmt
import app.wattlo.Palette
import app.wattlo.Schedule
import app.wattlo.Settings
import app.wattlo.Store
import app.wattlo.core.R as CoreR
import java.time.ZonedDateTime
import kotlin.concurrent.thread

/** All the queues at a glance: the main one and the extra ones, each with its state and countdown. */
class QueuesActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 100L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = ui.dp(16f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, ui.dp(100f).toInt())
            addView(ui.label(26f, Palette.WHITE, bold = true).apply { text = getString(R.string.queues_title) })
            addView(ui.label(13f, Palette.LABEL).apply { text = getString(R.string.queues_hint) }, ui.lp(2f))
            addView(list, ui.lp(14f))
        }
        val width = minOf(resources.displayMetrics.widthPixels, ui.dp(560f).toInt())
        setContentView(withTabs(ScrollView(this).apply {
            addView(column, FrameLayout.LayoutParams(width, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }, Tab.QUEUES))
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        // an extra queue that was never fetched, or a stale one: ask the sources once
        val old = System.currentTimeMillis() - 10 * 60_000L
        if (!refreshing && (listOf<String?>(null) + Settings.extraQueues(this)).any { Store.fetchedAt(this, it) < old }) {
            refreshing = true
            thread {
                Fetcher.refreshAndNotify(applicationContext)
                runOnUiThread { refreshing = false; render() }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = openTab(Tab.NOW)

    private fun render() {
        list.removeAllViews()
        val now = ZonedDateTime.now(Schedule.ZONE)
        val main = Settings.queue(this)
        for (q in listOf<String?>(null) + Settings.extraQueues(this)) {
            list.addView(card(q, q ?: main, q == null, now), ui.lp(if (list.childCount == 0) 0f else 10f))
        }
    }

    private fun card(extra: String?, name: String, isMain: Boolean, now: ZonedDateTime): LinearLayout {
        val days = Store.load(this, Settings.maybeIsOff(this), extra)
        val maybe = Store.loadMaybe(this, extra)
        val st = Schedule.status(days, now)
        val switching = st != null && Schedule.at(maybe, now) == true
        val color = when { st == null -> Palette.LABEL; switching -> Palette.AMBER; st.off -> Palette.RED; else -> Palette.GREEN }
        val state = when {
            st == null -> CoreR.string.no_data_upper
            switching -> CoreR.string.state_switching_upper
            st.off -> CoreR.string.state_off_upper
            else -> CoreR.string.state_on_upper
        }
        val sub = if (st == null) "" else getString(
            when { st.changeIsMinimum -> CoreR.string.until_no_change; st.off -> CoreR.string.power_on_at; else -> CoreR.string.power_off_at },
            clock(st.changeAt, now)
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16f).toInt(), ui.dp(14f).toInt(), ui.dp(16f).toInt(), ui.dp(14f).toInt())
            background = GradientDrawable().apply { cornerRadius = ui.dp(22f); setColor(Palette.CARD) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.label(26f, Palette.WHITE, bold = true).apply { text = name })
                if (isMain) addView(ui.label(11f, Palette.LABEL).apply { text = getString(R.string.queue_main_tag) })
            }, LinearLayout.LayoutParams(ui.dp(84f).toInt(), WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.label(12f, color, bold = true).apply { text = getString(state); letterSpacing = 0.08f })
                addView(ui.label(13f, Palette.GREY).apply { text = sub })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (st != null) {
                addView(ui.label(24f, Palette.WHITE, bold = true).apply {
                    val left = Fmt.duration(this@QueuesActivity, st.remaining(now).plusSeconds(59)) // round up, like the tile
                    text = if (st.changeIsMinimum) getString(CoreR.string.at_least, left) else left
                })
            }
            setOnClickListener {
                getSharedPreferences("phone_ui", MODE_PRIVATE).edit().putString("shown", extra).apply()
                openTab(Tab.NOW)
            }
        }
    }
}
