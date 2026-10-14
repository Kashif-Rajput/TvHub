package com.tvhub.media.ui.settings

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import com.tvhub.media.BuildConfig
import com.tvhub.media.MainActivity
import com.tvhub.media.R
import com.tvhub.media.core.Focus
import com.tvhub.media.core.Format
import com.tvhub.media.core.Perms
import com.tvhub.media.core.Prefs
import com.tvhub.media.data.PlaybackStore
import com.tvhub.media.databinding.FragmentSettingsBinding
import com.tvhub.media.databinding.ItemSettingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private class Row(
        val titleRes: Int,
        val hintRes: Int? = null,
        val value: () -> String,
        val onClick: (() -> Unit)? = null
    )

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!
    private val rendered = ArrayList<Pair<Row, ItemSettingBinding>>()
    private var decoders: String = "…"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rendered.clear()
        rows().forEach { addRow(it) }
        rendered.firstOrNull()?.second?.root?.requestFocus()
        viewLifecycleOwner.lifecycleScope.launch {
            decoders = withContext(Dispatchers.IO) { decoderSummary() }
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh() // permissions may have changed in system settings
    }

    override fun onDestroyView() {
        rendered.clear()
        _b = null
        super.onDestroyView()
    }

    private fun on(v: Boolean) = getString(if (v) R.string.on else R.string.off)

    private fun rows(): List<Row> = listOf(
        Row(R.string.set_animations, value = { on(Prefs.animations) }) {
            Prefs.animations = !Prefs.animations
        },
        Row(R.string.set_video_size, value = { sizeName(Prefs.resizeMode) }) {
            Prefs.resizeMode = (Prefs.resizeMode + 1) % 3
        },
        Row(R.string.set_ffmpeg, value = { on(Prefs.ffmpegAudio) }) {
            Prefs.ffmpegAudio = !Prefs.ffmpegAudio
        },
        Row(R.string.set_tunnel, R.string.set_tunnel_hint, value = { on(Prefs.tunneling) }) {
            Prefs.tunneling = !Prefs.tunneling
        },
        Row(R.string.set_storage_perm, value = {
            getString(if (Perms.hasMedia(requireContext())) R.string.granted else R.string.not_granted)
        }) {
            (requireActivity() as MainActivity).ensureMediaPermission { ok ->
                if (!ok && !Perms.openAppSettings(requireContext())) toast(R.string.setting_unavailable)
                refresh()
            }
        },
        Row(R.string.set_all_files, value = {
            getString(if (Perms.hasAllFiles()) R.string.granted else R.string.not_granted)
        }) {
            if (!Perms.openAllFilesSettings(requireContext())) toast(R.string.setting_unavailable)
        },
        Row(R.string.set_launcher, R.string.set_launcher_hint, value = { on(isLauncherEnabled()) }) {
            toggleLauncher()
        },
        Row(R.string.set_clear, value = { "" }) {
            PlaybackStore.clear()
            requireContext().imageLoader.memoryCache?.clear()
            toast(R.string.cleared)
        },
        Row(R.string.set_device, value = { deviceSummary() }),
        Row(R.string.set_display, value = { displaySummary() }),
        Row(R.string.set_decoders, value = { decoders }),
        Row(R.string.set_version, value = { BuildConfig.VERSION_NAME })
    )

    private fun addRow(row: Row) {
        val rb = ItemSettingBinding.inflate(layoutInflater, b.rows, false)
        rb.title.setText(row.titleRes)
        if (row.hintRes != null) {
            rb.hint.setText(row.hintRes)
            rb.hint.visibility = View.VISIBLE
        }
        rb.value.text = row.value()
        Focus.attach(rb.root)
        rb.root.setOnClickListener {
            row.onClick?.invoke()
            refresh()
        }
        b.rows.addView(rb.root)
        rendered += row to rb
    }

    private fun refresh() {
        if (_b == null) return
        rendered.forEach { (row, rb) -> rb.value.text = row.value() }
    }

    private fun toast(res: Int) = Toast.makeText(requireContext(), res, Toast.LENGTH_LONG).show()

    private fun sizeName(mode: Int) = getString(
        when (mode) {
            1 -> R.string.size_fill
            2 -> R.string.size_zoom
            else -> R.string.size_fit
        }
    )

    // ------------------------------------------------------------ home screen (launcher) mode

    private fun homeAlias() = ComponentName(requireContext(), "com.tvhub.media.HomeAlias")

    private fun isLauncherEnabled(): Boolean =
        requireContext().packageManager.getComponentEnabledSetting(homeAlias()) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun toggleLauncher() {
        val enable = !isLauncherEnabled()
        requireContext().packageManager.setComponentEnabledSetting(
            homeAlias(),
            if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        // Let the user pick/restore the default home app where the TV supports it.
        Perms.tryStart(requireContext(), Intent(Settings.ACTION_HOME_SETTINGS))
        if (enable) toast(R.string.launcher_enabled_hint)
    }

    // ------------------------------------------------------------ diagnostics (useful for troubleshooting)

    private fun deviceSummary(): String {
        val am = requireContext().getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val low = if (am.isLowRamDevice) " · low-RAM mode" else ""
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · " +
            "RAM ${Format.size(mi.totalMem)}$low"
    }

    @Suppress("DEPRECATION")
    private fun displaySummary(): String {
        val d = requireActivity().display ?: return "?"
        val m = d.mode
        val hdr = d.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
        return "${m.physicalWidth}×${m.physicalHeight} @ ${m.refreshRate.toInt()} Hz" + if (hdr) " · HDR" else ""
    }

    private fun decoderSummary(): String {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        fun hw(mime: String) = infos.any { info ->
            info.isHardwareAccelerated && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        fun any(mime: String) = infos.any { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        val video = listOf(
            "H.264" to "video/avc",
            "H.265" to "video/hevc",
            "VP9" to "video/x-vnd.on2.vp9",
            "AV1" to "video/av01",
            "MPEG-2" to "video/mpeg2"
        ).joinToString("  ") { (n, m) -> "$n ${if (hw(m)) "✓" else "✗"}" }
        val audio = listOf(
            "AC3" to "audio/ac3",
            "E-AC3" to "audio/eac3",
            "DTS" to "audio/vnd.dts"
        ).joinToString("  ") { (n, m) -> "$n ${if (any(m)) "✓" else "✗"}" }
        return "$video\nAudio: $audio"
    }
}
