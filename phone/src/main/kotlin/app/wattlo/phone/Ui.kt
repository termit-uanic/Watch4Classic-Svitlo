package app.wattlo.phone

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
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

    /** The title of a row is the first line of its text: bold; the other lines are its value. */
    private class RowText(ctx: Context) : TextView(ctx) {
        override fun setText(text: CharSequence?, type: BufferType?) {
            val s = text?.toString().orEmpty()
            val i = s.indexOf('\n')
            super.setText(
                if (i <= 0) text else SpannableString(s).apply { setSpan(StyleSpan(Typeface.BOLD), 0, i, 0) },
                type
            )
        }
    }

    /** A round grey badge with a monochrome icon, shown at the start of a row. */
    private fun badge(res: Int): Drawable {
        val size = dp(36f).toInt()
        val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2B2F36.toInt()) }
        val icon = ctx.getDrawable(res)!!.mutate().apply { setTint(Palette.GREY) }
        return LayerDrawable(arrayOf(circle, icon)).apply {
            val i = dp(9f).toInt()
            setLayerInset(1, i, i, i, i)
            setBounds(0, 0, size, size)
        }
    }

    private fun chevron(): Drawable {
        val size = dp(20f).toInt()
        return ctx.getDrawable(R.drawable.ic_chevron)!!.mutate().apply { setTint(Palette.LABEL); setBounds(0, 0, size, size) }
    }

    private fun ripple() = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, ColorDrawable(Color.WHITE))

    private fun rowPadding(v: View) = v.setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())

    /** A tappable row of a [group]: icon, title and value, a chevron at the end. */
    fun groupRow(icon: Int, onClick: () -> Unit): TextView = RowText(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Palette.GREY)
        rowPadding(this)
        compoundDrawablePadding = dp(14f).toInt()
        setCompoundDrawablesRelative(badge(icon), null, chevron(), null)
        gravity = Gravity.CENTER_VERTICAL
        background = ripple()
        setOnClickListener { onClick() }
    }

    /** A row of a [group] that only shows information: icon, title and value, nothing to tap. */
    fun infoRow(icon: Int): TextView = RowText(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Palette.GREY)
        rowPadding(this)
        compoundDrawablePadding = dp(14f).toInt()
        setCompoundDrawablesRelative(badge(icon), null, null, null)
        gravity = Gravity.CENTER_VERTICAL
    }

    /** A row of a [group] with a switch; the whole row is the click target, the switch only shows the state. */
    fun switchRow(icon: Int, onClick: () -> Unit): SwitchRow {
        val text = RowText(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Palette.GREY)
            compoundDrawablePadding = dp(14f).toInt()
            setCompoundDrawablesRelative(badge(icon), null, null, null)
            gravity = Gravity.CENTER_VERTICAL
        }
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
            setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
            background = ripple()
            addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { onClick() }
        }
        return SwitchRow(text, sw, row)
    }

    /** Rows in one rounded card, with hairlines between them (they start after the icon). */
    fun group(vararg rows: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = card()
        clipToOutline = true
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(ctx).apply { setBackgroundColor(0xFF2A2E35.toInt()) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(1f).toInt().coerceAtLeast(1)).apply { marginStart = dp(64f).toInt() })
            addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    fun section(title: String) = label(12f, Palette.LABEL, bold = true).apply {
        text = title.uppercase()
        letterSpacing = 0.1f
        setPadding(dp(6f).toInt(), dp(22f).toInt(), 0, dp(8f).toInt())
    }

    fun lp(top: Float = 0f) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top).toInt() }
}

/**
 * A soft elliptical glow of [color] that fades to nothing exactly at the edges of the view it is the background
 * of (a round gradient would be cut off by the view's rectangle).
 */
class Halo(color: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        // a radial gradient of radius 1 around the origin; draw() scales the canvas to the ellipse
        shader = android.graphics.RadialGradient(
            0f, 0f, 1f, intArrayOf((color and 0x00FFFFFF) or 0x42000000, color and 0x00FFFFFF), null,
            android.graphics.Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.exactCenterX(), b.exactCenterY())
        canvas.scale(b.width() / 2f, b.height() / 2f)
        canvas.drawRect(-1f, -1f, 1f, 1f, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
