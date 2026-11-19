package app.wattlo.phone

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.content.res.ColorStateList
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import app.wattlo.Palette
import app.wattlo.Settings
import app.wattlo.core.R as CoreR

/**
 * The settings of one widget: which queue it shows (the main one or an extra queue of the phone) and its background.
 * It opens when the widget is added and again from the widget's own menu; "Done" saves and redraws the widget.
 */
class WidgetConfigActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID
    private var queue: String? = null
    private var bg = Widgets.BG_DARK
    private var opacity = 100
    private var ringsSetting = Widgets.AS_APP
    private var labelsSetting = Widgets.AS_APP
    private lateinit var ringsRow: TextView
    private lateinit var labelsRow: TextView
    private lateinit var opacityRow: LinearLayout
    private lateinit var opacityText: TextView
    private lateinit var opacityBar: SeekBar
    private lateinit var queueRow: TextView
    private lateinit var bgRow: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // until "Done" the widget is not added (or not changed)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        queue = Widgets.queueOf(this, id)
        bg = Widgets.bgOf(this, id)
        opacity = Widgets.opacityOf(this, id)
        ringsSetting = Widgets.ringsSettingOf(this, id)
        labelsSetting = Widgets.labelsSettingOf(this, id)
        // the compact status widget has no ring
        val hasRing = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className
            ?.endsWith("SmallWidget") != true

        queueRow = ui.groupRow(R.drawable.ic_tab_queues) { pickQueue() }
        bgRow = ui.groupRow(R.drawable.ic_ring) { pickBackground() }
        opacityText = ui.label(15f, Palette.GREY, bold = true)
        // steps of 5 %: the bar goes 0..20
        opacityBar = SeekBar(this).apply {
            max = 20
            progress = opacity / 5
            progressTintList = ColorStateList.valueOf(Palette.GREEN)
            thumbTintList = ColorStateList.valueOf(Palette.GREEN)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    opacity = value * 5
                    render()
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        opacityRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14f).toInt(), ui.dp(12f).toInt(), ui.dp(14f).toInt(), ui.dp(8f).toInt())
            addView(opacityText)
            addView(opacityBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(4f).toInt() })
        }
        ringsRow = ui.groupRow(R.drawable.ic_ring) { pickRings() }
        labelsRow = ui.groupRow(R.drawable.ic_text_size) { pickLabels() }
        val done = ui.pill(getString(R.string.widget_config_done), Palette.GREEN, Palette.BG).apply {
            setOnClickListener { save() }
        }
        val pad = ui.dp(16f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 2)
            addView(ui.label(26f, Palette.WHITE, bold = true).apply { text = getString(R.string.widget_config_title) })
            addView(
                if (hasRing) ui.group(queueRow, bgRow, opacityRow, ringsRow, labelsRow) else ui.group(queueRow, bgRow, opacityRow),
                ui.lp(18f)
            )
            addView(done, ui.lp(24f))
        }
        val width = minOf(resources.displayMetrics.widthPixels, ui.dp(560f).toInt())
        setContentView(ScrollView(this).apply {
            addView(column, FrameLayout.LayoutParams(width, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        })
        render()
    }

    private fun pickQueue() {
        val main = Settings.queue(this)
        val all = listOf<String?>(null) + Settings.extraQueues(this)
        val labels = all.map { q ->
            if (q == null) getString(R.string.widget_queue_main, getString(CoreR.string.queue_name, main))
            else getString(CoreR.string.queue_name, q)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.widget_config_queue))
            .setSingleChoiceItems(labels, all.indexOf(queue).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                queue = all[i]
                render()
            }
            .show()
    }

    private fun pickBackground() {
        val all = listOf(Widgets.BG_DARK, Widgets.BG_STATE, Widgets.BG_CLEAR)
        val labels = arrayOf(R.string.widget_bg_dark, R.string.widget_bg_state, R.string.widget_bg_clear)
            .map { getString(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.widget_config_background))
            .setSingleChoiceItems(labels, all.indexOf(bg).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                bg = all[i]
                render()
            }
            .show()
    }

    private fun pickRings() {
        val all = listOf(Widgets.AS_APP, "1", "2")
        val labels = arrayOf(
            getString(R.string.widget_as_app), getString(CoreR.string.tile_rings_one), getString(CoreR.string.tile_rings_two)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.widget_config_rings))
            .setSingleChoiceItems(labels, all.indexOf(ringsSetting).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                ringsSetting = all[i]
                render()
            }
            .show()
    }

    private fun pickLabels() {
        val all = listOf(Widgets.AS_APP, "both", "outer", "current", "none")
        val labels = arrayOf(
            getString(R.string.widget_as_app), getString(CoreR.string.tile_labels_both), getString(CoreR.string.tile_labels_outer),
            getString(R.string.widget_labels_current), getString(R.string.widget_labels_none)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.widget_config_labels))
            .setSingleChoiceItems(labels, all.indexOf(labelsSetting).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                labelsSetting = all[i]
                render()
            }
            .show()
    }

    private fun ringsName(rings: Int) = getString(if (rings == 2) CoreR.string.tile_rings_two else CoreR.string.tile_rings_one)

    private fun labelsName(l: RingLabels) = getString(
        when (l) {
            RingLabels.BOTH -> CoreR.string.tile_labels_both
            RingLabels.OUTER -> CoreR.string.tile_labels_outer
            RingLabels.CURRENT -> R.string.widget_labels_current
            RingLabels.NONE -> R.string.widget_labels_none
        }
    )

    private fun render() {
        val main = Settings.queue(this)
        queueRow.text = getString(
            CoreR.string.two_lines, getString(R.string.widget_config_queue),
            if (queue == null) getString(R.string.widget_queue_main, getString(CoreR.string.queue_name, main))
            else getString(CoreR.string.queue_name, queue)
        )
        // a transparent widget has no background to fade
        val fades = bg != Widgets.BG_CLEAR
        opacityRow.alpha = if (fades) 1f else 0.4f
        opacityBar.isEnabled = fades
        opacityText.text = getString(R.string.widget_config_opacity, opacity)
        ringsRow.text = getString(
            CoreR.string.two_lines, getString(R.string.widget_config_rings),
            if (ringsSetting == Widgets.AS_APP) getString(R.string.widget_as_app_value, ringsName(Settings.appRings(this)))
            else ringsName(Widgets.resolveRings(ringsSetting, 1))
        )
        labelsRow.text = getString(
            CoreR.string.two_lines, getString(R.string.widget_config_labels),
            if (labelsSetting == Widgets.AS_APP)
                getString(R.string.widget_as_app_value, labelsName(Widgets.resolveLabels(labelsSetting, Settings.appInnerLabels(this))))
            else labelsName(Widgets.resolveLabels(labelsSetting, true))
        )
        bgRow.text = getString(
            CoreR.string.two_lines, getString(R.string.widget_config_background),
            getString(when (bg) { Widgets.BG_STATE -> R.string.widget_bg_state; Widgets.BG_CLEAR -> R.string.widget_bg_clear; else -> R.string.widget_bg_dark })
        )
    }

    private fun save() {
        Widgets.setConfig(this, id, queue, bg, opacity, ringsSetting, labelsSetting)
        Widgets.updateAll(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
