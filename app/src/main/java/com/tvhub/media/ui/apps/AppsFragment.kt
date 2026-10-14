package com.tvhub.media.ui.apps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.tvhub.media.R
import com.tvhub.media.core.focusPosition
import com.tvhub.media.core.focusedPosition
import com.tvhub.media.data.AppLauncher
import com.tvhub.media.databinding.FragmentListBinding
import com.tvhub.media.ui.common.Tile
import com.tvhub.media.ui.common.TileAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsFragment : Fragment() {

    private var _b: FragmentListBinding? = null
    private val b get() = _b!!
    private var lastFocus = 0
    private var cached: List<Tile> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.title.setText(R.string.tile_apps)
        val adapter = TileAdapter { tile, pos ->
            lastFocus = pos
            if (!AppLauncher.launch(requireContext(), tile.id)) {
                Toast.makeText(requireContext(), R.string.launch_failed, Toast.LENGTH_SHORT).show()
            }
        }
        b.list.layoutManager = GridLayoutManager(requireContext(), 5)
        b.list.adapter = adapter
        b.list.itemAnimator = null

        if (cached.isNotEmpty()) {
            adapter.submitList(cached) { _b?.list?.focusPosition(lastFocus) }
            b.count.text = getString(R.string.items_count, cached.size)
            return
        }
        b.progress.visibility = View.VISIBLE
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { AppLauncher.installedApps(ctx, withIcons = true) }
            val binding = _b ?: return@launch
            cached = apps.map { Tile(it.packageName, it.label, R.drawable.ic_apps, it.icon) }
            binding.progress.visibility = View.GONE
            binding.count.text = getString(R.string.items_count, cached.size)
            adapter.submitList(cached) { _b?.list?.focusPosition(lastFocus) }
        }
    }

    override fun onDestroyView() {
        _b?.list?.focusedPosition()?.takeIf { it >= 0 }?.let { lastFocus = it }
        _b = null
        super.onDestroyView()
    }
}
