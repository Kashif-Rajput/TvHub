package com.tvhub.media.ui.youtube

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvhub.media.R
import com.tvhub.media.core.Focus
import com.tvhub.media.core.Prefs
import com.tvhub.media.core.YtPlaylist
import com.tvhub.media.core.focusPosition
import com.tvhub.media.data.YouTubeApi
import com.tvhub.media.data.YtCategories
import com.tvhub.media.data.YtCategory
import com.tvhub.media.data.YtException
import com.tvhub.media.data.YtQuery
import com.tvhub.media.data.YtTab
import com.tvhub.media.data.YtVideo
import com.tvhub.media.databinding.FragmentYoutubeBinding
import com.tvhub.media.player.YouTubePlayerActivity
import com.tvhub.media.ui.common.MenuKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Two screens in one:
 *  - MODE_YOUTUBE: search box + a few sections (Trending, Music, Live News...)
 *  - MODE_MOVIES:  Watcho-style tabs (Movies / Videos / TV Channels). Each tab has its own
 *                  search box and sections; Movies also has year buttons (Latest, 2026, 2025...).
 * Results appear below; OK on a video plays it inside TV Hub.
 */
class YouTubeFragment : Fragment(), MenuKeyHandler {

    private var _b: FragmentYoutubeBinding? = null
    private val b get() = _b!!

    private val mode get() = arguments?.getString(ARG_MODE) ?: MODE_YOUTUBE
    private val isMovies get() = mode == MODE_MOVIES

    private lateinit var chipAdapter: ChipAdapter
    private var yearAdapter: ChipAdapter? = null
    private lateinit var videoAdapter: YtVideoAdapter

    // Survives view re-creation while the fragment sits on the back stack.
    private var tabIndex = 0
    private var selectedChip: String? = null
    private var selectedYear: String = YtCategories.YEAR_LATEST
    /** Text the user searched for in the current tab; null = a section is showing. */
    private var activeSearch: String? = null
    private val searchTextPerTab = HashMap<Int, String>()

    private var query: YtQuery? = null
    private var label: String = ""
    private val videos = ArrayList<YtVideo>()
    private var nextToken: String? = null
    private var lastFocusPos = -1
    private var loadJob: Job? = null
    private var loading = false
    private var generation = 0

    private val tab: YtTab? get() = if (isMovies) YtCategories.moviesTabs[tabIndex] else null
    private val categories: List<YtCategory> get() = tab?.categories ?: YtCategories.youtube

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank() && _b != null) {
            b.search.setText(text)
            runSearch(text)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentYoutubeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        b.title.setText(if (isMovies) R.string.tile_movies else R.string.tile_youtube)

        setupSearch()

        // ---- tabs (Movies & TV only)
        b.tabs.visibility = if (isMovies) View.VISIBLE else View.GONE
        if (isMovies) {
            tabViews().forEachIndexed { i, tv ->
                tv.text = YtCategories.moviesTabs[i].label
                Focus.attach(tv)
                tv.setOnClickListener { if (i != tabIndex) openTab(i) }
            }
        }

        // ---- year buttons (Movies tab only)
        b.years.layoutManager = LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false)
        b.years.itemAnimator = null

        // ---- section chips
        b.chips.layoutManager = LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false)
        b.chips.itemAnimator = null

        // ---- results grid
        videoAdapter = YtVideoAdapter { _, pos -> play(pos) }
        val glm = GridLayoutManager(ctx, 4)
        b.list.layoutManager = glm
        b.list.adapter = videoAdapter
        b.list.itemAnimator = null
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (glm.findLastVisibleItemPosition() >= videos.size - 8) loadMore()
            }
        })

        bindTabUi()
        videoAdapter.submitList(videos.toList())

        if (!YouTubeApi.hasKey()) {
            showMessage(getString(R.string.yt_no_key))
        } else if (query == null) {
            // First open: show the first section straight away (latest videos).
            categories.firstOrNull()?.let { openCategory(it) }
        } else {
            updateSubtitle()
            if (videos.isEmpty() && !loading) showMessage(getString(R.string.yt_no_results))
        }

        b.root.post {
            if (_b == null) return@post
            when {
                lastFocusPos >= 0 && videos.isNotEmpty() -> b.list.focusPosition(lastFocusPos)
                isMovies -> tabViews()[tabIndex].requestFocus()
                else -> b.search.requestFocus()
            }
        }
    }

    override fun onDestroyView() {
        _b?.let { binding ->
            val f = binding.list.focusedChild
            lastFocusPos = if (f != null) binding.list.getChildAdapterPosition(f) else -1
            searchTextPerTab[tabIndex] = binding.search.text?.toString().orEmpty()
        }
        _b = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ tabs

    private fun tabViews(): List<TextView> = listOf(b.tab1, b.tab2, b.tab3)

    private fun openTab(i: Int) {
        val binding = _b ?: return
        searchTextPerTab[tabIndex] = binding.search.text?.toString().orEmpty()
        tabIndex = i
        activeSearch = null
        selectedChip = null
        selectedYear = YtCategories.YEAR_LATEST
        binding.search.setText(searchTextPerTab[i].orEmpty())
        bindTabUi()
        categories.firstOrNull()?.let { openCategory(it) }
    }

    /** Shows the right search hint, year row and section chips for the current tab. */
    private fun bindTabUi() {
        val binding = _b ?: return
        val ctx = requireContext()
        val t = tab
        if (isMovies) {
            tabViews().forEachIndexed { i, tv ->
                tv.setTextColor(ContextCompat.getColor(ctx, if (i == tabIndex) R.color.accent else R.color.text))
            }
        }
        binding.search.setHint(
            when (t?.id) {
                "movies" -> R.string.mt_search_movies
                "videos" -> R.string.mt_search_videos
                "tv" -> R.string.mt_search_tv
                else -> R.string.yt_search_hint
            }
        )

        if (t?.hasYears == true) {
            val ya = ChipAdapter(YtCategories.years(getString(R.string.mt_latest))) { y, _ -> onYear(y.id) }
            ya.selectedId = selectedYear
            yearAdapter = ya
            binding.years.adapter = ya
            binding.years.visibility = View.VISIBLE
        } else {
            yearAdapter = null
            binding.years.adapter = null
            binding.years.visibility = View.GONE
        }

        chipAdapter = ChipAdapter(categories) { cat, _ -> openCategory(cat) }
        chipAdapter.selectedId = if (activeSearch == null) selectedChip else null
        binding.chips.adapter = chipAdapter
    }

    private fun onYear(id: String) {
        selectedYear = id
        yearAdapter?.selectedId = id
        refreshCurrent()
    }

    // ------------------------------------------------------------------ search box

    private fun setupSearch() {
        val et = b.search
        if (et.text.isNullOrEmpty()) {
            et.setText(if (isMovies) searchTextPerTab[tabIndex].orEmpty() else Prefs.ytLastSearch)
        }
        Focus.attach(b.btnSearch)
        Focus.attach(b.btnVoice)
        Focus.attach(b.btnApp)
        b.btnApp.visibility = if (isMovies) View.GONE else View.VISIBLE

        et.setOnEditorActionListener { v, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO || enter
            ) {
                hideIme(v)
                runSearch(v.text.toString())
                true
            } else {
                false
            }
        }
        // On TV, OK on the box opens the on-screen keyboard.
        et.setOnKeyListener { v, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (event.action == KeyEvent.ACTION_UP) showIme(v)
                true
            } else {
                false
            }
        }
        b.btnSearch.setOnClickListener { runSearch(et.text.toString()) }

        val voice = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val hasVoice = runCatching { voice.resolveActivity(requireContext().packageManager) != null }.getOrDefault(false)
        b.btnVoice.visibility = if (hasVoice) View.VISIBLE else View.GONE
        b.btnVoice.setOnClickListener {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, b.search.hint?.toString().orEmpty())
            try {
                voiceLauncher.launch(i)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.yt_voice_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        b.btnApp.setOnClickListener {
            if (!YouTubePlayerActivity.openInYouTubeApp(requireContext(), null)) {
                Toast.makeText(requireContext(), R.string.yt_app_missing, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showIme(v: View) {
        v.requestFocus()
        ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
            ?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideIme(v: View) {
        ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun runSearch(text: String) {
        val q = text.trim()
        if (q.isEmpty()) {
            Toast.makeText(requireContext(), R.string.yt_type_something, Toast.LENGTH_SHORT).show()
            return
        }
        if (isMovies) searchTextPerTab[tabIndex] = q else Prefs.ytLastSearch = q
        activeSearch = q
        selectedChip = null
        if (_b != null) chipAdapter.selectedId = null
        refreshCurrent(focusResults = true)
    }

    // ------------------------------------------------------------------ sections

    private fun openCategory(cat: YtCategory) {
        selectedChip = cat.id
        activeSearch = null
        if (_b != null) chipAdapter.selectedId = cat.id
        refreshCurrent()
    }

    /** Builds the query from: tab + (search text OR section) + year, then loads it. */
    private fun refreshCurrent(focusResults: Boolean = false) {
        val t = tab
        val search = activeSearch
        val year = if (t?.hasYears == true && selectedYear != YtCategories.YEAR_LATEST) selectedYear else null
        val yearText = if (year != null) " $year" else ""
        val yearLabel = when {
            t?.hasYears != true -> ""
            year != null -> " · $year"
            search == null -> " · " + getString(R.string.mt_latest)
            else -> ""
        }

        if (search != null) {
            val q = if (t == null) {
                YtQuery(q = search)
            } else {
                YtQuery(
                    q = search + t.searchSuffix + yearText,
                    live = t.searchLive,
                    duration = t.searchDuration
                )
            }
            start(q, getString(R.string.yt_results_for, search) + yearLabel, focusResults)
            return
        }

        val cat = categories.firstOrNull { it.id == selectedChip } ?: categories.firstOrNull() ?: return
        selectedChip = cat.id
        val base = cat.query
        val q = if (t?.hasYears == true) {
            // "Latest" = recently uploaded. A year = that year's movies (any upload date).
            base.copy(q = (base.q ?: "") + yearText, recentDays = if (year == null) base.recentDays else null)
        } else {
            base
        }
        start(q, cat.label + yearLabel, focusResults)
    }

    private fun start(q: YtQuery, title: String, focusResults: Boolean) {
        if (!YouTubeApi.hasKey()) {
            showMessage(getString(R.string.yt_no_key))
            return
        }
        loadJob?.cancel()
        query = q
        label = title
        videos.clear()
        nextToken = null
        lastFocusPos = -1
        if (_b != null) videoAdapter.submitList(emptyList())
        updateSubtitle()
        load(null, focusResults)
    }

    private fun loadMore() {
        val q = query ?: return
        val token = nextToken ?: return
        if (loading) return
        if (q.live && videos.size >= 72) return // live lists get repetitive; save quota
        load(token, false)
    }

    private fun load(pageToken: String?, focusResults: Boolean) {
        val q = query ?: return
        val myGen = ++generation
        loading = true
        setBusy(true)
        if (pageToken == null) hideMessage()
        loadJob = lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { YouTubeApi.load(q, pageToken) }
                if (myGen != generation) return@launch
                val known = videos.mapTo(HashSet()) { it.id }
                videos += page.items.filter { known.add(it.id) }
                nextToken = page.nextToken
                if (_b == null) return@launch
                videoAdapter.submitList(videos.toList()) {
                    if (focusResults && videos.isNotEmpty() && _b != null) b.list.focusPosition(0)
                }
                if (videos.isEmpty()) showMessage(getString(R.string.yt_no_results)) else hideMessage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (myGen == generation && _b != null) {
                    val ye = e as? YtException ?: YtException(YtException.Kind.OTHER, e.message ?: e.javaClass.simpleName)
                    if (videos.isEmpty()) showMessage(errorText(ye))
                    else Toast.makeText(requireContext(), errorText(ye), Toast.LENGTH_LONG).show()
                }
            } finally {
                if (myGen == generation) {
                    loading = false
                    if (_b != null) {
                        setBusy(false)
                        updateSubtitle()
                    }
                }
            }
        }
    }

    private fun errorText(e: YtException): String = getString(
        when (e.kind) {
            YtException.Kind.NO_KEY -> R.string.yt_no_key
            YtException.Kind.QUOTA -> R.string.yt_err_quota
            YtException.Kind.BAD_KEY -> R.string.yt_err_key
            YtException.Kind.NETWORK -> R.string.yt_err_network
            YtException.Kind.OTHER -> R.string.yt_err_other
        }
    ) + if (e.kind == YtException.Kind.OTHER) "\n(${e.message})" else ""

    // ------------------------------------------------------------------ play

    private fun play(pos: Int) {
        if (videos.isEmpty()) return
        lastFocusPos = pos
        YtPlaylist.set(videos, pos)
        startActivity(Intent(requireContext(), YouTubePlayerActivity::class.java))
    }

    /** Menu key on a video: play here or open in the official YouTube app. */
    override fun onMenuKey(): Boolean {
        val binding = _b ?: return false
        val f = binding.list.focusedChild ?: return false
        val pos = binding.list.getChildAdapterPosition(f)
        val v = videos.getOrNull(pos) ?: return false
        AlertDialog.Builder(requireContext())
            .setTitle(v.title)
            .setItems(arrayOf(getString(R.string.yt_play_here), getString(R.string.yt_open_in_app))) { _, which ->
                if (which == 0) {
                    play(pos)
                } else if (!YouTubePlayerActivity.openInYouTubeApp(requireContext(), v.id)) {
                    Toast.makeText(requireContext(), R.string.yt_app_missing, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }

    // ------------------------------------------------------------------ ui helpers

    private fun updateSubtitle() {
        val binding = _b ?: return
        binding.subtitle.text = when {
            label.isEmpty() -> ""
            videos.isEmpty() -> label
            else -> "$label · ${getString(R.string.items_count, videos.size)}"
        }
    }

    private fun setBusy(busy: Boolean) {
        _b?.progress?.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showMessage(text: String) {
        val binding = _b ?: return
        binding.message.text = text
        binding.message.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        _b?.message?.visibility = View.GONE
    }

    companion object {
        const val MODE_YOUTUBE = "youtube"
        const val MODE_MOVIES = "movies"
        private const val ARG_MODE = "mode"

        fun newInstance(mode: String) = YouTubeFragment().apply {
            arguments = Bundle().apply { putString(ARG_MODE, mode) }
        }
    }
}
