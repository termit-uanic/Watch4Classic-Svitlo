package app.wattlo.phone

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import app.wattlo.Palette

/** A card with a text and a switch; the whole card is the click target, the switch only shows the state. */
class SwitchRow(val label: TextView, val sw: Switch, val card: LinearLayout)

/** Small view factory shared by the screens (the UI is built in code, there are no layout files). */
class Ui(private val ctx: Context) {
    fun dp(v: Float) = v * ctx.resources.displayMetrics.density

    fun label(sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun pill(s: String, bg: Int, fg: Int, size: Float = 16f) = label(size, fg, bold = true).apply {
        text = s
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(28f)
            setColor(bg)
        }
        setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
    }

    private fun card() = GradientDrawable().apply {
        cornerRadius = dp(18f)
        setColor(Palette.CARD)
    }

    fun settingRow(onClick: () -> Unit) = label(15f, Palette.GREY).apply {
        setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
        background = card()
        setOnClickListener { onClick() }
    }

    fun switchRow(onClick: () -> Unit): SwitchRow {
        val text = label(15f, Palette.GREY)
        val sw = Switch(ctx).apply {
            isClickable = false
            isFocusable = false
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Palette.AMBER, Palette.GREY))
            trackTintList = ColorStateList(states, intArrayOf(Palette.AMBER_DIM, Palette.LABEL))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
            background = card()
            addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { onClick() }
        }
        return SwitchRow(text, sw, row)
    }

    fun section(title: String) = label(14f, Palette.WHITE, bold = true).apply {
        text = title
        setPadding(0, dp(18f).toInt(), 0, dp(6f).toInt())
    }

    fun lp(top: Float = 0f) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top).toInt() }
}
