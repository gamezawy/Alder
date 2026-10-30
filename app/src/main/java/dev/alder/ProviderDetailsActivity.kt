package dev.alder

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.util.Locale

/**
 * Jellyfin title details drawn in Alder's original full-bleed cinema language. The provider changes the data source,
 * not the visual identity: the same artwork treatment, type scale, glass actions and episode-guide behaviour.
 */
class ProviderDetailsActivity : Screen() {
    private var item: JellyfinItem? = null
    private var loadedEpisodes: List<JellyfinItem> = emptyList()
    private lateinit var heroArt: ImageView
    private lateinit var hero: FrameLayout
    private lateinit var root: FrameLayout
    private lateinit var playAction: TextView
    private var episodesAction: TextView? = null
    private var episodesUnavailable = false
    private var episodesLoaded = false
    private var backdropFailed = false
    private lateinit var guide: FrameLayout
    private lateinit var guideRows: LinearLayout
    private var guideOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val itemId = intent.getStringExtra("jellyfinItemId").orEmpty()
        if (itemId.isBlank()) { finish(); return }

        heroArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        hero = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        root = FrameLayout(this).apply {
            clipChildren = false
            addView(hero, FrameLayout.LayoutParams(-1, -1))
            addView(TextClock(this@ProviderDetailsActivity).apply {
                format12Hour = "h:mm a"
                format24Hour = "HH:mm"
                textSize = SMALL
                setTextColor(TEXT_2)
                typeface = text("", SMALL).typeface
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(36); rightMargin = dp(PAD)
            })
        }
        setWindowed(root, heroArt)

        val backdropUrl = Jellyfin.backdropUrl(this, itemId, 1800)
        val artWidth = resources.displayMetrics.widthPixels
        if (backdropUrl != null) {
            Images.load(backdropUrl, heroArt, artWidth, urgent = true, failed = {
                backdropFailed = true
                item?.let { loaded ->
                    Jellyfin.imageUrl(this, loaded.id, loaded.imageTag, 1400)?.let { poster ->
                        Images.load(poster, heroArt, artWidth, urgent = true) {
                            heroArt.animate().alpha(1f).setDuration(250).start()
                            refrost()
                        }
                    }
                }
            }) {
                heroArt.animate().alpha(1f).setDuration(250).start()
                refrost()
            }
        } else backdropFailed = true

        background({ Jellyfin.item(applicationContext, itemId) }) { result ->
            if (isFinishing || isDestroyed) return@background
            result.onSuccess { loaded ->
                item = loaded
                showHero(loaded)
                if (loaded.type.equals("Series", true)) loadEpisodes(loaded)
            }.onFailure {
                hero.removeAllViews()
                val message = column(Gravity.START).apply {
                    addView(text(R.string.provider_jellyfin_offline, BODY, color = TEXT))
                    addView(pill(R.string.provider_retry).apply {
                        setOnClickListener { recreate() }
                    }.marginTop(14))
                }
                hero.addView(message, FrameLayout.LayoutParams(dp(520), -2, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = dp(PAD); bottomMargin = dp(90)
                })
            }
        }
    }

    private fun showHero(loaded: JellyfinItem) {
        hero.removeAllViews()
        if (backdropFailed) Jellyfin.imageUrl(this, loaded.id, loaded.imageTag, 1400)?.let { poster ->
            Images.load(poster, heroArt, resources.displayMetrics.widthPixels, urgent = true) {
                heroArt.animate().alpha(1f).setDuration(250).start()
                refrost()
            }
        }
        val title = text(loaded.name, SERIF_HERO, serif = true).onArt().apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val facts = listOfNotNull(
            loaded.year?.toString(),
            loaded.officialRating,
            loaded.communityRating?.let { String.format(Locale.US, "%.1f", it) },
            loaded.genres.take(3).joinToString(" · ").takeIf { it.isNotBlank() }
        ).joinToString("   ·   ")
        val stack = column(Gravity.START)
        stack.addView(title)
        if (loaded.tagline?.isNotBlank() == true) {
            stack.addView(text(loaded.tagline, BODY, color = TEXT_2).onArt().marginTop(8))
        }
        if (facts.isNotBlank()) stack.addView(text(facts, SMALL, color = TEXT_2).onArt().marginTop(12))
        if (loaded.overview.isNotBlank()) {
            stack.addView(text(loaded.overview, BODY, color = TEXT).onArt().apply {
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(dp(500), -2).apply { topMargin = dp(10) })
        }

        val buttons = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        playAction = pill(R.string.action_play, R.drawable.ic_play).apply {
            setOnClickListener { playSelected(loaded) }
        }
        buttons.addView(playAction, wrap().apply { marginEnd = dp(10) })
        if (loaded.type.equals("Series", true)) {
            val episodeButton = pill(R.string.provider_episodes).apply {
                setOnClickListener {
                    if (::guide.isInitialized) showGuide()
                    else android.widget.Toast.makeText(this@ProviderDetailsActivity,
                        getString(R.string.provider_loading_episodes), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            episodesAction = episodeButton
            buttons.addView(episodeButton, wrap().apply { marginEnd = dp(10) })
        }
        buttons.addView(pill(R.string.watch_status_title).apply {
            setOnClickListener { chooseStatus(loaded) }
        })
        stack.addView(buttons, wrap().apply { topMargin = dp(20) })
        hero.addView(stack, FrameLayout.LayoutParams(dp(560), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD)
            bottomMargin = dp(86)
        })
        if (loaded.type.equals("Series", true)) episodesAction?.requestFocus() else playAction.requestFocus()
    }

    private fun loadEpisodes(series: JellyfinItem) {
        background({ Jellyfin.episodes(applicationContext, series.id) }) { result ->
            if (isFinishing || isDestroyed) return@background
            result.onSuccess { episodes ->
                loadedEpisodes = episodes
                episodesLoaded = true
                episodesUnavailable = false
                buildGuide(episodes)
            }.onFailure {
                loadedEpisodes = emptyList()
                episodesLoaded = true
                episodesUnavailable = true
                buildGuide(emptyList())
            }
        }
    }

    private fun buildGuide(episodes: List<JellyfinItem>) {
        if (::guide.isInitialized) root.removeView(guide)
        guideOpen = false
        guideRows = column(Gravity.START)
        guide = FrameLayout(this).apply {
            background = glassOver(this, 16, shade = FROST_DENSE)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
            visibility = View.GONE
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(caps(R.string.provider_episodes, TUNGSTEN))
            addView(text(item?.name.orEmpty(), SERIF_TITLE, serif = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }.marginTop(8))
            addView(ScrollView(this@ProviderDetailsActivity).apply {
                isVerticalScrollBarEnabled = false
                addView(guideRows)
            }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
        }
        guide.addView(panel, FrameLayout.LayoutParams(-1, -1))
        val width = minOf(dp(760), resources.displayMetrics.widthPixels - dp(PAD * 2))
        root.addView(guide, FrameLayout.LayoutParams(width, -1, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            topMargin = dp(86); bottomMargin = dp(30); rightMargin = dp(PAD)
        })

        if (episodes.isEmpty()) {
            guideRows.addView(text(if (episodesUnavailable) R.string.provider_episodes_unavailable else R.string.provider_no_episodes, BODY, color = TEXT_2).marginTop(12))
            return
        }
        episodes.groupBy { it.seasonNumber ?: 0 }.toSortedMap().forEach { (season, seasonEpisodes) ->
            val heading = if (season > 0) getString(R.string.provider_season_number, season)
                else getString(R.string.provider_specials)
            guideRows.addView(text(heading, BODY, color = TEXT).marginTop(12))
            seasonEpisodes.forEach { episode ->
                val prefix = listOfNotNull(
                    episode.seasonNumber?.let { "S%02d".format(it) },
                    episode.episodeNumber?.let { "E%02d".format(it) }
                ).joinToString("")
                val label = listOfNotNull(prefix.takeIf { it.isNotBlank() }, episode.name).joinToString("  ·  ")
                val row = text(label, SMALL, color = if (episode.played) TEXT_3 else TEXT).apply {
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    isFocusable = true
                    rowFocus(8)
                    setOnClickListener { playEpisode(item ?: return@setOnClickListener, episode) }
                }
                guideRows.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    private fun showGuide() {
        if (!::guide.isInitialized) {
            android.widget.Toast.makeText(this, getString(R.string.provider_loading_episodes),
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        guideOpen = true
        guide.visibility = View.VISIBLE
        (0 until guideRows.childCount).asSequence().map { guideRows.getChildAt(it) }
            .firstOrNull { it.isFocusable }?.requestFocus()
    }

    private fun hideGuide() {
        guideOpen = false
        guide.visibility = View.GONE
        if (::playAction.isInitialized) playAction.requestFocus()
    }

    private fun playSelected(selected: JellyfinItem) {
        if (!selected.type.equals("Series", true)) {
            playItem(selected, selected.seriesName ?: selected.name, selected.seriesId)
            return
        }
        if (!episodesLoaded) {
            android.widget.Toast.makeText(this, getString(R.string.provider_loading_episodes),
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val episode = nextJellyfinEpisode(loadedEpisodes)
        if (episode == null) {
            android.widget.Toast.makeText(this,
                getString(if (episodesUnavailable) R.string.provider_episodes_unavailable else R.string.provider_no_episodes),
                android.widget.Toast.LENGTH_SHORT).show()
        } else playEpisode(selected, episode)
    }

    private fun playEpisode(series: JellyfinItem, episode: JellyfinItem) {
        val episodeTitle = listOfNotNull(
            episode.seasonNumber?.let { "S%02d".format(it) },
            episode.episodeNumber?.let { "E%02d".format(it) },
            episode.name
        ).joinToString("  ·  ")
        playItem(episode, series.name, series.id, episodeTitle)
    }

    private fun playItem(selected: JellyfinItem, title: String, seriesId: String?, episodeTitle: String = "") {
        startActivity(Intent(this, ProviderPlayerActivity::class.java)
            .putExtra("jellyfinItemId", selected.id)
            .putExtra("title", title)
            .putExtra("episodeTitle", episodeTitle)
            .putExtra("seriesId", seriesId)
            .putExtra("resumeAtMs", selected.playbackPositionTicks / 10_000L))
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (guideOpen && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            hideGuide()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
