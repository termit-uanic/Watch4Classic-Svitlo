package app.wattlo.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.wattlo.Fetcher
import app.wattlo.Notify
import app.wattlo.Palette
import app.wattlo.Refresh
import app.wattlo.Schedule
import app.wattlo.Settings
import app.wattlo.Store
import java.time.ZonedDateTime

/**
 * The setup wizard, shown the first time the app is opened (and again from the settings): a welcome, the main queue,
 * the extra queues, the look of the rings (with a small picture of each) and the reminders. The main queue and the look
 * are shared with the watch by the settings synchronisation.
 */
class SetupActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private val dots by lazy { LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(18f).toInt(), 0, ui.dp(8f).toInt()) } }
    private val content by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
    private val scroll by lazy { ScrollView(this).apply { isFillViewport = true } }
    private val next by lazy { ui.pill("", Palette.GREEN, Palette.BG).apply { setOnClickListener { advance() } } }
    private val skip by lazy {
        ui.label(14f, Palette.LABEL).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(14f).toInt(), 0, ui.dp(6f).toInt())
            setOnClickListener { skipStep() }
        }
    }

    private var step = 0
    private lateinit var main: String
    private val extras = linkedSetOf<String>()
    /** 0 = one ring of 24 hours, 1 = two rings with the numbers outside, 2 = two rings with all the hours. */
    private var look = 2
    private var sameOnWatch = true
    private var leadOff = 10
    private var leadOn = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        main = Settings.queue(this)
        extras.addAll(Settings.extraQueues(this))
        look = when { Settings.appRings(this) == 1 -> 0; Settings.appInnerLabels(this) -> 2; else -> 1 }
        leadOff = Settings.notifyOff(this).takeIf { it > 0 } ?: 10
        leadOn = Settings.notifyOn(this)

        scroll.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(next, ui.lp())
            addView(skip, ui.lp())
        }
        val pad = ui.dp(16f).toInt()
        val side = maxOf(pad, (resources.displayMetrics.widthPixels - ui.dp(560f).toInt()) / 2)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, 0, side, pad)
            addView(dots, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (step > 0) { step--; show() } else finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        show()
    }

    override fun onResume() {
        super.onResume()
        if (step == LAST) show() // the user may have changed the permission or the battery mode in the system settings
    }

    // ---------------------------------------------------------------- steps

    private fun show() {
        dots.removeAllViews()
        repeat(STEPS) { i ->
            dots.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == step) Palette.GREEN else 0xFF2B2F36.toInt()) }
            }, LinearLayout.LayoutParams(ui.dp(8f).toInt(), ui.dp(8f).toInt()).apply { marginStart = ui.dp(4f).toInt(); marginEnd = ui.dp(4f).toInt() })
        }
        content.removeAllViews()
        content.addView(
            when (step) { 0 -> welcome(); 1 -> queueStep(); 2 -> extrasStep(); 3 -> lookStep(); else -> remindersStep() },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
        scroll.scrollTo(0, 0)
        next.text = getString(when (step) { 0 -> R.string.setup_start; LAST -> R.string.setup_finish; else -> R.string.setup_next })
        skip.text = getString(if (step == 0) R.string.setup_skip_all else R.string.setup_skip)
        skip.visibility = if (step == 0 || step == 2 || step == LAST) View.VISIBLE else View.INVISIBLE
    }

    private fun advance() {
        when (step) {
            1 -> applyQueues() // from now on the schedule of the chosen queue is being fetched: the next steps can show it
            LAST -> return finishSetup()
        }
        step++
        show()
    }

    private fun skipStep() {
        when (step) {
            0 -> { markDone(this); finish() }
            LAST -> { leadOff = Settings.notifyOff(this); leadOn = Settings.notifyOn(this); finishSetup() }
            else -> { step++; show() }
        }
    }

    private fun title(text: Int, body: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(4f).toInt(), ui.dp(10f).toInt(), ui.dp(4f).toInt(), ui.dp(14f).toInt())
        addView(ui.label(26f, Palette.WHITE, bold = true).apply { this.text = getString(text) })
        addView(ui.label(14.5f, Palette.LABEL).apply { this.text = getString(body); setLineSpacing(0f, 1.15f) }, ui.lp(6f))
    }

    private fun welcome(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(8f).toInt(), ui.dp(48f).toInt(), ui.dp(8f).toInt(), 0)
        val size = ui.dp(132f).toInt()
        addView(ImageView(context).apply {
            setImageResource(app.wattlo.core.R.drawable.ic_tile_preview)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
            }
        }, LinearLayout.LayoutParams(size, size))
        addView(ui.label(28f, Palette.WHITE, bold = true).apply { text = getString(R.string.setup_welcome_title); gravity = Gravity.CENTER }, ui.lp(26f))
        addView(ui.label(15.5f, Palette.GREY).apply { text = getString(R.string.setup_welcome_body); gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, ui.lp(10f))
    }

    private fun queueStep(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(title(R.string.setup_queue_title, R.string.setup_queue_body))
        addView(grid(3, Settings.QUEUES.map { q -> chip(q, q == main, multi = false) { main = q; extras.remove(q); show() } }))
        addView(ui.label(13.5f, Palette.GREEN).apply {
            text = getString(R.string.setup_queue_hint)
            setPadding(ui.dp(4f).toInt(), ui.dp(16f).toInt(), 0, 0)
            setOnClickListener { tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.poe.pl.ua/disconnection/power-outages/"))) }
        })
    }

    private fun extrasStep(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(title(R.string.setup_extras_title, R.string.setup_extras_body))
        addView(grid(3, Settings.QUEUES.filter { it != main }.map { q ->
            chip(q, q in extras, multi = true) { if (q in extras) extras.remove(q) else extras.add(q); show() }
        }))
    }

    private fun lookStep(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(title(R.string.setup_look_title, R.string.setup_look_body))
        addView(lookCard(0, R.string.setup_look_one, R.string.setup_look_one_hint))
        addView(lookCard(1, R.string.setup_look_two, R.string.setup_look_two_hint), ui.lp(10f))
        addView(lookCard(2, R.string.setup_look_two_all, R.string.setup_look_two_all_hint), ui.lp(10f))
        val same = ui.switchRow(R.drawable.ic_tab_now) { sameOnWatch = !sameOnWatch; show() }
        same.label.text = getString(R.string.setup_same_on_watch)
        same.sw.isChecked = sameOnWatch
        addView(ui.group(same.card), ui.lp(16f))
    }

    private fun remindersStep(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(title(R.string.setup_reminders_title, R.string.setup_reminders_body))
        val leads = listOf(0, 5, 10, 15, 30)
        addView(ui.section(getString(app.wattlo.core.R.string.before_off)))
        addView(grid(3, leads.map { m -> chip(Notify.leadLabel(this@SetupActivity, m), m == leadOff, multi = false, size = 13f) { leadOff = m; show() } }))
        addView(ui.section(getString(app.wattlo.core.R.string.before_on)))
        addView(grid(3, leads.map { m -> chip(Notify.leadLabel(this@SetupActivity, m), m == leadOn, multi = false, size = 13f) { leadOn = m; show() } }))
        val rows = mutableListOf<View>()
        if ((leadOff > 0 || leadOn > 0) && !Notify.enabled(this@SetupActivity)) {
            rows += ui.groupRow(R.drawable.ic_bell) { askNotificationPermission() }.apply { text = getString(R.string.setup_allow_notifications) }
        }
        rows += ui.groupRow(R.drawable.ic_play) { openAutostart() }.apply { text = getString(R.string.autostart_button) }
        rows += ui.groupRow(R.drawable.ic_battery) { openBattery() }.apply { text = getString(R.string.battery_button) }
        addView(ui.label(13f, Palette.LABEL).apply { text = getString(R.string.background_hint); setPadding(ui.dp(4f).toInt(), ui.dp(18f).toInt(), ui.dp(4f).toInt(), ui.dp(8f).toInt()) })
        addView(ui.group(*rows.toTypedArray()))
    }

    // ---------------------------------------------------------------- pieces

    /** A choice of the queue (one is on) or of the extra queues (several are on). */
    private fun chip(label: String, on: Boolean, multi: Boolean, size: Float = 15f, click: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(ui.dp(2f).toInt(), ui.dp(13f).toInt(), ui.dp(2f).toInt(), ui.dp(13f).toInt())
        setTextColor(if (on && !multi) Palette.BG else if (on) Palette.WHITE else Palette.GREY)
        background = GradientDrawable().apply {
            cornerRadius = ui.dp(16f)
            setColor(if (on && !multi) Palette.GREEN else if (on) 0xFF2B2F36.toInt() else Palette.CARD)
            if (on && multi) setStroke(ui.dp(1.5f).toInt(), Palette.GREEN)
        }
        setOnClickListener { click() }
    }

    private fun grid(columns: Int, items: List<View>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        items.chunked(columns).forEachIndexed { r, row ->
            addView(LinearLayout(context).apply {
                row.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.dp(4f).toInt(); marginEnd = ui.dp(4f).toInt() }) }
                repeat(columns - row.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            }, ui.lp(if (r == 0) 0f else 8f))
        }
    }

    private fun lookCard(index: Int, titleRes: Int, hintRes: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(12f).toInt(), ui.dp(10f).toInt(), ui.dp(12f).toInt(), ui.dp(10f).toInt())
        background = GradientDrawable().apply {
            cornerRadius = ui.dp(20f)
            setColor(Palette.CARD)
            if (index == look) setStroke(ui.dp(1.5f).toInt(), Palette.GREEN)
        }
        addView(ImageView(context).apply { setImageBitmap(thumbnail(index)) }, LinearLayout.LayoutParams(ui.dp(76f).toInt(), ui.dp(76f).toInt()))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.label(16f, Palette.WHITE, bold = true).apply { text = getString(titleRes) })
            addView(ui.label(13f, Palette.LABEL).apply { text = getString(hintRes) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.dp(14f).toInt() })
        setOnClickListener { look = index; show() }
    }

    /** The ring as it would look: the real schedule of the chosen queue when it is already here, a sample otherwise. */
    private fun thumbnail(index: Int): Bitmap {
        val now = ZonedDateTime.now(Schedule.ZONE)
        val maybeOff = Settings.maybeIsOff(this)
        val flags = Store.load(this, maybeOff)[now.toLocalDate()] ?: sampleDay()
        val maybe = Store.loadMaybe(this)[now.toLocalDate()]
        return WidgetRing.render(
            flags, maybe, Schedule.slotOf(now), now.hour * 60 + now.minute,
            rings = if (index == 0) 1 else 2, yellowAsOutage = maybeOff,
            labels = if (index == 2) RingLabels.BOTH else RingLabels.OUTER, px = 360
        )
    }

    private fun sampleDay() = BooleanArray(Schedule.SLOTS) { i -> listOf(2..9, 16..24, 28..36, 40..47).any { i in it } }

    // ---------------------------------------------------------------- applying

    /** The main queue and the extra ones, right when the user has chosen them. */
    private fun applyQueues() {
        Settings.chooseQueue(this, main)
        Settings.setExtraQueues(this, extras - main)
        Refresh.now(this)
    }

    private fun finishSetup() {
        applyQueues()
        val rings = if (look == 0) 1 else 2
        val inner = look != 1
        Settings.setAppRings(this, rings)
        Settings.setAppInnerLabels(this, inner)
        if (sameOnWatch) {
            Settings.setTileRings(this, rings)
            Settings.setTileInnerLabels(this, inner)
            Settings.setWearAppRings(this, rings)
            Settings.setWearAppInnerLabels(this, inner)
        }
        Settings.setNotify(this, off = leadOff, on = leadOn)
        markDone(this)
        Fetcher.notifyAll(this) // the widgets, the reminders
        finish()
    }

    companion object {
        private const val STEPS = 5
        private const val LAST = STEPS - 1

        fun isDone(c: android.content.Context): Boolean = c.getSharedPreferences("phone_ui", android.content.Context.MODE_PRIVATE).getBoolean("setupDone", false)

        fun markDone(c: android.content.Context) {
            c.getSharedPreferences("phone_ui", android.content.Context.MODE_PRIVATE).edit().putBoolean("setupDone", true).apply()
        }
    }
}
