package dev.alder

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Jellyfin-first TV home. Stremio remains available as its own section and is never selected as an automatic fallback.
 */
class JellyfinHomeActivity : Screen() {
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var selectedPage = Page.HOME
    private var selectedView: JellyfinItem? = null
    private var generation = 0

    private enum class Page { HOME, LIBRARY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Profiles.active(this) == null) {
            startActivity(Intent(this, ProfilesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
            return
        }
        root = column(Gravity.START).apply {
            setBackgroundColor(BASE)
            setPadding(dp(34), dp(22), dp(34), dp(18))
        }
        root.addView(navigation(), LinearLayout.LayoutParams(-1, dp(48)))
        status = text(getString(R.string.provider_connecting), BODY, color = TEXT_2).apply {
            setPadding(dp(8), dp(18), dp(8), dp(18))
        }
        root.addView(status)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        body = column(Gravity.START)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadHome()
    }

    private fun navigation(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val entries = listOf(
            getString(R.string.tab_home) to { selectedPage = Page.HOME; selectedView = null; loadHome() },
            getString(R.string.provider_library) to { selectedPage = Page.LIBRARY; selectedView = null; loadLibrary() },
            getString(R.string.provider_stremio) to { startActivity(Intent(this@JellyfinHomeActivity, MainActivity::class.java)) },
            getString(R.string.provider_live_tv) to { startActivity(Intent(this@JellyfinHomeActivity, IptvActivity::class.java)) },
            getString(R.string.provider_search) to { startActivity(Intent(this@JellyfinHomeActivity, ProviderSearchActivity::class.java)) },
            getString(R.string.menu_settings) to { startActivity(Intent(this@JellyfinHomeActivity, SettingsActivity::class.java)) }
        )
        entries.forEachIndexed { index, entry ->
            val item = TextView(this@JellyfinHomeActivity).apply {
                text = entry.first
                textSize = BODY
                setTextColor(if (index == selectedPage.ordinal) TUNGSTEN else TEXT_2)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isFocusable = true
                rowFocus(8)
                setOnClickListener { entry.second() }
            }
            addView(item, LinearLayout.LayoutParams(-2, -1))
        }
    }

    private fun reset(message: String? = null) {
        generation++
        body.removeAllViews()
        status.text = message.orEmpty()
    }

    private fun loadHome() {
        reset(getString(R.string.provider_connecting))
        val token = generation
        if (Jellyfin.account(this) == null) {
            showOffline(getString(R.string.provider_jellyfin_not_connected))
            return
        }
        background({
            val resume = Jellyfin.resumeItems(applicationContext)
            val latest = runCatching { Jellyfin.views(applicationContext) }.getOrDefault(emptyList())
            val recentByView = latest.mapNotNull { view ->
                runCatching { view to Jellyfin.latestItems(applicationContext, view.id, 12) }.getOrNull()
            }
            Triple(resume, latest, recentByView)
        }) { result ->
            if (token != generation || isFinishing) return@background
            result.onSuccess { (resume, views, recent) ->
                status.text = getString(R.string.provider_jellyfin_connected, Jellyfin.account(this)?.username.orEmpty())
                body.removeAllViews()
                if (resume.isNotEmpty()) addSection(getString(R.string.provider_continue_watching), resume)
                if (recent.isNotEmpty()) {
                    recent.forEach { (view, items) ->
                        if (items.isNotEmpty()) addSection(view.name, items)
                    }
                } else if (views.isEmpty()) {
                    status.text = getString(R.string.provider_no_libraries)
                }
                if (body.childCount == 0) body.addView(text(getString(R.string.provider_no_items), BODY, color = TEXT_2))
                body.getChildAt(0)?.requestFocus()
            }.onFailure {
                showOffline(getString(R.string.provider_jellyfin_offline))
            }
        }
    }

    private fun loadLibrary() {
        reset(getString(R.string.provider_loading_library))
        val token = generation
        if (Jellyfin.account(this) == null) {
            showOffline(getString(R.string.provider_jellyfin_not_connected))
            return
        }
        background({ Jellyfin.views(applicationContext) }) { result ->
            if (token != generation || isFinishing) return@background
            result.onSuccess { views ->
                status.text = getString(R.string.provider_library)
                body.removeAllViews()
                if (selectedView == null) {
                    views.forEach { view ->
                        val row = text(view.name, 23f, color = TEXT).apply {
                            setPadding(dp(12), dp(15), dp(12), dp(15))
                            isFocusable = true
                            rowFocus(9)
                            setOnClickListener { selectedView = view; loadLibraryItems(view) }
                        }
                        body.addView(row, LinearLayout.LayoutParams(-1, -2))
                    }
                } else loadLibraryItems(selectedView!!)
                if (body.childCount == 0) body.addView(text(getString(R.string.provider_no_libraries), BODY, color = TEXT_2))
                body.getChildAt(0)?.requestFocus()
            }.onFailure { showOffline(getString(R.string.provider_jellyfin_offline)) }
        }
    }

    private fun loadLibraryItems(view: JellyfinItem) {
        reset(view.name)
        val token = generation
        background({ Jellyfin.items(applicationContext, view.id, listOf("Movie", "Series"), 200) }) { result ->
            if (token != generation || isFinishing) return@background
            result.onSuccess { items ->
                body.removeAllViews()
                addSection(view.name, items)
                body.getChildAt(0)?.requestFocus()
            }.onFailure { showOffline(getString(R.string.provider_jellyfin_offline)) }
        }
    }

    private fun addSection(title: String, items: List<JellyfinItem>) {
        if (items.isEmpty()) return
        body.addView(text(title, SERIF_TITLE, serif = true).apply {
            setPadding(dp(4), dp(12), 0, dp(8))
        })
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        items.forEach { item ->
            val label = TextView(this).apply {
                text = item.name
                textSize = SMALL
                setTextColor(TEXT)
                maxLines = 2
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = glass(9)
                isFocusable = true
                rowFocus(8)
                setOnClickListener { play(item) }
            }
            row.addView(label, LinearLayout.LayoutParams(dp(170), dp(76)).apply { marginEnd = dp(8) })
        }
        val horizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        body.addView(horizontal, LinearLayout.LayoutParams(-1, dp(88)))
    }

    private fun play(item: JellyfinItem) {
        startActivity(Intent(this, ProviderPlayerActivity::class.java)
            .putExtra("jellyfinItemId", item.id).putExtra("title", item.name))
    }

    private fun showOffline(message: String) {
        status.text = message
        body.removeAllViews()
        val retry = text(getString(R.string.provider_retry), BODY, color = TUNGSTEN).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isFocusable = true
            rowFocus(8)
            setOnClickListener { if (selectedPage == Page.HOME) loadHome() else loadLibrary() }
        }
        body.addView(retry)
        body.addView(text(getString(R.string.provider_offline_navigation), SMALL, color = TEXT_2).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
        })
        retry.requestFocus()
    }
}
