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
    private lateinit var status: TextView
    private lateinit var poster: ImageView
    private lateinit var actions: LinearLayout

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
                    loaded.genres.take(3).joinToString(" · ").ifBlank { null }
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
                actions.addView(action(getString(R.string.action_play)) {
                    startActivity(Intent(this, ProviderPlayerActivity::class.java)
                        .putExtra("jellyfinItemId", loaded.id).putExtra("title", loaded.name))
                })
                actions.addView(action(getString(R.string.watch_status_title)) { chooseStatus(loaded) }.apply {
                    layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(12) }
                })
                Images.load(Jellyfin.imageUrl(this, loaded.id, loaded.imageTag, 800), poster, dp(500))
            }.onFailure {
                status.text = getString(R.string.provider_jellyfin_offline)
            }
        }
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
