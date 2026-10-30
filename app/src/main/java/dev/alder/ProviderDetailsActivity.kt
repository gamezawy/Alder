package dev.alder

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ProviderDetailsActivity : Screen() {
    private var item: JellyfinItem? = null
    private var loadedEpisodes: List<JellyfinItem> = emptyList()
    private lateinit var status: TextView
    private lateinit var poster: ImageView
    private lateinit var actions: LinearLayout
    private lateinit var episodesPanel: LinearLayout
    private lateinit var playAction: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val itemId = intent.getStringExtra("jellyfinItemId").orEmpty()
        if (itemId.isBlank()) { finish(); return }
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF211A15.toInt(), BASE, BASE))
        }
        val layout = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), dp(34), dp(48), dp(34))
        }
        poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x33222222)
        }
        layout.addView(poster, LinearLayout.LayoutParams(dp(250), dp(370)))
        val details = column(Gravity.START).apply {
            setPadding(dp(34), 0, 0, 0)
        }
        status = text(getString(R.string.provider_connecting), SMALL, color = TEXT_2)
        details.addView(status)
        actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(details)
        }
        layout.addView(scroll, LinearLayout.LayoutParams(0, dp(420), 1f))
        root.addView(layout, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        background({ Jellyfin.item(applicationContext, itemId) }) { result ->
            result.onSuccess { loaded ->
                item = loaded
                status.text = listOfNotNull(
                    loaded.year?.toString(),
                    loaded.officialRating,
                    loaded.communityRating?.let { String.format(java.util.Locale.US, "%.1f", it) },
                    loaded.genres.take(3).joinToString(" · ").takeIf { it.isNotBlank() }
                ).joinToString("   ·   ")
                details.removeAllViews()
                details.addView(text(loaded.name, SERIF_HERO, serif = true).onArt().apply { maxLines = 2 })
                if (loaded.tagline != null) details.addView(text(loaded.tagline, BODY, color = TEXT_2).marginTop(10))
                if (status.text.isNotBlank()) details.addView(text(status.text.toString(), SMALL, color = TEXT_2).marginTop(12))
                if (loaded.overview.isNotBlank()) details.addView(text(loaded.overview, BODY, color = TEXT).apply {
                    setLineSpacing(dp(3).toFloat(), 1f)
                }.marginTop(18))
                details.addView(actions, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(24) })
                actions.removeAllViews()
                playAction = action(getString(R.string.action_play)) { playSelected(loaded) }
                actions.addView(playAction)
                if (loaded.type.equals("Series", true)) {
                    episodesPanel = column(Gravity.START)
                    details.addView(episodesPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
                    playAction.isEnabled = false
                    playAction.alpha = 0.45f
                    loadEpisodes(loaded)
                }
                actions.addView(action(getString(R.string.watch_status_title)) { chooseStatus(loaded) }.apply {
                    layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(12) }
                })
                Images.load(Jellyfin.imageUrl(this, loaded.id, loaded.imageTag, 800), poster, dp(500))
                actions.getChildAt(0)?.requestFocus()
            }.onFailure {
                status.text = getString(R.string.provider_jellyfin_offline)
            }
        }
    }

    private fun loadEpisodes(series: JellyfinItem) {
        episodesPanel.removeAllViews()
        episodesPanel.addView(caps(R.string.provider_episodes, TUNGSTEN))
        episodesPanel.addView(text(getString(R.string.provider_loading_episodes), SMALL, color = TEXT_2).marginTop(8))
        background({ Jellyfin.episodes(applicationContext, series.id) }) { result ->
            if (isFinishing || isDestroyed) return@background
            result.onSuccess { episodes ->
                loadedEpisodes = episodes
                episodesPanel.removeAllViews()
                episodesPanel.addView(caps(R.string.provider_episodes, TUNGSTEN))
                if (episodes.isEmpty()) {
                    episodesPanel.addView(text(getString(R.string.provider_no_episodes), SMALL, color = TEXT_2).marginTop(8))
                    return@onSuccess
                }
                playAction.isEnabled = true
                playAction.alpha = 1f
                episodes.groupBy { it.seasonNumber ?: 0 }.toSortedMap().forEach { (season, seasonEpisodes) ->
                    val heading = if (season > 0) getString(R.string.provider_season_number, season) else getString(R.string.provider_specials)
                    episodesPanel.addView(text(heading, BODY, color = TEXT).marginTop(16))
                    seasonEpisodes.forEach { episode ->
                        val number = episode.episodeNumber?.let { String.format(java.util.Locale.ROOT, "%02d", it) }
                        val prefix = listOfNotNull(season.takeIf { it > 0 }?.let { "S%02d".format(it) }, number?.let { "E$it" }).joinToString("")
                        val label = listOfNotNull(prefix.takeIf { it.isNotBlank() }, episode.name).joinToString("  ·  ")
                        val row = text(label, SMALL, color = if (episode.played) TEXT_3 else TEXT).apply {
                            setPadding(dp(12), dp(10), dp(12), dp(10))
                            isFocusable = true
                            rowFocus(8)
                            setOnClickListener { playEpisode(series, episode) }
                        }
                        episodesPanel.addView(row, LinearLayout.LayoutParams(-1, -2))
                    }
                }
            }.onFailure {
                episodesPanel.removeAllViews()
                episodesPanel.addView(text(getString(R.string.provider_episodes_unavailable), SMALL, color = TEXT_2))
            }
        }
    }

    private fun playSelected(selected: JellyfinItem) {
        if (!selected.type.equals("Series", true)) {
            val isEpisode = selected.type.equals("Episode", true)
            startActivity(Intent(this, ProviderPlayerActivity::class.java)
                .putExtra("jellyfinItemId", selected.id)
                .putExtra("title", selected.seriesName ?: selected.name)
                .putExtra("episodeTitle", if (isEpisode) listOfNotNull(selected.seasonNumber?.let { "S%02d".format(it) }, selected.episodeNumber?.let { "E%02d".format(it) }, selected.name).joinToString("  ·  ") else "")
                .putExtra("seriesId", selected.seriesId)
                .putExtra("resumeAtMs", selected.playbackPositionTicks / 10_000L))
            return
        }
        val episode = loadedEpisodes.firstOrNull { it.playbackPositionTicks > 0L && !it.played }
            ?: loadedEpisodes.firstOrNull { !it.played }
            ?: loadedEpisodes.firstOrNull()
        if (episode != null) playEpisode(selected, episode)
    }

    private fun playEpisode(series: JellyfinItem, episode: JellyfinItem) {
        startActivity(Intent(this, ProviderPlayerActivity::class.java)
            .putExtra("jellyfinItemId", episode.id)
            .putExtra("title", series.name)
            .putExtra("seriesId", series.id)
            .putExtra("episodeTitle", listOfNotNull(episode.seasonNumber?.let { "S%02d".format(it) }, episode.episodeNumber?.let { "E%02d".format(it) }, episode.name).joinToString("  ·  "))
            .putExtra("resumeAtMs", episode.playbackPositionTicks / 10_000L))
    }

    private fun action(label: String, click: () -> Unit) = text(label, BODY, color = TUNGSTEN).apply {
        setPadding(dp(16), dp(10), dp(16), dp(10))
        isFocusable = true
        rowFocus(8)
        setOnClickListener { click() }
    }

    private fun chooseStatus(loaded: JellyfinItem) {
        val statuses = WatchStatus.entries
        val labels = statuses.map { status ->
            getString(when (status) {
                WatchStatus.NONE -> R.string.watch_status_none
                WatchStatus.FAVORITE -> R.string.watch_status_favorite
                WatchStatus.WATCHING -> R.string.watch_status_watching
                WatchStatus.ON_HOLD -> R.string.watch_status_on_hold
                WatchStatus.DROPPED -> R.string.watch_status_dropped
                WatchStatus.WATCHED -> R.string.watch_status_watched
            })
        }
        choose(getString(R.string.watch_status_title), labels, loaded.name,
            checked = WatchStatuses.get(this, "jellyfin", loaded.id).ordinal) { index ->
            WatchStatuses.set(this, "jellyfin", loaded.id, statuses[index])
        }
    }
}
