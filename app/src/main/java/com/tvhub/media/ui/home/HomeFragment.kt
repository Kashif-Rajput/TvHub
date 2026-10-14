package com.tvhub.media.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvhub.media.MainActivity
import com.tvhub.media.R
import com.tvhub.media.core.Format
import com.tvhub.media.core.UsbMonitor
import com.tvhub.media.core.focusPosition
import com.tvhub.media.data.AppInfo
import com.tvhub.media.data.AppLauncher
import com.tvhub.media.data.MediaEntry
import com.tvhub.media.data.MediaKind
import com.tvhub.media.data.PlaybackStore
import com.tvhub.media.data.StreamingService
import com.tvhub.media.data.Volumes
import com.tvhub.media.databinding.FragmentHomeBinding
import com.tvhub.media.ui.apps.AppsFragment
import com.tvhub.media.ui.browser.BrowserFragment
import com.tvhub.media.ui.common.Item
import com.tvhub.media.ui.common.MediaAdapter
import com.tvhub.media.ui.common.MenuKeyHandler
import com.tvhub.media.ui.common.Open
import com.tvhub.media.ui.common.Style
import com.tvhub.media.ui.common.Tile
import com.tvhub.media.ui.common.TileAdapter
import com.tvhub.media.ui.media.MediaListFragment
import com.tvhub.media.ui.settings.SettingsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment(), MenuKeyHandler {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    private lateinit var tileAdapter: TileAdapter
    private lateinit var continueAdapter: MediaAdapter
    private lateinit var recentAdapter: MediaAdapter

    // Kept across view re-creation (fragment instance survives on the back stack).
    private var tiles: List<Tile> = emptyList()
    private val resolved = HashMap<String, String?>()
    private var focusRow = 0
    private var focusPos = 0
    private var seenUsbEvent = -1L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        tileAdapter = TileAdapter { tile, _ -> onTile(tile) }
        b.tiles.layoutManager = GridLayoutManager(ctx, 5)
        b.tiles.adapter = tileAdapter
        b.tiles.itemAnimator = null
        if (tiles.isEmpty()) tiles = baseTiles()
        tileAdapter.submitList(tiles)

        continueAdapter = MediaAdapter(Style.CARD, fixedWidthDp = 220) { item, _ -> openEntry(item) }
        b.continueRow.layoutManager = LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false)
        b.continueRow.adapter = continueAdapter
        b.continueRow.itemAnimator = null

        recentAdapter = MediaAdapter(Style.CARD, fixedWidthDp = 220) { item, _ -> openEntry(item) }
        b.recentRow.layoutManager = LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false)
        b.recentRow.adapter = recentAdapter
        b.recentRow.itemAnimator = null

        if (seenUsbEvent < 0) seenUsbEvent = UsbMonitor.events.value.time
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UsbMonitor.events.collect { ev ->
                    if (ev.time != 0L && ev.time != seenUsbEvent) {
                        seenUsbEvent = ev.time
                        Toast.makeText(
                            requireContext(),
                            if (ev.mounted) R.string.usb_connected else R.string.usb_removed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    refreshUsb()
                }
            }
        }

        refreshRecents()
        b.tiles.post { restoreFocus() }
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        refreshRecents()
    }

    override fun onPause() {
        super.onPause()
        rememberFocus()
    }

    override fun onDestroyView() {
        rememberFocus()
        _b = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ focus

    private fun rows(): List<RecyclerView> = listOf(b.tiles, b.continueRow, b.recentRow)

    private fun rememberFocus() {
        val binding = _b ?: return
        val f = binding.root.findFocus() ?: return
        rows().forEachIndexed { idx, rv ->
            val vh = rv.findContainingViewHolder(f)
            if (vh != null && vh.bindingAdapterPosition >= 0) {
                focusRow = idx
                focusPos = vh.bindingAdapterPosition
                return
            }
        }
    }

    private fun restoreFocus() {
        if (_b == null) return
        val rv = rows().getOrNull(focusRow)?.takeIf { it.visibility == View.VISIBLE && (it.adapter?.itemCount ?: 0) > 0 }
        if (rv != null) rv.focusPosition(focusPos) else b.tiles.focusPosition(0)
    }

    // ------------------------------------------------------------------ data

    private fun baseTiles(): List<Tile> {
        val list = ArrayList<Tile>()
        AppLauncher.services.forEach { list += Tile(it.id, getString(it.labelRes), it.iconRes) }
        list += Tile("usb", getString(R.string.tile_usb), R.drawable.ic_usb)
        list += Tile("videos", getString(R.string.tile_videos), R.drawable.ic_video)
        list += Tile("music", getString(R.string.tile_music), R.drawable.ic_music)
        list += Tile("pictures", getString(R.string.tile_pictures), R.drawable.ic_image)
        list += Tile("apps", getString(R.string.tile_apps), R.drawable.ic_apps)
        list += Tile("settings", getString(R.string.tile_settings), R.drawable.ic_settings)
        return list
    }

    private fun refreshApps() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val apps = AppLauncher.installedApps(ctx, withIcons = false)
                AppLauncher.services.associate { svc ->
                    val pkg = AppLauncher.resolve(ctx, svc, apps)
                    svc.id to (pkg to pkg?.let { AppLauncher.appIcon(ctx, it, preferBanner = false) })
                }
            }
            if (_b == null) return@launch
            tiles = tiles.map { t ->
                val r = result[t.id] ?: return@map t
                resolved[t.id] = r.first
                t.copy(
                    icon = r.second,
                    status = if (r.first == null) getString(R.string.status_not_found) else null
                )
            }
            tileAdapter.submitList(tiles)
        }
    }

    private fun refreshUsb() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val vols = withContext(Dispatchers.IO) { Volumes.removable(ctx) }
            val binding = _b ?: return@launch
            binding.usbStatus.text = when (vols.size) {
                0 -> getString(R.string.usb_none)
                1 -> getString(
                    R.string.usb_one, vols[0].label,
                    Format.size(vols[0].freeBytes), Format.size(vols[0].totalBytes)
                )
                else -> getString(R.string.usb_many, vols.size)
            }
        }
    }

    private fun refreshRecents() {
        val binding = _b ?: return
        val cont = PlaybackStore.continueWatching().take(12).map { r ->
            val pos = PlaybackStore.position(r.entry.key)
            val dur = if (r.duration > 0) r.duration else r.entry.durationMs
            Item(
                id = r.entry.stableId,
                title = r.entry.title,
                subtitle = "${Format.duration(pos)} / ${Format.duration(dur)}",
                thumb = r.entry.thumb,
                iconRes = R.drawable.ic_video,
                progress = if (dur > 0) (pos * 100 / dur).toInt() else -1,
                payload = r.entry
            )
        }
        val contKeys = cont.map { it.id }.toSet()
        val recent = PlaybackStore.recents()
            .filter { it.entry.stableId !in contKeys }
            .take(15)
            .map { r ->
                Item(
                    id = r.entry.stableId,
                    title = r.entry.title,
                    subtitle = r.entry.artist ?: Format.duration(r.duration),
                    thumb = r.entry.thumb,
                    iconRes = iconFor(r.entry.kind),
                    payload = r.entry
                )
            }
        continueAdapter.submitList(cont)
        recentAdapter.submitList(recent)
        val cv = if (cont.isEmpty()) View.GONE else View.VISIBLE
        binding.continueTitle.visibility = cv
        binding.continueRow.visibility = cv
        val rv = if (recent.isEmpty()) View.GONE else View.VISIBLE
        binding.recentTitle.visibility = rv
        binding.recentRow.visibility = rv
    }

    private fun iconFor(kind: MediaKind) = when (kind) {
        MediaKind.VIDEO -> R.drawable.ic_video
        MediaKind.AUDIO -> R.drawable.ic_music
        MediaKind.IMAGE -> R.drawable.ic_image
    }

    // ------------------------------------------------------------------ actions

    private fun main() = requireActivity() as MainActivity

    private fun onTile(tile: Tile) {
        val svc = AppLauncher.services.firstOrNull { it.id == tile.id }
        if (svc != null) {
            val pkg = resolved[svc.id]
            if (pkg != null && AppLauncher.launch(requireContext(), pkg)) return
            showMissing(svc)
            return
        }
        when (tile.id) {
            "usb" -> main().navigate(BrowserFragment())
            "videos" -> main().navigate(MediaListFragment.newInstance(MediaKind.VIDEO))
            "music" -> main().navigate(MediaListFragment.newInstance(MediaKind.AUDIO))
            "pictures" -> main().navigate(MediaListFragment.newInstance(MediaKind.IMAGE))
            "apps" -> main().navigate(AppsFragment())
            "settings" -> main().navigate(SettingsFragment())
        }
    }

    private fun openEntry(item: Item) {
        val e = item.payload as? MediaEntry ?: return
        Open.media(requireActivity(), listOf(e), 0)
    }

    private fun showMissing(svc: StreamingService) {
        val name = getString(svc.labelRes)
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.app_missing_title, name))
            .setMessage(getString(R.string.app_missing_msg, name))
            .setPositiveButton(R.string.app_search_store) { _, _ ->
                if (!AppLauncher.openStoreSearch(requireContext(), name)) {
                    Toast.makeText(requireContext(), R.string.no_store, Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton(R.string.app_pick_installed) { _, _ -> pickApp(svc) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Lets the user say which installed app is "Watcho" etc. Remembered. */
    private fun pickApp(svc: StreamingService) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val apps: List<AppInfo> = withContext(Dispatchers.IO) { AppLauncher.installedApps(ctx, withIcons = false) }
            if (_b == null) return@launch
            val labels = apps.map { it.label }.toMutableList()
            labels += getString(R.string.app_reset)
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.app_pick_title, getString(svc.labelRes)))
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which == apps.size) {
                        com.tvhub.media.core.Prefs.setAppPackage(svc.id, null)
                        refreshApps()
                    } else {
                        val pkg = apps[which].packageName
                        com.tvhub.media.core.Prefs.setAppPackage(svc.id, pkg)
                        resolved[svc.id] = pkg
                        refreshApps()
                        if (!AppLauncher.launch(requireContext(), pkg)) {
                            Toast.makeText(requireContext(), R.string.launch_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Menu key on a YouTube/Watcho/Dodear tile: choose which installed app it opens. */
    override fun onMenuKey(): Boolean {
        val binding = _b ?: return false
        val f = binding.root.findFocus() ?: return false
        val vh = binding.tiles.findContainingViewHolder(f) ?: return false
        val tile = tiles.getOrNull(vh.bindingAdapterPosition) ?: return false
        val svc = AppLauncher.services.firstOrNull { it.id == tile.id } ?: return false
        pickApp(svc)
        return true
    }
}
