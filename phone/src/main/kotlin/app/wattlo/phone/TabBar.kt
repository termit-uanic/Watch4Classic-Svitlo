package app.wattlo.phone

import android.app.Activity
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import app.wattlo.Palette
import app.wattlo.core.R as CoreR

/** The three screens of the phone app, in the order of the tab bar. */
enum class Tab(val icon: Int, val label: Int, val target: Class<out Activity>) {
    NOW(R.drawable.ic_tab_now, R.string.tab_now, MainActivity::class.java),
    QUEUES(R.drawable.ic_tab_queues, R.string.tab_queues, QueuesActivity::class.java),
    SETTINGS(R.drawable.ic_tab_settings, CoreR.string.settings, SettingsActivity::class.java),
}

/**
 * Opens a tab without an animation and without piling up screens: "Now" is the root of the task (everything above
 * it is closed), the others are brought to the front if they are already open.
 */
fun Activity.openTab(t: Tab) {
    val flags = Intent.FLAG_ACTIVITY_NO_ANIMATION or
        if (t == Tab.NOW) Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        else Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
    startActivity(Intent(this, t.target).addFlags(flags))
}

/**
 * Wraps a screen's [content] and floats the tab bar over its bottom edge: a capsule where only the open tab shows
 * its name. The content has to leave room under it (about 100 dp of bottom padding in the scrolled column).
 */
fun Activity.withTabs(content: View, current: Tab): View {
    val ui = Ui(this)
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = ui.dp(6f).toInt()
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            cornerRadius = ui.dp(30f)
            setColor(Palette.CARD)
            setStroke(ui.dp(1f).toInt().coerceAtLeast(1), 0xFF2E3239.toInt())
        }
        elevation = ui.dp(10f)
        for (t in Tab.entries) addView(tabView(ui, t, t == current))
    }
    return FrameLayout(this).apply {
        addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(bar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = ui.dp(14f).toInt()
        })
    }
}

private fun Activity.tabView(ui: Ui, t: Tab, selected: Boolean) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    setPadding(ui.dp(16f).toInt(), ui.dp(10f).toInt(), ui.dp(16f).toInt(), ui.dp(10f).toInt())
    contentDescription = getString(t.label)
    if (selected) background = GradientDrawable().apply { cornerRadius = ui.dp(24f); setColor(0xFF2F343C.toInt()) }
    addView(ImageView(context).apply {
        setImageResource(t.icon)
        setColorFilter(if (selected) Palette.WHITE else Palette.LABEL, PorterDuff.Mode.SRC_IN)
    }, LinearLayout.LayoutParams(ui.dp(22f).toInt(), ui.dp(22f).toInt()))
    if (selected) {
        addView(ui.label(13f, Palette.WHITE, bold = true).apply { text = getString(t.label) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ui.dp(6f).toInt() })
    }
    setOnClickListener { if (!selected) openTab(t) }
}

/** "HH:mm" of a moment of the schedule; midnight of the next day reads as 24:00. */
fun clock(t: java.time.ZonedDateTime, now: java.time.ZonedDateTime): String =
    if (t.hour == 0 && t.minute == 0 && t.toLocalDate() != now.toLocalDate()) "24:00"
    else t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
