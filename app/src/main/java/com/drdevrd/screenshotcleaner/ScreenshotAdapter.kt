package com.drdevrd.screenshotcleaner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

private const val TYPE_HEADER = 0
private const val TYPE_ITEM = 1

sealed class Row {
    data class Header(val groupKey: String, val label: Label, val count: Int) : Row()
    data class Item(val item: ScreenshotItem) : Row()
}

class ScreenshotAdapter(
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<Row>()

    /** Rebuild rows: grouped by label, then by similarity group within each label. */
    fun submit(items: List<ScreenshotItem>) {
        rows.clear()
        val byLabel = items.groupBy { it.label }
        for (label in Label.values()) {
            val labelItems = byLabel[label] ?: continue
            val byGroup = labelItems.groupBy { it.groupKey }
            for ((groupKey, groupItems) in byGroup) {
                rows.add(Row.Header(groupKey, label, groupItems.size))
                groupItems.forEach { rows.add(Row.Item(it)) }
            }
        }
        notifyDataSetChanged()
    }

    fun allItems(): List<ScreenshotItem> = rows.filterIsInstance<Row.Item>().map { it.item }

    fun selectAll(selected: Boolean) {
        rows.filterIsInstance<Row.Item>().forEach { it.item.selected = selected }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun selectGroup(groupKey: String, selected: Boolean) {
        rows.filterIsInstance<Row.Item>()
            .filter { it.item.groupKey == groupKey }
            .forEach { it.item.selected = selected }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun getItemViewType(position: Int) =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun getItemCount() = rows.size

    fun spanSize(position: Int, totalSpan: Int): Int =
        if (rows[position] is Row.Header) totalSpan else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_header, parent, false))
        } else {
            ItemVH(inflater.inflate(R.layout.item_screenshot, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> {
                val h = holder as HeaderVH
                h.text.text = "${row.label.display}  (${row.count})"
                h.checkBox.setOnCheckedChangeListener(null)
                h.checkBox.isChecked = false
                h.checkBox.setOnCheckedChangeListener { _, checked ->
                    selectGroup(row.groupKey, checked)
                }
            }
            is Row.Item -> {
                val h = holder as ItemVH
                h.thumb.setImageURI(row.item.uri)
                h.check.setOnCheckedChangeListener(null)
                h.check.isChecked = row.item.selected
                h.check.setOnCheckedChangeListener { _, checked ->
                    row.item.selected = checked
                    onSelectionChanged()
                }
                h.itemView.setOnClickListener {
                    row.item.selected = !row.item.selected
                    h.check.isChecked = row.item.selected
                    onSelectionChanged()
                }
            }
        }
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.headerText)
        val checkBox: CheckBox = v.findViewById(R.id.groupSelectAll)
    }

    class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumbImage)
        val check: CheckBox = v.findViewById(R.id.thumbCheck)
    }
}
