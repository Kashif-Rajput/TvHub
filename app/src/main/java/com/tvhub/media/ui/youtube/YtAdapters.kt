package com.tvhub.media.ui.youtube

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.tvhub.media.R
import com.tvhub.media.core.Focus
import com.tvhub.media.data.YtCategory
import com.tvhub.media.data.YtVideo
import com.tvhub.media.databinding.ItemCardBinding
import com.tvhub.media.databinding.ItemChipBinding

/** Row of category buttons (Hollywood, Bollywood, Live TV...). */
class ChipAdapter(
    private val items: List<YtCategory>,
    private val onClick: (YtCategory, Int) -> Unit
) : RecyclerView.Adapter<ChipAdapter.VH>() {

    var selectedId: String? = null
        set(value) {
            val old = field
            field = value
            items.indexOfFirst { it.id == old }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            items.indexOfFirst { it.id == value }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }

    class VH(val b: ItemChipBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        Focus.attach(b.root)
        val vh = VH(b)
        b.root.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items[p], p)
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val ctx = holder.itemView.context
        holder.b.chip.text = c.label
        holder.b.chip.setTextColor(
            ContextCompat.getColor(ctx, if (c.id == selectedId) R.color.accent else R.color.text)
        )
        Focus.reset(holder.itemView)
    }
}

/** Grid of YouTube results, reusing the normal media card. */
class YtVideoAdapter(
    private val onClick: (YtVideo, Int) -> Unit
) : ListAdapter<YtVideo, YtVideoAdapter.VH>(DIFF) {

    init {
        setHasStableIds(true)
    }

    class VH(val b: ItemCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        Focus.attach(b.root)
        val vh = VH(b)
        b.root.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(getItem(p), p)
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = getItem(position)
        val b = holder.b
        b.title.text = v.title
        b.subtitle.text = v.channel
        val badge = if (v.live) b.root.context.getString(R.string.yt_live) else v.duration
        b.badge.text = badge
        b.badge.visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
        b.progress.visibility = View.GONE

        val iv = b.thumb
        iv.dispose()
        if (v.thumb.isNullOrEmpty()) {
            iv.scaleType = ImageView.ScaleType.CENTER
            iv.setImageResource(R.drawable.ic_play_box)
        } else {
            iv.load(v.thumb) {
                size(320, 180)
                placeholder(R.drawable.ic_play_box)
                error(R.drawable.ic_play_box)
                listener(
                    onStart = { iv.scaleType = ImageView.ScaleType.CENTER },
                    onSuccess = { _, _ -> iv.scaleType = ImageView.ScaleType.CENTER_CROP },
                    onError = { _, _ -> iv.scaleType = ImageView.ScaleType.CENTER }
                )
            }
        }
        Focus.reset(holder.itemView)
    }

    override fun onViewRecycled(holder: VH) {
        holder.b.thumb.dispose()
        holder.b.thumb.setImageDrawable(null)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<YtVideo>() {
            override fun areItemsTheSame(a: YtVideo, b: YtVideo) = a.id == b.id
            override fun areContentsTheSame(a: YtVideo, b: YtVideo) = a == b
        }
    }
}
