package com.tvhub.media.ui.media

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvhub.media.MainActivity
import com.tvhub.media.R
import com.tvhub.media.core.Format
import com.tvhub.media.core.Perms
import com.tvhub.media.core.Prefs
import com.tvhub.media.core.UsbMonitor
import com.tvhub.media.core.focusPosition
import com.tvhub.media.core.focusedPosition
import com.tvhub.media.data.MediaEntry
import com.tvhub.media.data.MediaKind
import com.tvhub.media.data.MediaRepository
import com.tvhub.media.data.PlaybackStore
import com.tvhub.media.data.Volumes
import com.tvhub.media.databinding.FragmentListBinding
import com.tvhub.media.player.AudioPlayerActivity
import com.tvhub.media.ui.common.Item
import com.tvhub.media.ui.common.MediaAdapter
import com.tvhub.media.ui.common.MenuKeyHandler
import com.tvhub.media.ui.common.Open
import com.tvhub.media.ui.common.Style
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaListViewModel(app: Application) : AndroidViewModel(app) {

    data class State(
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val items: List<MediaEntry> = emptyList(),
        val sort: Int = 0,
        val volume: String? = null,
        val volumeLabel: String? = null,
        val needsPermission: Boolean = false
    )

    private val _state = MutableStateFlow(State(sort = Prefs.sortMode))
    val state: StateFlow<State> = _state
    var kind: MediaKind = MediaKind.VIDEO
    var lastFocus = 0
    private var raw: List<MediaEntry> = emptyList()
    private var job: Job? = null

    fun load(force: Boolean = false) {
        if (_state.value.loaded && !force) return
        job?.cancel() // drop any older, slower scan
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, needsPermission = false) }
            val app = getApplication<Application>()
            val volId = _state.value.volume
            val vi = if (volId == null) null
            else withContext(Dispatchers.IO) { Volumes.list(app).firstOrNull { it.id == volId } }
            raw = MediaRepository.load(app, kind, vi?.mediaStoreName, vi?.dir?.path)
            val sorted = withContext(Dispatchers.Default) { MediaRepository.sort(raw, _state.value.sort) }
            _state.update { it.copy(loading = false, loaded = true, items = sorted) }
        }
    }

    fun permissionDenied() = _state.update { it.copy(loading = false, needsPermission = true) }

    fun nextSort() {
        val mode = (_state.value.sort + 1) % 3
        Prefs.sortMode = mode
        viewModelScope.launch {
            val sorted = withContext(Dispatchers.Default) { MediaRepository.sort(raw, mode) }
            _state.update { it.copy(sort = mode, items = sorted) }
        }
    }

    fun nextVolume() {
        viewModelScope.launch {
            val vols = withContext(Dispatchers.IO) {
                Volumes.list(getApplication()).filter { it.mounted && (it.mediaStoreName != null || it.dir != null) }
            }
            val choices = listOf<Pair<String?, String?>>(null to null) + vols.map { it.id to it.label }
            val cur = choices.indexOfFirst { it.first == _state.value.volume }.coerceAtLeast(0)
            val next = choices[(cur + 1) % choices.size]
            _state.update { it.copy(volume = next.first, volumeLabel = next.second) }
            lastFocus = 0
            load(force = true)
        }
    }
}

class MediaListFragment : Fragment(), MenuKeyHandler {

    companion object {
        fun newInstance(kind: MediaKind) = MediaListFragment().apply {
            arguments = bundleOf("kind" to kind.name)
        }
    }

    private val vm: MediaListViewModel by viewModels()
    private val kind: MediaKind by lazy { MediaKind.valueOf(requireArguments().getString("kind")!!) }

    private var _b: FragmentListBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: MediaAdapter
    private var needInitialFocus = true
    private var seenUsbEvent = -1L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vm.kind = kind
        needInitialFocus = true
        b.title.setText(
            when (kind) {
                MediaKind.VIDEO -> R.string.tile_videos
                MediaKind.AUDIO -> R.string.tile_music
                MediaKind.IMAGE -> R.string.tile_pictures
            }
        )

        val ctx = requireContext()
        when (kind) {
            MediaKind.AUDIO -> {
                adapter = MediaAdapter(Style.ROW) { _, pos -> open(pos) }
                b.list.layoutManager = LinearLayoutManager(ctx)
            }
            MediaKind.VIDEO -> {
                adapter = MediaAdapter(Style.CARD) { _, pos -> open(pos) }
                b.list.layoutManager = GridLayoutManager(ctx, 4)
            }
            MediaKind.IMAGE -> {
                adapter = MediaAdapter(Style.CARD) { _, pos -> open(pos) }
                b.list.layoutManager = GridLayoutManager(ctx, 5)
            }
        }
        b.list.adapter = adapter
        b.list.itemAnimator = null
        b.list.setHasFixedSize(true)

        listOf(b.btn1, b.btn2, b.btn3).forEach { it.visibility = View.VISIBLE }
        b.btn1.setOnClickListener { vm.nextSort() }
        b.btn2.setOnClickListener { vm.nextVolume() }
        b.btn3.setOnClickListener {
            if (vm.state.value.needsPermission) requestPermission() else vm.load(force = true)
        }
        if (kind == MediaKind.AUDIO) {
            b.btn4.visibility = View.VISIBLE
            b.btn4.setText(R.string.now_playing)
            b.btn4.setOnClickListener {
                startActivity(
                    Intent(requireContext(), AudioPlayerActivity::class.java)
                        .putExtra(AudioPlayerActivity.EXTRA_RESUME_ONLY, true)
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
        if (seenUsbEvent < 0) seenUsbEvent = UsbMonitor.events.value.time
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UsbMonitor.events.collect { ev ->
                    if (ev.time != 0L && ev.time != seenUsbEvent) {
                        seenUsbEvent = ev.time
                        // Give the system a moment to index a newly inserted drive.
                        if (ev.mounted) delay(2500)
                        vm.load(force = true)
                    }
                }
            }
        }

        requestPermission()
    }

    private fun requestPermission() {
        (requireActivity() as MainActivity).ensureMediaPermission { ok ->
            if (ok) vm.load() else vm.permissionDenied()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh resume-progress bars after coming back from the player.
        if (_b != null && vm.state.value.loaded) render(vm.state.value)
    }

    override fun onPause() {
        super.onPause()
        _b?.list?.focusedPosition()?.takeIf { it >= 0 }?.let { vm.lastFocus = it }
    }

    override fun onDestroyView() {
        _b?.list?.focusedPosition()?.takeIf { it >= 0 }?.let { vm.lastFocus = it }
        _b = null
        super.onDestroyView()
    }

    private fun open(position: Int) {
        vm.lastFocus = position
        Open.media(requireActivity(), vm.state.value.items, position)
    }

    private fun render(s: MediaListViewModel.State) {
        val binding = _b ?: return
        binding.progress.visibility = if (s.loading) View.VISIBLE else View.GONE
        binding.btn1.setText(
            when (s.sort) {
                1 -> R.string.sort_date
                2 -> R.string.sort_size
                else -> R.string.sort_name
            }
        )
        binding.btn2.text = if (s.volumeLabel == null) getString(R.string.source_all)
        else getString(R.string.source_one, s.volumeLabel)
        binding.btn3.setText(if (s.needsPermission) R.string.grant else R.string.refresh)
        binding.count.text = if (s.loaded) getString(R.string.items_count, s.items.size) else ""

        val message = when {
            s.needsPermission -> getString(R.string.perm_needed)
            s.loaded && !s.loading && s.items.isEmpty() -> getString(R.string.empty_media)
            !s.loaded && s.loading -> getString(R.string.loading)
            else -> null
        }
        binding.message.text = message ?: ""
        binding.message.visibility = if (message == null) View.GONE else View.VISIBLE

        val mapped = s.items.map { toItem(it) }
        adapter.submitList(mapped) {
            val bb = _b ?: return@submitList
            if (!needInitialFocus) return@submitList
            if (mapped.isNotEmpty()) {
                needInitialFocus = false
                bb.list.focusPosition(vm.lastFocus)
            } else if (s.loaded || s.needsPermission) {
                needInitialFocus = false
                bb.btn3.requestFocus()
            }
        }
    }

    private fun toItem(e: MediaEntry): Item = when (e.kind) {
        MediaKind.VIDEO -> {
            val pos = PlaybackStore.position(e.key)
            Item(
                id = e.stableId,
                title = e.displayName,
                subtitle = e.folder ?: "",
                badge = Format.duration(e.durationMs),
                thumb = e.thumb,
                iconRes = R.drawable.ic_video,
                progress = if (pos > 0 && e.durationMs > 0) (pos * 100 / e.durationMs).toInt().coerceIn(1, 100) else -1,
                payload = e
            )
        }
        MediaKind.AUDIO -> Item(
            id = e.stableId,
            title = e.title,
            subtitle = listOfNotNull(e.artist, e.album).joinToString(" · ").ifEmpty { e.folder ?: e.displayName },
            badge = Format.duration(e.durationMs),
            thumb = e.thumb,
            iconRes = R.drawable.ic_music,
            payload = e
        )
        MediaKind.IMAGE -> Item(
            id = e.stableId,
            title = e.displayName,
            subtitle = e.folder ?: "",
            thumb = e.thumb,
            iconRes = R.drawable.ic_image,
            payload = e
        )
    }

    /** Menu key = change sort order. */
    override fun onMenuKey(): Boolean {
        vm.nextSort()
        return true
    }

    @Suppress("unused")
    private fun hasPermission() = Perms.hasMedia(requireContext())
}
