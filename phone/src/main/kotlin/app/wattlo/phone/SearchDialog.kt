package app.wattlo.phone

import android.app.Activity
import android.app.AlertDialog
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView

/**
 * Pick-from-a-list dialogs with a filter field above the list (the regions, the 60 queues of Kyiv): the typed text is
 * looked for anywhere in an item, ignoring case. Short lists (up to [MIN_FOR_FILTER] - 1 items) get no field and look
 * like any other choice dialog.
 */
object SearchDialog {
    const val MIN_FOR_FILTER = 13

    fun matches(item: String, filter: String): Boolean {
        val f = filter.trim()
        return f.isEmpty() || item.contains(f, ignoreCase = true)
    }

    /** One item, chosen with a tap; [onPick] gets its index in [items]. */
    fun single(a: Activity, title: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        if (items.size < MIN_FOR_FILTER) {
            AlertDialog.Builder(a).setTitle(title)
                .setSingleChoiceItems(items.toTypedArray(), selected) { d, i -> d.dismiss(); onPick(i) }
                .show()
            return
        }
        lateinit var dialog: AlertDialog
        build(a, title, items, multi = false, chosen = mutableSetOf(selected), onTap = { i -> dialog.dismiss(); onPick(i) }) {
            dialog = it.show()
        }
    }

    /** Several items; [onOk] gets the indexes (in [items]) that are checked when the user confirms. */
    fun multi(a: Activity, title: String, items: List<String>, checked: Set<Int>, onOk: (Set<Int>) -> Unit) {
        val chosen = checked.toMutableSet()
        if (items.size < MIN_FOR_FILTER) {
            AlertDialog.Builder(a).setTitle(title)
                .setMultiChoiceItems(items.toTypedArray(), BooleanArray(items.size) { it in chosen }) { _, i, on -> if (on) chosen += i else chosen -= i }
                .setPositiveButton(android.R.string.ok) { _, _ -> onOk(chosen) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        build(a, title, items, multi = true, chosen = chosen, onTap = {}) { b ->
            b.setPositiveButton(android.R.string.ok) { _, _ -> onOk(chosen) }
            b.setNegativeButton(android.R.string.cancel, null)
            b.show()
        }
    }

    private fun build(
        a: Activity, title: String, items: List<String>, multi: Boolean, chosen: MutableSet<Int>,
        onTap: (Int) -> Unit, show: (AlertDialog.Builder) -> Unit,
    ) {
        val ui = Ui(a)
        val builder = AlertDialog.Builder(a).setTitle(title)
        val ctx = builder.context
        val shown = mutableListOf<Int>() // indexes into items, the ones that pass the filter
        val adapter = ArrayAdapter(
            ctx, if (multi) android.R.layout.simple_list_item_multiple_choice else android.R.layout.simple_list_item_single_choice,
            mutableListOf<String>()
        )
        val list = ListView(ctx).apply {
            choiceMode = if (multi) ListView.CHOICE_MODE_MULTIPLE else ListView.CHOICE_MODE_SINGLE
            this.adapter = adapter
            setOnItemClickListener { _, _, pos, _ ->
                val i = shown[pos]
                if (multi) { if (isItemChecked(pos)) chosen += i else chosen -= i } else onTap(i)
            }
        }
        fun refill(filter: String) {
            shown.clear()
            shown += items.indices.filter { matches(items[it], filter) }
            adapter.clear()
            adapter.addAll(shown.map { items[it] })
            list.clearChoices()
            shown.forEachIndexed { pos, i -> if (i in chosen) list.setItemChecked(pos, true) }
            adapter.notifyDataSetChanged()
        }
        val field = ui.searchField(ctx.getString(R.string.search_hint)) { refill(it) }
        refill("")
        builder.setView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ui.dp(20f).toInt()
            setPadding(pad, ui.dp(4f).toInt(), pad, 0)
            addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, (a.resources.displayMetrics.heightPixels * 0.45f).toInt()))
        })
        show(builder)
    }
}
