package com.drdevrd.screenshotcleaner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.decode.VideoFrameDecoder
import coil.load

private const val TYPE_HEADER = 0
private const val TYPE_ITEM = 1

sealed class Row {
    data class Header(val key: String, val title: String, val count: Int) : Row()
    data class Item(val item: MediaItem) : Row()
}

class MediaAdapter(
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<Row>()

    /**
     * Group by content Category (same for screenshots, photos, and videos).
     * Similar items land next to each other inside a category (sorted by groupKey).
     */
    fun submit(items: List<MediaItem>) {
        rows.clear()
        if (items.isEmpty()) {
            notifyDataSetChanged()
            return
        }
        val byCategory = items.groupBy { it.category }
        for (category in Category.values()) {
            val catItems = byCategory[category] ?: continue
            val sorted = catItems.sortedBy { it.groupKey }
            rows.add(Row.Header("cat_${category.name}", category.display, catItems.size))
            sorted.forEach { rows.add(Row.Item(it)) }
        }
        notifyDataSetChanged()
    }

    fun allItems(): List<MediaItem> = rows.filterIsInstance<Row.Item>().map { it.item }

    fun selectAll(selected: Boolean) {
        rows.filterIsInstance<Row.Item>().forEach { it.item.selected = selected }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun selectSection(headerKey: String, selected: Boolean) {
        val items = rows.filterIsInstance<Row.Item>().map { it.item }
        val matching = if (headerKey.startsWith("cat_")) {
            val name = headerKey.removePrefix("cat_")
            items.filter { it.category.name == name }
        } else emptyList()
        matching.forEach { it.selected = selected }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun getItemCount(): Int = rows.size

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
                h.text.text = "${row.title}  (${row.count})"
                h.checkBox.setOnCheckedChangeListener(null)
                h.checkBox.isChecked = false
                h.checkBox.setOnCheckedChangeListener { _, checked ->
                    selectSection(row.key, checked)
                }
            }
            is Row.Item -> {
                val h = holder as ItemVH
                if (row.item.type == MediaType.VIDEO) {
                    h.thumb.load(row.item.uri) {
                        decoderFactory(VideoFrameDecoder.Factory())
                        crossfade(false)
                    }
                } else {
                    h.thumb.load(row.item.uri) {
                        crossfade(false)
                    }
                }
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
