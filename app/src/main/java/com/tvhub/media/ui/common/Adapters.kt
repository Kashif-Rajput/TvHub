package com.tvhub.media.ui.common

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.tvhub.media.core.Focus
import com.tvhub.media.data.Thumb
import com.tvhub.media.databinding.ItemCardBinding
import com.tvhub.media.databinding.ItemRowBinding
import com.tvhub.media.databinding.ItemTileBinding

// ---------------------------------------------------------------- Home / app tiles

data class Tile(
    val id: String,
    val label: String,
    val iconRes: Int,
    val icon: Drawable? = null,
    val status: String? = null
)

class TileAdapter(
    private val onClick: (Tile, Int) -> Unit
) : ListAdapter<Tile, TileAdapter.VH>(DIFF) {

    init {
        setHasStableIds(true)
    }

    class VH(val b: ItemTileBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        Focus.attach(b.root)
        val vh = VH(b)
        b.root.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(getItem(p), p)
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = getItem(position)
        val b = holder.b
        b.label.text = t.label
        if (t.icon != null) b.icon.setImageDrawable(t.icon) else b.icon.setImageResource(t.iconRes)
        b.sub.text = t.status ?: ""
        b.sub.visibility = if (t.status.isNullOrEmpty()) View.GONE else View.VISIBLE
        Focus.reset(b.root)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Tile>() {
            override fun areItemsTheSame(a: Tile, b: Tile) = a.id == b.id
            override fun areContentsTheSame(a: Tile, b: Tile) = a == b
        }
    }
}

// ---------------------------------------------------------------- Media cards / rows

data class Item(
    val id: Long,
    val title: String,
    val subtitle: String = "",
    val badge: String = "",
    val thumb: Thumb? = null,
    val iconRes: Int,
    val progress: Int = -1,
    val payload: Any? = null
)

enum class Style { CARD, ROW }

class MediaAdapter(
    private val style: Style,
    private val fixedWidthDp: Int = 0,
    private val onClick: (Item, Int) -> Unit
) : ListAdapter<Item, RecyclerView.ViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    private class CardVH(val b: ItemCardBinding) : RecyclerView.ViewHolder(b.root)
    private class RowVH(val b: ItemRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun getItemViewType(position: Int): Int = style.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val vh: RecyclerView.ViewHolder = if (style == Style.CARD) {
            val b = ItemCardBinding.inflate(inflater, parent, false)
            if (fixedWidthDp > 0) {
                b.root.layoutParams = b.root.layoutParams.apply {
                    width = (fixedWidthDp * parent.resources.displayMetrics.density).toInt()
                }
            }
            CardVH(b)
        } else {
            RowVH(ItemRowBinding.inflate(inflater, parent, false))
        }
        Focus.attach(vh.itemView)
        vh.itemView.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(getItem(p), p)
        }
        return vh
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is CardVH -> {
                val b = holder.b
                b.title.text = item.title
                b.subtitle.text = item.subtitle
                b.badge.text = item.badge
                b.badge.visibility = if (item.badge.isEmpty()) View.GONE else View.VISIBLE
                if (item.progress in 1..100) {
                    b.progress.visibility = View.VISIBLE
                    b.progress.progress = item.progress
                } else {
                    b.progress.visibility = View.GONE
                }
                bindImage(b.thumb, item, 320, 180)
            }
            is RowVH -> {
                val b = holder.b
                b.title.text = item.title
                b.subtitle.text = item.subtitle
                b.badge.text = item.badge
                bindImage(b.thumb, item, 128, 128)
            }
        }
        Focus.reset(holder.itemView)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        val iv = when (holder) {
            is CardVH -> holder.b.thumb
            is RowVH -> holder.b.thumb
            else -> null
        }
        iv?.dispose()
        iv?.setImageDrawable(null) // let the bitmap be reclaimed
    }

    private fun bindImage(iv: ImageView, item: Item, w: Int, h: Int) {
        iv.dispose()
        val thumb = item.thumb
        if (thumb == null) {
            iv.scaleType = ImageView.ScaleType.CENTER
            iv.setImageResource(item.iconRes)
            return
        }
        iv.load(thumb) {
            size(w, h)
            placeholder(item.iconRes)
            error(item.iconRes)
            listener(
                onStart = { iv.scaleType = ImageView.ScaleType.CENTER },
                onSuccess = { _, _ -> iv.scaleType = ImageView.ScaleType.CENTER_CROP },
                onError = { _, _ -> iv.scaleType = ImageView.ScaleType.CENTER }
            )
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(a: Item, b: Item) = a.id == b.id
            override fun areContentsTheSame(a: Item, b: Item) =
                a.title == b.title && a.subtitle == b.subtitle && a.badge == b.badge &&
                    a.progress == b.progress && a.thumb == b.thumb && a.iconRes == b.iconRes
        }
    }
}
