package com.tvhub.media.ui.browser

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvhub.media.MainActivity
import com.tvhub.media.R
import com.tvhub.media.core.Format
import com.tvhub.media.core.Prefs
import com.tvhub.media.core.UsbMonitor
import com.tvhub.media.core.focusPosition
import com.tvhub.media.core.focusedPosition
import com.tvhub.media.data.MediaKind
import com.tvhub.media.data.Node
import com.tvhub.media.data.NodeLister
import com.tvhub.media.data.Thumb
import com.tvhub.media.data.VolumeInfo
import com.tvhub.media.data.Volumes
import com.tvhub.media.databinding.FragmentListBinding
import com.tvhub.media.ui.common.Item
import com.tvhub.media.ui.common.MediaAdapter
import com.tvhub.media.ui.common.Open
import com.tvhub.media.ui.common.Style
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    sealed class Row {
        data class Vol(val v: VolumeInfo) : Row()
        data class Entry(val n: Node) : Row()
    }

    /** node == null means "list of drives". */
    class Level(val node: Node?, var focus: Int = 0)

    data class State(
        val rows: List<Row> = emptyList(),
        val loading: Boolean = false,
        val unreadable: Boolean = false,
        val version: Int = 0
    )

    val stack = ArrayList<Level>()
    val state = MutableStateFlow(State())
    private var job: Job? = null

    val depth: Int get() = stack.size

    fun ensureStarted() {
        if (stack.isEmpty()) showVolumes()
    }

    fun showVolumes() {
        stack.clear()
        stack += Level(null)
        reload()
    }

    fun open(node: Node, focusHere: Int) {
        if (focusHere >= 0) stack.lastOrNull()?.focus = focusHere
        stack += Level(node)
        reload()
    }

    fun up(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        reload()
        return true
    }

    fun rememberFocus(pos: Int) {
        if (pos >= 0) stack.lastOrNull()?.focus = pos
    }

    /** Re-lists the current level. Returns via state; unreadable=true if the folder vanished. */
    fun reload() {
        val level = stack.lastOrNull() ?: return
        job?.cancel()
        job = viewModelScope.launch {
            state.value = state.value.copy(loading = true, unreadable = false)
            val ctx = getApplication<Application>()
            val node = level.node
            val rows: List<Row>?
            if (node == null) {
                rows = withContext(Dispatchers.IO) { Volumes.list(ctx).filter { it.mounted } }.map { Row.Vol(it) }
            } else {
                rows = NodeLister.list(ctx, node)?.map { Row.Entry(it) }
            }
            state.value = State(
                rows = rows ?: emptyList(),
                loading = false,
                unreadable = rows == null,
                version = state.value.version + 1
            )
        }
    }

    fun breadcrumb(): String = stack.mapNotNull { it.node?.name }.joinToString("  ›  ")
}

class BrowserFragment : Fragment() {

    private val vm: BrowserViewModel by viewModels()
    private var _b: FragmentListBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: MediaAdapter
    private var renderedVersion = -1
    private var seenUsbEvent = -1L
    private var pendingVolume: VolumeInfo? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            rememberFocus()
            if (!vm.up()) {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private val treeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val vol = pendingVolume
        pendingVolume = null
        val uri: Uri = res.data?.data ?: return@registerForActivityResult
        if (res.resultCode != Activity.RESULT_OK || vol == null) return@registerForActivityResult
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Prefs.setTreeUri(vol.id, uri.toString())
        vm.open(NodeLister.treeRoot(uri, vol.label), currentFocus())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        renderedVersion = -1
        b.title.setText(R.string.browser_title)
        adapter = MediaAdapter(Style.ROW) { item, pos -> onItem(item, pos) }
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        b.list.itemAnimator = null
        b.list.setHasFixedSize(true)

        b.btn1.visibility = View.VISIBLE
        b.btn1.setText(R.string.refresh)
        b.btn1.setOnClickListener { vm.reload() }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
        if (seenUsbEvent < 0) seenUsbEvent = UsbMonitor.events.value.time
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UsbMonitor.events.collect { ev ->
                    if (ev.time == 0L || ev.time == seenUsbEvent) return@collect
                    seenUsbEvent = ev.time
                    if (vm.depth <= 1) vm.reload()
                    else if (!ev.mounted) checkStillThere()
                }
            }
        }

        (requireActivity() as MainActivity).ensureMediaPermission { vm.ensureStarted() }
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

    private fun currentFocus(): Int = _b?.list?.focusedPosition() ?: -1

    private fun rememberFocus() = vm.rememberFocus(currentFocus())

    /** After a removal event, see if the folder we're in still exists. */
    private fun checkStillThere() {
        val node = vm.stack.lastOrNull()?.node ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = NodeLister.list(requireContext().applicationContext, node) != null
            if (!ok && _b != null) {
                Toast.makeText(requireContext(), R.string.drive_removed, Toast.LENGTH_SHORT).show()
                vm.showVolumes()
            }
        }
    }

    private fun render(s: BrowserViewModel.State) {
        val binding = _b ?: return
        backCallback.isEnabled = vm.depth > 1
        binding.progress.visibility = if (s.loading) View.VISIBLE else View.GONE
        binding.count.text = vm.breadcrumb()
        val msg = when {
            s.loading -> null
            s.unreadable -> getString(R.string.cannot_read)
            s.rows.isEmpty() && vm.depth > 1 -> getString(R.string.empty_folder)
            else -> null
        }
        binding.message.text = msg ?: ""
        binding.message.visibility = if (msg == null) View.GONE else View.VISIBLE

        val items = s.rows.map { toItem(it) }
        val version = s.version
        adapter.submitList(items) {
            val bb = _b ?: return@submitList
            if (renderedVersion == version || s.loading) return@submitList
            renderedVersion = version
            val focus = vm.stack.lastOrNull()?.focus ?: 0
            if (items.isNotEmpty()) bb.list.focusPosition(focus) else bb.btn1.requestFocus()
        }
    }

    private fun toItem(row: BrowserViewModel.Row): Item = when (row) {
        is BrowserViewModel.Row.Vol -> {
            val v = row.v
            Item(
                id = ("vol:" + v.id).hashCode().toLong(),
                title = if (v.primary) getString(R.string.internal_storage) else v.label,
                subtitle = if (v.totalBytes > 0) "${Format.size(v.freeBytes)} free of ${Format.size(v.totalBytes)}" else "",
                iconRes = if (v.removable) R.drawable.ic_usb else R.drawable.ic_drive,
                payload = v
            )
        }
        is BrowserViewModel.Row.Entry -> {
            val n = row.n
            if (n.isDir) {
                Item(n.stableId, n.name, getString(R.string.folder), iconRes = R.drawable.ic_folder, payload = n)
            } else {
                val kind = n.kind
                Item(
                    id = n.stableId,
                    title = n.name,
                    subtitle = listOf(Format.size(n.size), Format.date(n.modified)).filter { it.isNotEmpty() }.joinToString(" · "),
                    thumb = kind?.let { Thumb(n.uri, n.file?.path, it, n.uri.toString()) },
                    iconRes = when (kind) {
                        MediaKind.VIDEO -> R.drawable.ic_video
                        MediaKind.AUDIO -> R.drawable.ic_music
                        MediaKind.IMAGE -> R.drawable.ic_image
                        null -> R.drawable.ic_file
                    },
                    payload = n
                )
            }
        }
    }

    private fun onItem(item: Item, pos: Int) {
        when (val p = item.payload) {
            is VolumeInfo -> openVolume(p, pos)
            is Node -> {
                if (p.isDir) {
                    vm.open(p, pos)
                    return
                }
                val kind = p.kind
                if (kind == null) {
                    Toast.makeText(requireContext(), R.string.unsupported_file, Toast.LENGTH_SHORT).show()
                    return
                }
                vm.rememberFocus(pos)
                val siblings = vm.state.value.rows
                    .mapNotNull { (it as? BrowserViewModel.Row.Entry)?.n }
                    .filter { !it.isDir && it.kind == kind }
                val entries = siblings.mapNotNull { it.toEntry() }
                val index = siblings.indexOfFirst { it.stableId == p.stableId }.coerceAtLeast(0)
                Open.media(requireActivity(), entries, index)
            }
        }
    }

    /**
     * 1) Direct path access (fast) when Android lets us read the drive.
     * 2) A folder permission the user granted earlier.
     * 3) Ask the user to allow the drive in the system folder picker.
     */
    private fun openVolume(v: VolumeInfo, pos: Int) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val dir = v.dir
            val readable = dir != null && withContext(Dispatchers.IO) { dir.listFiles() != null }
            if (_b == null) return@launch
            val label = if (v.primary) getString(R.string.internal_storage) else v.label
            if (readable && dir != null) {
                vm.open(NodeLister.fileRoot(dir, label), pos)
                return@launch
            }
            val saved = Prefs.treeUri(v.id)?.let { Uri.parse(it) }
            if (saved != null && ctx.contentResolver.persistedUriPermissions.any { it.uri == saved && it.isReadPermission }) {
                vm.open(NodeLister.treeRoot(saved, label), pos)
                return@launch
            }
            vm.rememberFocus(pos)
            pendingVolume = v
            try {
                treeLauncher.launch(v.volume.createOpenDocumentTreeIntent())
            } catch (e: ActivityNotFoundException) {
                pendingVolume = null
                Toast.makeText(requireContext(), R.string.no_picker, Toast.LENGTH_LONG).show()
            }
        }
    }
}
