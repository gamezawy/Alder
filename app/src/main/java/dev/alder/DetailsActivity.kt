package dev.alder

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun castChipLabel(member: CastMember): String = member.name

/**
 * Selects the people shown on the title page's compact cast strip: at most one primary director plus lead actors,
 * capped so the strip fits inside the hero column without horizontal scrolling.
 */
internal fun heroCastPreview(allMembers: List<CastMember>, maxCount: Int = 3): List<CastMember> {
    if (allMembers.isEmpty() || maxCount <= 0) return emptyList()
    val directors = allMembers.filter { it.isDirector }
    val actors = allMembers.filterNot { it.isDirector }
    if (actors.isEmpty()) return directors.take(maxCount)
    val ordered = listOfNotNull(directors.firstOrNull()) + actors
    return ordered.take(maxCount)
}

internal fun isEpisodeAired(released: String?, now: Long = System.currentTimeMillis()): Boolean {
    if (released.isNullOrBlank()) return true
    val day = released.take(10).takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return true
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    if (runCatching { source.parse(day) }.getOrNull() == null) return true

    if (released.length >= 19 && released.contains('T')) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val full = runCatching { iso.parse(released.take(19)) }.getOrNull()
        if (full != null && full.time <= now) return true
    }

    val localToday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
    val utcToday = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
    return day <= localToday || day <= utcToday
}

/** "14 Nov" for an episode that airs later, in the viewer's language; the screen puts it in "Airs …". */
internal fun airDay(released: String?, locale: Locale = Locale.getDefault()): String? {
    val day = released?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return null
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val parsed = runCatching { source.parse(day) }.getOrNull() ?: return null
    return SimpleDateFormat("d MMM", locale).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(parsed)
}

internal fun isRecentlyAired(released: String?, now: Long = System.currentTimeMillis()): Boolean {
    if (!isEpisodeAired(released, now)) return false
    val day = released?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return false
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val parsed = runCatching { source.parse(day) }.getOrNull() ?: return false
    val age = now - parsed.time
    return age in 0..(8L * 24 * 60 * 60 * 1000L)
}

internal fun episodeDate(value: String?, locale: Locale = Locale.getDefault()): String? {
    val day = value?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return null
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val parsed = runCatching { source.parse(day) }.getOrNull() ?: return null
    return SimpleDateFormat("d MMM yyyy", locale).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(parsed)
}

// A title stays in place over its art; episodes and related titles open over the same scene.
class DetailsActivity : Screen() {
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val name by lazy { intent.getStringExtra("name") ?: "" }
    private val poster by lazy { intent.getStringExtra("poster") }
    private val initialArt by lazy { intent.getStringExtra("background") }

    private lateinit var key: String
    override val backdrop get() = heroArt
    private lateinit var root: FrameLayout
    private lateinit var hero: FrameLayout
    private lateinit var heroArt: ImageView
    private lateinit var guide: FrameLayout
    private lateinit var guideRows: LinearLayout
    private lateinit var seasonStrip: LinearLayout
    private var seasonButtons = emptyMap<Int, TextView>()
    private lateinit var previewImage: ImageView
    private lateinit var previewTitle: TextView
    private lateinit var previewFacts: TextView
    private lateinit var previewAbout: TextView
    private lateinit var previewProgress: FrameLayout
    private lateinit var similarOverlay: FrameLayout
    private var similarFirst: View? = null
    private lateinit var buttonRow: LinearLayout
    private val castChips = mutableListOf<View>()
    private lateinit var filmographyOverlay: FrameLayout
    private var filmographyOpen = false
    private var filmographyFirst: View? = null
    private var lastFocusedActor: View? = null
    private lateinit var listButton: TextView
    private lateinit var playButton: TextView
    private lateinit var infoButton: TextView
    private lateinit var infoOverlay: FrameLayout
    private var guideOpen = false
    private var similarOpen = false
    private var infoOpen = false
    private val infoCastCards = mutableListOf<View>()
    private var storyScrollView: ScrollView? = null
    private var richCast: List<CastMember> = emptyList()
    private var castStripSlot: FrameLayout? = null
    private var selectedSeason = 1
    private var episodeRows = emptyList<View>()
    private var meta: Meta? = null
    private var item: JSONObject? = null // the title's library item: progress and whether it's in My List
    private var itemChangedHere = false
    private var similarRequested = false
    private var imdbTrailers: List<Stream> = emptyList()
    private var trailerRequested = false
    private var certificate: String? = null
    private var certificateRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = authKey ?: return finish()
        val artWidth = resources.displayMetrics.widthPixels
        heroArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        hero = FrameLayout(this).apply { clipChildren = false }
        root = FrameLayout(this).apply {
            clipChildren = false
            addView(hero, FrameLayout.LayoutParams(-1, -1))
            addView(TextClock(this@DetailsActivity).apply {
                format12Hour = "h:mm a"
                format24Hour = "HH:mm"
                textSize = SMALL
                setTextColor(TEXT_2)
                typeface = text("", SMALL).typeface
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(36); rightMargin = dp(PAD)
            })
            val (loading, loadingText) = loadingOverlay()
            loadingView = loading
            loadingStatus = loadingText
            addView(loadingView, FrameLayout.LayoutParams(-1, -1))
        }
        setWindowed(root, heroArt)

        (initialArt ?: poster)?.let { Images.load(it, heroArt, artWidth, urgent = true) { heroArt.alpha = 1f; refrost() } }

        fun show(m: Meta) {
            meta = m
            val art = m.background ?: initialArt ?: poster
            if (heroArt.tag != art) Images.load(art, heroArt, artWidth, urgent = true) {
                heroArt.animate().alpha(1f).setDuration(250).start()
                refrost()
            }
            richCast = m.castMembers
            certificate = m.certification ?: certificate
            showHero(m)
            if (type == "series" && m.videos.isNotEmpty()) buildGuide(m.videos)
            if (type == "movie" && !similarRequested) { similarRequested = true; loadSimilar() }
            loadRichCast()
            if (!certificateRequested && certificate == null && m.id.startsWith("tt")) {
                certificateRequested = true
                background<String?>({ imdbCertificate(m.id) }) { r ->
                    val resolved = r.getOrNull()
                    if (resolved != null && resolved != certificate) {
                        certificate = resolved
                        if (!guideOpen && !infoOpen && !similarOpen && !filmographyOpen) showHero(meta)
                    }
                }
            }
            if (!trailerRequested && m.id.startsWith("tt")) {
                trailerRequested = true
                background({ imdbTrailerStreams(m.id) }) { r ->
                    val resolved = r.getOrDefault(emptyList())
                    if (resolved.isNotEmpty()) {
                        val hadTrailer = m.trailerStreams.isNotEmpty() || m.trailerYtId != null || imdbTrailers.isNotEmpty()
                        imdbTrailers = resolved
                        if (!hadTrailer && !guideOpen && !infoOpen && !similarOpen && !filmographyOpen) showHero(meta)
                    }
                }
            }
        }
        background({
            (Stremio.savedAddons(key) ?: Stremio.addons(key)).savedMeta(type, metaId) to Stremio.savedLibrary(key)?.firstOrNull { it.optString("_id") == metaId }
        }) { result ->
            result.getOrNull()?.let { (saved, libraryItem) ->
                if (!itemChangedHere && item == null) item = libraryItem
                if (meta == null) saved?.let(::show)
            }
        }
        background({ Stremio.addons(key).meta(type, metaId, together = true) }) { result ->
            val fresh = result.getOrNull()
            if (fresh != null) show(fresh)
            else if (meta == null) showHero(null)
        }
        background({ Stremio.library(key, listOf(metaId)).firstOrNull() }) { result ->
            if (!itemChangedHere) {
                item = result.getOrNull() ?: item
                if (meta != null) showHero(meta)
            }
        }
    }

    // ---------- title and watch window ----------

    private fun showHero(m: Meta?) {
        hero.removeAllViews()
        val title = text(m?.name ?: name, SERIF_HERO, serif = true).onArt().apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.BOTTOM or Gravity.START
            // The box is a fixed height: at large font sizes a two-line name shrinks to fit instead of clipping.
            setAutoSizeTextTypeUniformWithConfiguration(20, SERIF_HERO.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        val titleArea = FrameLayout(this).apply {
            addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
            if (m != null) {
                val logo = ImageView(this@DetailsActivity).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_START
                    maxWidth = dp(260)
                    maxHeight = dp(75)
                    visibility = View.GONE
                }
                addView(logo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
                Images.load(m.logo ?: Images.logoFor(m.id), logo, dp(520), urgent = true, failed = {
                    title.visibility = View.VISIBLE; logo.visibility = View.GONE
                }) {
                    title.visibility = View.GONE; logo.visibility = View.VISIBLE
                }
            }
        }
        val stack = column(Gravity.START).apply { addView(titleArea, LinearLayout.LayoutParams(-1, -2)) }
        if (m == null) {
            val error = failed(getString(R.string.details_load_failed)) { recreate() }
            stack.addView(error.marginTop(16))
            error.post { error.getChildAt(2).requestFocus() }
        } else {
            val length = if (type == "series") m.videos.map { it.season }.filter { it > 0 }.distinct().size
                .takeIf { it > 0 }?.let(::seasons) else m.runtime
            val facts = listOfNotNull(m.year, m.genres.firstOrNull(), length).joinToString(DOT)
            val factRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            // Content rating first, as a hairline-bordered badge: [ TV-MA ] · 2024 · Drama · 2 seasons
            certificate?.let { cert ->
                factRow.addView(text(cert, 9.5f, bold = true, color = TEXT_2).apply {
                    includeFontPadding = false
                    setLineSpacing(0f, 1f)
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(3).toFloat()
                        setStroke(dp(1), 0x55FFFFFF)
                    }
                })
                if (facts.isNotBlank()) factRow.addView(text(DOT, SMALL, color = TEXT_2).onArt())
            }
            factRow.addView(text(facts, SMALL, color = TEXT_2).onArt().apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            m.rating?.let { rating ->
                factRow.addView(text(DOT, SMALL, color = TEXT_2).onArt())
                factRow.addView(text("IMDb", 9.5f, bold = true, color = BASE).apply {
                    includeFontPadding = false
                    setLineSpacing(0f, 1f)
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                    background = rounded(TUNGSTEN, 3)
                })
                factRow.addView(text(rating, SMALL, color = TEXT_2).onArt(), wrap().apply { marginStart = dp(6) })
            }
            stack.addView(factRow, wrap().apply { topMargin = dp(12) })
            m.description?.takeIf { it.isNotBlank() }?.let {
                stack.addView(text(it, BODY, color = TEXT).onArt().apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(dp(470), -2).apply { topMargin = dp(8) })
            }
            val initialCast = richCast.ifEmpty {
                val dirMembers = m.directors.map { CastMember(it, character = getString(R.string.cast_director), role = "director") }
                dirMembers + m.castMembers
            }
            buildCastChips(initialCast)?.let { castStrip ->
                val slot = FrameLayout(this).apply {
                    clipChildren = false
                    clipToPadding = false
                    addView(castStrip)
                }
                castStripSlot = slot
                stack.addView(slot, wrap().apply { topMargin = dp(10) })
            }
            addWatch(m, stack)
        }
        hero.addView(stack, FrameLayout.LayoutParams(dp(HERO_W), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); bottomMargin = dp(92)
        })
        val hasEpisodes = type == "series" && m?.videos?.isNotEmpty() == true
        if (hasEpisodes || ::similarOverlay.isInitialized) {
            hero.addView(caps(if (hasEpisodes) R.string.details_episodes_below else R.string.details_more_below, TEXT_2).onArt().apply {
                isFocusable = true
                setOnClickListener { if (hasEpisodes) showGuide() else showSimilar() }
                rowFocus(rest = { null })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(28)
            })
        }
    }

    /** Where you are, then the main action and three small glass controls. */
    private fun addWatch(m: Meta, stack: LinearLayout) {
        val target = playTarget(m)
        val episode = target?.second
        // Under a minute in is not started: a title opened and closed at 0:00 offers Play, not Resume.
        val resumeAt = (item?.let { Library.resumeAt(it, target?.first ?: metaId) } ?: 0L).takeIf { it >= STARTED } ?: 0L
        val videos = m.videos
        val watched = Library.watched(item, videos)
        val airedVideos = videos.filter { it.season > 0 && isEpisodeAired(it.released) }
        val currentVideoId = item?.optJSONObject("state")?.text("video_id")
        val currentVideo = videos.firstOrNull { it.id == currentVideoId }
        val unairedNext = when {
            // Unfinished playback always resumes
            resumeAt > 0 -> null
            // Unpremiered show: first target episode is unaired
            episode != null && !isEpisodeAired(episode.released) -> episode
            // Caught up pointer is unaired
            currentVideo != null && !isEpisodeAired(currentVideo.released) -> currentVideo
            // All aired episodes watched: next in line is unaired
            airedVideos.isNotEmpty() && airedVideos.all { it.id in watched } ->
                videos.firstOrNull { it.season > 0 && !isEpisodeAired(it.released) && it.id !in watched }
            else -> null
        }

        val hasTrailer = m.trailerStreams.isNotEmpty() || m.trailerYtId != null || imdbTrailers.isNotEmpty()

        if (type == "series" && unairedNext != null) {
            val airDate = airDay(unairedNext.released) ?: getString(R.string.details_airs_soon)
            val caughtUp = getString(if (airedVideos.isEmpty()) R.string.details_premiere else R.string.details_up_to_date,
                unairedNext.season, unairedNext.episode, airDate)
            stack.addView(text(caughtUp, SMALL, bold = true).onArt().apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }.marginTop(16))

            playButton = pill(R.string.action_episodes).apply {
                setOnClickListener { showGuide() }
            }
            val trailerButton = if (hasTrailer) trailerPill { playTrailer(m) } else null
            infoButton = iconButton("ⓘ", getString(R.string.action_more_info)) { showInfo() }
            val statusButton = iconButton("◉", getString(R.string.watch_status_title)) { chooseStremioStatus() }
            listButton = iconButton(if (Library.inList(item)) "✓" else "+", getString(R.string.tab_my_list)) { toggleList() }
            buttonRow = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                addView(playButton, wrap().apply { marginEnd = dp(if (trailerButton != null) 10 else 14) })
                trailerButton?.let { addView(it, wrap().apply { marginEnd = dp(10) }) }
                addView(listButton, LinearLayout.LayoutParams(dp(BUTTON_H), dp(BUTTON_H)).apply { marginEnd = dp(8) })
                addView(statusButton, LinearLayout.LayoutParams(dp(BUTTON_H), dp(BUTTON_H)).apply { marginEnd = dp(8) })
                addView(infoButton, LinearLayout.LayoutParams(dp(BUTTON_H), dp(BUTTON_H)))
            }
            stack.addView(buttonRow, wrap().apply { topMargin = dp(16) })
            playButton.post { if (!guideOpen && !infoOpen && !similarOpen) playButton.requestFocus() }
            return
        }
        val duration = item?.optJSONObject("state")?.optLong("duration") ?: 0L
        val remaining = if (resumeAt > 0 && duration > resumeAt) getString(R.string.time_left, runtime("${(duration - resumeAt) / 60_000} min")) else null
        val where = listOfNotNull(episode?.let { "S${it.season} E${it.episode}" }, episode?.title?.takeIf { it.isNotBlank() }, remaining).joinToString(DOT)
        if (where.isNotEmpty() && resumeAt > 0) stack.addView(text(where, SMALL, bold = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }.marginTop(16))
        if (remaining != null) stack.addView(progressBar(resumeAt.toFloat() / duration), LinearLayout.LayoutParams(dp(240), dp(3)).apply { topMargin = dp(8) })

        playButton = pill(if (resumeAt > 0) R.string.action_resume else R.string.action_play, R.drawable.ic_play).apply {
            setOnClickListener { openSources(target?.first ?: metaId, startOver = false) }
            // Held OK: every source, to choose one by hand.
            setOnLongClickListener { openSources(target?.first ?: metaId, startOver = false, pick = true); true }
        }
        val trailerButton = if (hasTrailer) trailerPill { playTrailer(m) } else null
        val startOver = if (resumeAt > 0) iconButton("↺", getString(R.string.action_start_over)) {
            openSources(target?.first ?: metaId, startOver = true)
        } else null
        infoButton = iconButton("ⓘ", getString(R.string.action_more_info)) { showInfo(focusCast = false) }
        val statusButton = iconButton("◉", getString(R.string.watch_status_title)) { chooseStremioStatus() }
        listButton = iconButton(if (Library.inList(item)) "✓" else "+", getString(R.string.tab_my_list)) { toggleList() }
        buttonRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(playButton, wrap().apply { marginEnd = dp(if (trailerButton != null) 10 else 14) })
            trailerButton?.let { addView(it, wrap().apply { marginEnd = dp(10) }) }
            startOver?.let { addView(it, LinearLayout.LayoutParams(dp(BUTTON_H), dp(BUTTON_H)).apply { marginEnd = dp(8) }) }
            addView(listButton, LinearLayout.LayoutParams(dp(BUTTON_H), dp(BUTTON_H)).apply { marginEnd = dp(8) })
            addView(statusButton, LinearLayout.LayoutParams(dp(BUTTON_H), dp(BUTTON_H)).apply { marginEnd = dp(8) })
            addView(infoButton, LinearLayout.LayoutParams(dp(BUTTON_H), dp(BUTTON_H)))
        }
        stack.addView(buttonRow, wrap().apply { topMargin = dp(16) })
        playButton.post { if (!guideOpen && !infoOpen && !similarOpen && !filmographyOpen) playButton.requestFocus() }
    }

    private fun chooseStremioStatus() {
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
        choose(getString(R.string.watch_status_title), labels, m.name,
            checked = WatchStatuses.get(this, "stremio", metaId).ordinal) { index ->
            WatchStatuses.set(this, "stremio", metaId, statuses[index])
        }
    }

    private fun seasonName(season: Int) = if (season == 0) getString(R.string.season_specials) else getString(R.string.season_number, season)

    private fun trailerPill(action: () -> Unit) = text(R.string.action_trailer, SMALL, bold = true).apply {
        isFocusable = true
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(BUTTON_H)
        setPadding(dp(14), 0, dp(16), 0)
        compoundDrawablePadding = dp(6)
        val icon = getDrawable(R.drawable.ic_play)?.mutate()?.apply {
            setBounds(0, 0, dp(15), dp(15))
            setTint(TEXT)
        }
        setCompoundDrawablesRelative(icon, null, null, null)
        background = glass(BUTTON_H / 2)
        setOnFocusChangeListener { v, has ->
            v.background = glass(BUTTON_H / 2, has)
            v.grow(has, CHIP_GROW).start()
        }
        setOnClickListener { action() }
    }

    private fun iconButton(symbol: String, description: String, action: () -> Unit): TextView = object : TextView(this) {
        private val d = resources.displayMetrics.density
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            style = Paint.Style.FILL
        }
        private val glyphPath = Path()
        private val glyphBounds = Rect()

        override fun onDraw(canvas: Canvas) {
            val s = text?.toString().orEmpty()
            if (s.isEmpty()) return
            val cx = width / 2f
            val cy = height / 2f
            strokePaint.color = currentTextColor
            fillPaint.color = currentTextColor
            when (s) {
                "+" -> {
                    val r = 5.5f * d
                    strokePaint.strokeWidth = 1.8f * d
                    canvas.drawLine(cx - r, cy, cx + r, cy, strokePaint)
                    canvas.drawLine(cx, cy - r, cx, cy + r, strokePaint)
                }
                "✓" -> {
                    strokePaint.strokeWidth = 1.8f * d
                    glyphPath.reset()
                    glyphPath.moveTo(cx - 5f * d, cy + 0.3f * d)
                    glyphPath.lineTo(cx - 1.5f * d, cy + 3.8f * d)
                    glyphPath.lineTo(cx + 5.2f * d, cy - 3.4f * d)
                    canvas.drawPath(glyphPath, strokePaint)
                }
                "↺" -> {
                    val r = 5.8f * d
                    strokePaint.strokeWidth = 1.6f * d
                    canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -135f, 285f, false, strokePaint)
                    val ax = cx - 4.1f * d
                    val ay = cy - 4.1f * d
                    glyphPath.reset()
                    glyphPath.moveTo(ax, ay - 2.8f * d)
                    glyphPath.lineTo(ax, ay + 0.4f * d)
                    glyphPath.lineTo(ax + 3.2f * d, ay + 0.4f * d)
                    canvas.drawPath(glyphPath, strokePaint)
                }
                "ⓘ" -> {
                    val r = 6.8f * d
                    strokePaint.strokeWidth = 1.5f * d
                    canvas.drawCircle(cx, cy, r, strokePaint)
                    strokePaint.strokeWidth = 1.6f * d
                    canvas.drawLine(cx, cy - 0.5f * d, cx, cy + 3.6f * d, strokePaint)
                    canvas.drawCircle(cx, cy - 3.2f * d, 0.95f * d, fillPaint)
                }
                else -> {
                    paint.color = currentTextColor
                    paint.getTextBounds(s, 0, s.length, glyphBounds)
                    canvas.drawText(s, cx - glyphBounds.exactCenterX(), cy - glyphBounds.exactCenterY(), paint)
                }
            }
        }
    }.apply {
        text = symbol
        textSize = 18f
        setTextColor(TEXT)
        gravity = Gravity.CENTER
        includeFontPadding = false
        contentDescription = description
        isFocusable = true
        background = glass(BUTTON_H / 2)
        setOnFocusChangeListener { v, has ->
            v.background = glass(BUTTON_H / 2, has)
            v.grow(has, CHIP_GROW).start()
        }
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(BUTTON_H), dp(BUTTON_H))
    }

    /** What Play opens: the episode Continue Watching points at, or the first episode, or the movie itself. */
    private fun playTarget(m: Meta?): Pair<String, Video?>? {
        val videos = m?.videos.orEmpty()
        if (videos.isEmpty()) return metaId to null
        val current = item?.optJSONObject("state")?.text("video_id")
        val video = videos.firstOrNull { it.id == current } ?: videos.firstOrNull { it.season > 0 } ?: videos.first()
        return video.id to video
    }

    private fun toggleList() {
        itemChangedHere = true
        val entry = item ?: Library.newItem(metaId, type, meta?.name ?: name, meta?.poster ?: poster)
        Library.setInList(entry, !Library.inList(item))
        item = entry
        listButton.text = if (Library.inList(entry)) "✓" else "+"
        listButton.contentDescription = getString(if (Library.inList(entry)) R.string.action_remove_from_list else R.string.action_add_to_list)
        val snapshot = JSONObject(entry.toString())
        background({ Stremio.saveLibrary(key, listOf(snapshot)) }) {}
    }

    // ---------- glass and episode guide ----------

    private fun glassPane() = FrameLayout(this).apply {
        background = glassOver(this, 14, shade = FROST_DENSE)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
        }
    }

    private fun buildGuide(videos: List<Video>) {
        if (::guide.isInitialized) root.removeView(guide)
        guideOpen = false
        val x = dp(PAD)
        val top = dp(128)
        val width = resources.displayMetrics.widthPixels - x * 2
        val height = resources.displayMetrics.heightPixels - top - dp(27)
        val largeText = resources.configuration.fontScale > 1.15f
        guide = FrameLayout(this).apply { visibility = View.GONE }
        val smallLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
        guide.addView(smallLogo, FrameLayout.LayoutParams(dp(150), dp(42)).apply {
            leftMargin = x; topMargin = dp(27)
        })
        val fallback = text(meta?.name ?: name, SERIF_TITLE, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE
        }
        guide.addView(fallback, FrameLayout.LayoutParams(dp(250), -2).apply {
            leftMargin = x; topMargin = dp(28)
        })
        Images.load(meta?.logo ?: Images.logoFor(metaId), smallLogo, dp(300), urgent = true, failed = {
            smallLogo.visibility = View.GONE; fallback.visibility = View.VISIBLE
        })

        seasonStrip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        // Room around the chips for the focused one to grow into: flush with the strip, its left edge was cut flat.
        val room = dp(8)
        guide.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(room, room, room, room)
            clipToPadding = false
            addView(seasonStrip)
        }, FrameLayout.LayoutParams(width + 2 * room, dp(32) + 2 * room).apply { leftMargin = x - room; topMargin = dp(88) - room })

        val pane = glassPane()
        guideRows = column(Gravity.START)
        pane.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            // The row past the panel's foot fades out instead of running into the edge.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(40))
            addView(guideRows)
        }, FrameLayout.LayoutParams(dp(380), height - dp(28)).apply {
            leftMargin = dp(14); topMargin = dp(14)
        })
        val previewX = dp(414)
        val previewWidth = width - previewX - dp(14)
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(BASE, 8)
            clipToOutline = true
        }
        pane.addView(previewImage, FrameLayout.LayoutParams(previewWidth, dp(226)).apply {
            leftMargin = previewX; topMargin = dp(14)
        })
        previewProgress = FrameLayout(this)
        pane.addView(previewProgress, FrameLayout.LayoutParams(previewWidth - dp(20), dp(3)).apply {
            leftMargin = previewX + dp(10); topMargin = dp(229)
        })
        previewTitle = text("", 22f, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewTitle, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(251)
        })
        previewFacts = text("", SMALL, color = TEXT_2).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewFacts, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(if (largeText) 285 else 280)
        })
        previewAbout = text("", SMALL, color = TEXT).onArt().apply {
            maxLines = if (largeText) 2 else 3
            ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewAbout, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(if (largeText) 313 else 304)
        })
        guide.addView(pane, FrameLayout.LayoutParams(width, height).apply {
            leftMargin = x; topMargin = top
        })
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))
        selectedSeason = playTarget(meta)?.second?.season ?: videos.first().season
        fillGuide()
    }

    private fun fillGuide() {
        val videos = meta?.videos.orEmpty()
        val seasons = videos.map { it.season }.distinct()
        seasonStrip.removeAllViews()
        seasonButtons = seasons.associateWith { season ->
            chip(if (season == selectedSeason) seasonName(season)
                else if (season == 0) getString(R.string.season_specials) else season.toString(), season == selectedSeason).apply {
                setOnClickListener {
                    selectedSeason = season
                    fillGuide()
                    seasonButtons[season]?.requestFocus()
                }
                setOnLongClickListener {
                    seasonMenu(season)
                    true
                }
                seasonStrip.addView(this, LinearLayout.LayoutParams(-2, dp(24)).apply {
                    if (seasonStrip.childCount > 0) marginStart = dp(8)
                })
            }
        }
        guideRows.removeAllViews()
        val watched = Library.watched(item, videos)
        val currentId = playTarget(meta)?.first
        episodeRows = videos.filter { it.season == selectedSeason }.map { video ->
            val row = FrameLayout(this).apply { tag = video.id }
            val number = text("%02d".format(video.episode), BODY, color = TEXT_3)
            row.addView(number, FrameLayout.LayoutParams(dp(32), -2, Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(12)
            })
            val resumeAt = item?.let { Library.resumeAt(it, video.id) } ?: 0L
            val duration = item?.optJSONObject("state")?.takeIf { it.text("video_id") == video.id }
                ?.optLong("duration") ?: 0L
            val aired = isEpisodeAired(video.released)
            val status = when {
                !aired -> airDay(video.released)?.let { getString(R.string.airs_on, it) }.orEmpty()
                video.id in watched -> "✓"
                resumeAt > 0 && duration > resumeAt -> getString(R.string.time_left_minutes, ((duration - resumeAt) / 60_000).toInt())
                else -> meta?.runtime.orEmpty()
            }
            val titleColor = when {
                !aired -> TEXT_3
                video.id in watched -> TEXT_2
                else -> TEXT
            }
            row.addView(text(video.title.ifBlank { getString(R.string.episode_number, video.episode) }, BODY,
                color = titleColor).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, FrameLayout.LayoutParams(dp(225), -2, Gravity.CENTER_VERTICAL).apply { leftMargin = dp(46) })
            if (status.isNotEmpty()) row.addView(text(status, SMALL,
                color = if (!aired) TEXT_3 else if (video.id in watched) TUNGSTEN else TEXT_2).apply { gravity = Gravity.END },
                FrameLayout.LayoutParams(dp(98), -2, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                    rightMargin = dp(12)
                })
            row.rowFocus(9) { focused -> if (focused) updatePreview(video) }
            if (aired) {
                row.setOnClickListener {
                    val next = videos.getOrNull(videos.indexOf(video) + 1)?.id
                    openSources(video.id, startOver = false, next = next, label = "S${video.season} E${video.episode}")
                }
                row.setOnLongClickListener {
                    episodeMenu(video)
                    true
                }
            }
            guideRows.addView(row, LinearLayout.LayoutParams(-1, dp(45)))
            row
        }
        videos.firstOrNull { it.id == currentId && it.season == selectedSeason }?.let(::updatePreview)
            ?: videos.firstOrNull { it.season == selectedSeason }?.let(::updatePreview)
    }

    private fun updatePreview(video: Video) {
        val blur = Settings.blurThumbnails(this) && video.id !in Library.watched(item, meta?.videos.orEmpty())
        Images.load(video.thumbnail, previewImage, dp(870), orElse = meta?.background, look = if (blur) Images.blurred else null)
        previewTitle.text = video.title.ifBlank { getString(R.string.episode_number, video.episode) }
        previewFacts.text = listOfNotNull("S${video.season} E${video.episode}", meta?.runtime,
            episodeDate(video.released)).joinToString(DOT)
        previewAbout.text = video.overview.orEmpty()
        previewAbout.visibility = if (video.overview.isNullOrBlank()) View.GONE else View.VISIBLE
        previewProgress.removeAllViews()
        val state = item?.optJSONObject("state")?.takeIf { it.text("video_id") == video.id }
        val resumeAt = item?.let { Library.resumeAt(it, video.id) } ?: 0L
        val duration = state?.optLong("duration") ?: 0L
        if (resumeAt > 0 && duration > resumeAt) {
            previewProgress.addView(progressBar(resumeAt.toFloat() / duration),
                FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun showGuide() {
        if (!::guide.isInitialized || guideOpen) return
        guideOpen = true
        hero.visibility = View.INVISIBLE
        guide.visibility = View.VISIBLE
        guide.alpha = 0f
        guide.translationY = dp(28).toFloat()
        guide.animate().alpha(1f).translationY(0f).setDuration(260).start()
        // The film steps back behind the guide, so the chips and panel read over a bright backdrop.
        heroArt.animate().alpha(GUIDE_ART).setDuration(260).withEndAction { refrost() }.start()
        leftShade(false) // the episode list sits on glass: the art can run to the left edge as it does on the right
        val currentId = playTarget(meta)?.first
        (episodeRows.firstOrNull { it.tag == currentId } ?: episodeRows.firstOrNull())?.requestFocus()
    }

    private fun hideGuide() {
        guideOpen = false
        heroArt.animate().alpha(1f).setDuration(220).withEndAction { refrost() }.start()
        leftShade(true)
        guide.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(220).withEndAction {
            guide.visibility = View.GONE
            hero.visibility = View.VISIBLE
            if (::playButton.isInitialized) playButton.requestFocus()
        }.start()
    }

    private fun ensureLibraryItem(): JSONObject {
        itemChangedHere = true
        val entry = item ?: Library.newItem(metaId, type, meta?.name ?: name, meta?.poster ?: poster)
        item = entry
        return entry
    }

    private fun saveAndRefresh(focusEpisodeId: String? = null, focusSeason: Int? = null) {
        val entry = item ?: return
        val snapshot = JSONObject(entry.toString())
        background({ Stremio.saveLibrary(key, listOf(snapshot)) }) {}
        fillGuide()
        meta?.let(::showHero)
        if (focusEpisodeId != null) {
            val target = (0 until guideRows.childCount).map { guideRows.getChildAt(it) }
                .firstOrNull { it.tag == focusEpisodeId }
            target?.requestFocus()
            meta?.videos?.firstOrNull { it.id == focusEpisodeId }?.let(::updatePreview)
        } else if (focusSeason != null) {
            seasonButtons[focusSeason]?.requestFocus()
        }
    }

    private fun episodeMenu(video: Video) {
        val videos = meta?.videos.orEmpty()
        val watched = Library.watched(item, videos)
        val isWatched = video.id in watched
        val prior = Library.priorEpisodes(videos, video)

        val options = mutableListOf(R.string.action_choose_source, if (isWatched) R.string.action_mark_unwatched else R.string.action_mark_watched)
        if (prior.isNotEmpty()) {
            options.add(R.string.action_mark_previous_watched)
        }

        val epNumber = if (video.season > 0) "S${video.season} E${video.episode}" else "E${video.episode}"
        val title = video.title.ifBlank { epNumber }
        val message = if (video.title.isNotBlank()) epNumber else null

        choose(title, options.map(::getString), message = message) { selected ->
            when (options[selected]) {
                R.string.action_choose_source -> {
                    val next = videos.getOrNull(videos.indexOf(video) + 1)?.id
                    openSources(video.id, startOver = false, next = next,
                        label = "S${video.season} E${video.episode}", pick = true)
                }
                R.string.action_mark_watched -> markEpisodeWatched(video, true)
                R.string.action_mark_unwatched -> markEpisodeWatched(video, false)
                R.string.action_mark_previous_watched -> markPreviousEpisodesWatched(video, prior)
            }
        }
    }

    private fun markEpisodeWatched(video: Video, watched: Boolean) {
        val entry = ensureLibraryItem()
        val videos = meta?.videos.orEmpty()
        val s = entry.state
        if (watched) {
            Library.markWatched(entry, videos, video.id)
            s.put("timesWatched", maxOf(1, s.optInt("timesWatched")))
            val nextEp = Library.nextEpisode(videos, video)
            if (nextEp != null) {
                s.put("video_id", nextEp.id)
                s.put("timeOffset", 0L)
                s.put("flaggedWatched", 0)
            } else {
                s.put("video_id", video.id)
                s.put("timeOffset", 0L)
                s.put("flaggedWatched", 1)
            }
        } else {
            Library.markUnwatched(entry, videos, video.id)
            s.put("video_id", video.id)
            s.put("timeOffset", 0L)
            s.put("flaggedWatched", 0)
        }
        entry.put("_mtime", Library.now())
        saveAndRefresh(focusEpisodeId = video.id)
    }

    private fun markPreviousEpisodesWatched(video: Video, prior: List<Video>) {
        val entry = ensureLibraryItem()
        val videos = meta?.videos.orEmpty()
        val s = entry.state
        Library.markEpisodesWatched(entry, videos, prior.map { it.id })
        s.put("timesWatched", maxOf(1, s.optInt("timesWatched")))
        s.put("video_id", video.id)
        s.put("timeOffset", 0L)
        s.put("flaggedWatched", 0)
        entry.put("_mtime", Library.now())
        saveAndRefresh(focusEpisodeId = video.id)
    }

    private fun seasonMenu(season: Int) {
        val videos = meta?.videos.orEmpty()
        val seasonVideos = videos.filter { it.season == season }
        if (seasonVideos.isEmpty()) return
        val watched = Library.watched(item, videos)
        val allWatched = seasonVideos.all { it.id in watched }
        val title = seasonName(season)
        val option = getString(if (allWatched) R.string.mark_season_unwatched else R.string.mark_season_watched, title)
        choose(title, listOf(option)) { selected ->
            if (selected == 0) {
                markSeasonWatched(season, !allWatched)
            }
        }
    }

    private fun markSeasonWatched(season: Int, watched: Boolean) {
        val entry = ensureLibraryItem()
        val videos = meta?.videos.orEmpty()
        val seasonVideos = videos.filter { it.season == season }
        val seasonIds = seasonVideos.map { it.id }.toSet()
        val s = entry.state

        if (watched) {
            Library.markEpisodesWatched(entry, videos, seasonIds)
            s.put("timesWatched", maxOf(1, s.optInt("timesWatched")))
            val nextEp = Library.nextEpisodeAfterSeason(videos, season)
            if (nextEp != null) {
                s.put("video_id", nextEp.id)
                s.put("timeOffset", 0L)
                s.put("flaggedWatched", 0)
            } else {
                seasonVideos.lastOrNull()?.let { s.put("video_id", it.id) }
                s.put("timeOffset", 0L)
                s.put("flaggedWatched", 1)
            }
        } else {
            Library.unmarkEpisodesWatched(entry, videos, seasonIds)
            seasonVideos.firstOrNull()?.let { firstEp ->
                s.put("video_id", firstEp.id)
                s.put("timeOffset", 0L)
                s.put("flaggedWatched", 0)
            }
        }
        entry.put("_mtime", Library.now())
        saveAndRefresh(focusSeason = season)
    }

    /**
     * The whole story and complete cast, with circular portraits above actor and character names.
     * Remote navigates between the story text and cast cards, with OK on any actor revealing their filmography.
     */
    private fun showInfo(focusCast: Boolean = false) {
        val m = meta ?: return
        if (::infoOverlay.isInitialized) root.removeView(infoOverlay)
        infoOpen = true
        hero.visibility = View.INVISIBLE
        infoCastCards.clear()
        storyScrollView = null

        infoOverlay = FrameLayout(this)
        infoOverlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xEB0E0C0A.toInt(), 0xD90E0C0A.toInt(), 0x990E0C0A.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))

        val facts = listOfNotNull(m.year, m.runtime,
            m.genres.take(2).joinToString(" / ").ifEmpty { null }, m.rating?.let { "IMDb $it" }).joinToString(DOT)

        val content = column(Gravity.START).apply {
            clipChildren = false
            clipToPadding = false
        }

        content.addView(text(m.name, SERIF_TITLE, bold = true, serif = true).onArt().apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        content.addView(text(facts, SMALL, color = TEXT_2).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }.marginTop(6))

        m.description?.takeIf { it.isNotBlank() }?.let { desc ->
            content.addView(caps(R.string.details_the_story, TEXT_2).onArt().marginTop(14))
            val story = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isFocusable = true
                addView(text(desc, BODY, color = TEXT).onArt())
            }
            storyScrollView = story
            content.addView(story, LinearLayout.LayoutParams(dp(HERO_W), dp(110)).apply { topMargin = dp(6) })
        }

        val fullCast = richCast.ifEmpty {
            val directors = m.directors.map { CastMember(it, getString(R.string.cast_director), null, role = "director") }
            val directorNames = directors.map { it.name.lowercase() }.toSet()
            directors + m.castMembers.filter { it.name.lowercase() !in directorNames }
        }
        if (fullCast.isNotEmpty()) {
            content.addView(caps(R.string.details_cast, TEXT_2).onArt().marginTop(14))
            val castRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }

            val avatarSize = dp(56)
            val ringBox = avatarSize + dp(6)

            fullCast.forEachIndexed { index, actor ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isFocusable = true
                    clipChildren = false
                    clipToPadding = false
                    setPadding(dp(4), dp(4), dp(4), dp(6))
                }

                val avatarContainer = FrameLayout(this).apply { clipChildren = false }

                val ring = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x00000000)
                        setStroke(dp(2), TUNGSTEN)
                    }
                    visibility = View.INVISIBLE
                }
                avatarContainer.addView(ring, FrameLayout.LayoutParams(ringBox, ringBox, Gravity.CENTER))

                val avatarFrame = FrameLayout(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x33F3D9B1.toInt())
                    }
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) =
                            outline.setOval(0, 0, view.width, view.height)
                    }
                }
                val initials = text(actor.initials, 15f, bold = true, color = 0xFFF3D9B1.toInt(), serif = true).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
                avatarFrame.addView(initials, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

                if (!actor.photo.isNullOrBlank()) {
                    val photoView = ImageView(this@DetailsActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        visibility = View.GONE
                    }
                    avatarFrame.addView(photoView, FrameLayout.LayoutParams(-1, -1))
                    Images.load(actor.photo, photoView, avatarSize, failed = {
                        photoView.visibility = View.GONE
                        initials.visibility = View.VISIBLE
                    }) {
                        photoView.visibility = View.VISIBLE
                        initials.visibility = View.GONE
                    }
                }
                avatarContainer.addView(avatarFrame, FrameLayout.LayoutParams(avatarSize, avatarSize, Gravity.CENTER))

                card.addView(avatarContainer, wrap())

                val nameView = text(actor.name, SMALL, bold = true, color = TEXT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                card.addView(nameView, LinearLayout.LayoutParams(dp(82), -2).apply { topMargin = dp(6) })

                val role = if (actor.isDirector) getString(R.string.cast_director) else actor.character
                val charView = if (!role.isNullOrBlank()) {
                    text(role, 11f, color = TEXT_2).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        textAlignment = View.TEXT_ALIGNMENT_CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                } else null
                charView?.let { card.addView(it, LinearLayout.LayoutParams(dp(82), -2).apply { topMargin = dp(2) }) }

                card.setOnFocusChangeListener { _, focused ->
                    card.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f).setDuration(120).start()
                    ring.visibility = if (focused) View.VISIBLE else View.INVISIBLE
                    nameView.setTextColor(if (focused) 0xFFFBF8F5.toInt() else TEXT)
                    charView?.setTextColor(if (focused) TEXT else TEXT_2)
                }

                card.setOnClickListener {
                    showFilmography(actor, card)
                }

                infoCastCards.add(card)
                castRow.addView(card, LinearLayout.LayoutParams(-2, -2).apply {
                    if (index > 0) marginStart = dp(12)
                })
            }

            val room = dp(8)
            val castScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                setPadding(room, room, room, room)
                addView(castRow)
            }
            content.addView(castScroll, LinearLayout.LayoutParams(-1, dp(128) + 2 * room).apply {
                leftMargin = -room
                rightMargin = -room
                topMargin = dp(6) - room
                bottomMargin = -room
            })
        }

        infoOverlay.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(34)
        })
        root.addView(infoOverlay, FrameLayout.LayoutParams(-1, -1))
        infoOverlay.alpha = 0f
        infoOverlay.translationY = dp(20).toFloat()
        infoOverlay.animate().alpha(1f).translationY(0f).setDuration(220).start()

        if (focusCast && infoCastCards.isNotEmpty()) {
            infoCastCards.first().requestFocus()
        } else {
            storyScrollView?.requestFocus() ?: infoCastCards.firstOrNull()?.requestFocus()
        }
    }

    private fun hideInfo() {
        infoOpen = false
        if (::infoOverlay.isInitialized) {
            val closing = infoOverlay
            closing.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(180).withEndAction {
                root.removeView(closing)
                if (!infoOpen) {
                    hero.visibility = View.VISIBLE
                    infoButton.requestFocus()
                }
            }.start()
        } else {
            hero.visibility = View.VISIBLE
            infoButton.requestFocus()
        }
    }

    private fun loadSimilar() {
        background({
            Stremio.addons(key).firstNotNullOfOrNull { addon ->
                runCatching { addon.similar(type, metaId) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }.orEmpty()
        }) { result ->
            result.getOrNull()?.let { titles ->
                titles.asSequence().filter { it.id != metaId && it.type == type }
                    .distinctBy { it.id }.take(12).toList().takeIf { it.isNotEmpty() }
                    ?.let { showSimilar(getString(R.string.details_more_like_this), it) }
            }
        }
    }

    /** Movies reveal one Home-sized poster row without moving the title off the screen. */
    private fun showSimilar(heading: String, titles: List<Meta>) {
        if (::similarOverlay.isInitialized) root.removeView(similarOverlay)
        similarOverlay = FrameLayout(this).apply { visibility = View.GONE }
        similarOverlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF70E0C0A.toInt(), 0xA60E0C0A.toInt(), 0x000E0C0A))
        }, FrameLayout.LayoutParams(-1, dp(280), Gravity.BOTTOM))
        val content = column(Gravity.START)
        val captionPill = caption("").apply {
            visibility = View.INVISIBLE
            maxWidth = dp(360)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(caps(heading).onArt())
            addView(captionPill, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        }
        content.addView(headerRow, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
        similarFirst = null
        titles.forEachIndexed { index, title ->
            val card = FrameLayout(this).apply {
                background = rounded(BASE, 6)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
                }
                clipToOutline = true
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(image, FrameLayout.LayoutParams(-1, -1))
                val fallback = text(title.name, SMALL, bold = true).apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(8), 0, dp(8), dp(8)); visibility = View.GONE
                }
                addView(fallback, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                cardFocus(6) { has ->
                    if (has) {
                        val yearStr = title.year?.take(4)?.let { "  ·  $it" } ?: ""
                        captionPill.text = "${title.name}$yearStr"
                        captionPill.visibility = View.VISIBLE
                    }
                }
                setOnClickListener {
                    startActivity(Intent(this@DetailsActivity, DetailsActivity::class.java)
                        .putExtra("type", title.type).putExtra("id", title.id).putExtra("name", title.name)
                        .putExtra("poster", title.poster).putExtra("background", title.background))
                }
                Images.load(title.poster ?: title.background, image, dp(POSTER_W), failed = {
                    fallback.visibility = View.VISIBLE
                })
            }
            if (similarFirst == null) similarFirst = card
            row.addView(card, LinearLayout.LayoutParams(dp(POSTER_W), dp(POSTER_H)).apply {
                if (index > 0) marginStart = dp(CARD_GAP)
            })
        }
        val room = dp(8)
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(room, room, room, room)
            addView(row)
        }, LinearLayout.LayoutParams(-1, dp(POSTER_H) + 2 * room).apply {
            leftMargin = -room
            rightMargin = -room
            topMargin = -room
            bottomMargin = -room
        })
        similarOverlay.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(38)
        })
        root.addView(similarOverlay, FrameLayout.LayoutParams(-1, -1))
        showHero(meta)
    }

    private fun showSimilar() {
        if (!::similarOverlay.isInitialized || similarOpen) return
        similarOpen = true
        hero.visibility = View.INVISIBLE
        similarOverlay.visibility = View.VISIBLE
        similarOverlay.alpha = 0f
        similarOverlay.translationY = dp(20).toFloat()
        similarOverlay.animate().alpha(1f).translationY(0f).setDuration(220).start()
        similarFirst?.requestFocus()
    }

    private fun hideSimilar() {
        similarOpen = false
        similarOverlay.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(180).withEndAction {
            similarOverlay.visibility = View.GONE
            hero.visibility = View.VISIBLE
            playButton.requestFocus()
        }.start()
    }

    private fun loadRichCast() {
        background({
            Tmdb.cast(metaId, type)
        }) { result ->
            val enhanced = result.getOrNull().orEmpty()
            if (enhanced.isNotEmpty()) {
                richCast = enhanced
                castStripSlot?.let { slot ->
                    slot.removeAllViews()
                    buildCastChips(richCast)?.let { slot.addView(it) }
                }
                if (infoOpen) {
                    showInfo(focusCast = currentFocus in infoCastCards)
                }
            }
        }
    }

    private fun buildCastChips(allMembers: List<CastMember>): View? {
        val members = heroCastPreview(
            allMembers,
            maxCount = if (allMembers.size <= 4 && allMembers.sumOf { it.name.length } <= 52) 4 else 3
        )
        if (members.isEmpty()) return null
        castChips.clear()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        members.forEachIndexed { index, actor ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                minimumHeight = dp(28)
                setPadding(dp(4), dp(4), dp(11), dp(4))
                val rest = glassOver(this, 14, shade = FROST_DENSE)
                background = rest

                val avatarSize = dp(20)
                val avatarFrame = FrameLayout(context).apply {
                    background = rounded(0x33F3D9B1.toInt(), 10)
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) =
                            outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                    }
                }
                val initialsText = text(actor.initials, 9f, bold = true, color = TUNGSTEN, serif = true).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
                avatarFrame.addView(initialsText, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

                if (!actor.photo.isNullOrBlank()) {
                    val photoView = ImageView(this@DetailsActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        visibility = View.GONE
                    }
                    avatarFrame.addView(photoView, FrameLayout.LayoutParams(-1, -1))
                    Images.load(actor.photo, photoView, avatarSize, failed = {
                        photoView.visibility = View.GONE
                        initialsText.visibility = View.VISIBLE
                    }) {
                        photoView.visibility = View.VISIBLE
                        initialsText.visibility = View.GONE
                    }
                }
                addView(avatarFrame, LinearLayout.LayoutParams(avatarSize, avatarSize).apply { marginEnd = dp(7) })

                val nameView = text(castChipLabel(actor), SMALL, color = TEXT).apply {
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(115)
                }
                addView(nameView, wrap())

                setOnFocusChangeListener { v, has ->
                    v.background = if (has) glass(14, true) else rest
                    v.grow(has, CHIP_GROW).start()
                    nameView.setTextColor(TEXT)
                }
                setOnClickListener { showFilmography(actor, this) }
            }
            castChips.add(chip)
            container.addView(chip, LinearLayout.LayoutParams(-2, -2).apply {
                if (index > 0) marginStart = dp(8)
            })
        }

        val totalCastCount = (richCast.ifEmpty { allMembers }).size
        val remainingCount = totalCastCount - members.size
        if (remainingCount > 0) {
            val moreChip = text(getString(R.string.cast_more, remainingCount), SMALL, color = TEXT).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                minHeight = dp(28)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                val rest = glassOver(this, 14, shade = FROST_DENSE)
                background = rest
                isFocusable = true
                setOnFocusChangeListener { v, has ->
                    v.background = if (has) glass(14, true) else rest
                    v.grow(has, CHIP_GROW).start()
                }
                setOnClickListener { showInfo(focusCast = true) }
            }
            castChips.add(moreChip)
            container.addView(moreChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }

        return container
    }

    private fun showFilmography(actor: CastMember, originView: View) {
        lastFocusedActor = originView
        background({
            val creditedTitles = runCatching {
                Tmdb.personCredits(actor.tmdbId, actor.name, actor.isDirector)
            }.getOrDefault(emptySet())

            val allMetas = Stremio.addons(key).flatMap { addon ->
                runCatching { addon.search(actor.name) }.getOrNull().orEmpty()
            }.distinctBy { it.id }.filter { it.id != metaId }

            val actorLower = actor.name.lowercase().trim()

            allMetas.sortedWith(compareByDescending<Meta> { meta ->
                val metaTitleLower = meta.name.lowercase().trim()
                var score = 0
                if (metaTitleLower in creditedTitles || creditedTitles.any { it.contains(metaTitleLower) || metaTitleLower.contains(it) }) {
                    score += 100
                }
                if (actor.isDirector) {
                    if (meta.directors.any { it.lowercase().contains(actorLower) || actorLower.contains(it.lowercase()) }) {
                        score += 80
                    }
                } else {
                    if (meta.cast.any { it.lowercase().contains(actorLower) || actorLower.contains(it.lowercase()) }) {
                        score += 80
                    }
                }
                if (score == 0 && metaTitleLower.contains(actorLower)) {
                    score -= 50
                }
                score
            }.thenByDescending { it.year ?: "" }).filter { meta ->
                val metaTitleLower = meta.name.lowercase().trim()
                val isCredited = metaTitleLower in creditedTitles || creditedTitles.any { it.contains(metaTitleLower) || metaTitleLower.contains(it) }
                    || (if (actor.isDirector) meta.directors.any { it.lowercase().contains(actorLower) } else meta.cast.any { it.lowercase().contains(actorLower) })
                isCredited || allMetas.size <= 4 || !metaTitleLower.contains(actorLower)
            }.take(16)
        }) { result ->
            val titles = result.getOrNull().orEmpty()
            if (titles.isEmpty()) {
                if (!infoOpen) showInfo(focusCast = true)
                return@background
            }
            val heading = if (actor.isDirector) getString(R.string.directed_by, actor.name) else actor.name
            buildFilmographyOverlay(heading, titles)
            openFilmography()
        }
    }

    private fun buildFilmographyOverlay(heading: String, titles: List<Meta>) {
        if (::filmographyOverlay.isInitialized) root.removeView(filmographyOverlay)
        filmographyOverlay = FrameLayout(this).apply { visibility = View.GONE }
        filmographyOverlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF70E0C0A.toInt(), 0xA60E0C0A.toInt(), 0x000E0C0A))
        }, FrameLayout.LayoutParams(-1, dp(280), Gravity.BOTTOM))
        val content = column(Gravity.START)
        val captionPill = caption("").apply {
            visibility = View.INVISIBLE
            maxWidth = dp(360)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(caps(heading).onArt())
            addView(captionPill, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        }
        content.addView(headerRow, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
        filmographyFirst = null
        titles.forEachIndexed { index, title ->
            val card = FrameLayout(this).apply {
                background = rounded(BASE, 6)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
                }
                clipToOutline = true
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(image, FrameLayout.LayoutParams(-1, -1))
                val fallback = text(title.name, SMALL, bold = true).apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(8), 0, dp(8), dp(8)); visibility = View.GONE
                }
                addView(fallback, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                cardFocus(6) { has ->
                    if (has) {
                        val yearStr = title.year?.take(4)?.let { "  ·  $it" } ?: ""
                        captionPill.text = "${title.name}$yearStr"
                        captionPill.visibility = View.VISIBLE
                    }
                }
                setOnClickListener {
                    startActivity(Intent(this@DetailsActivity, DetailsActivity::class.java)
                        .putExtra("type", title.type).putExtra("id", title.id).putExtra("name", title.name)
                        .putExtra("poster", title.poster).putExtra("background", title.background))
                }
                Images.load(title.poster ?: title.background, image, dp(POSTER_W), failed = {
                    fallback.visibility = View.VISIBLE
                })
            }
            if (filmographyFirst == null) filmographyFirst = card
            row.addView(card, LinearLayout.LayoutParams(dp(POSTER_W), dp(POSTER_H)).apply {
                if (index > 0) marginStart = dp(CARD_GAP)
            })
        }
        val room = dp(8)
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(room, room, room, room)
            addView(row)
        }, LinearLayout.LayoutParams(-1, dp(POSTER_H) + 2 * room).apply {
            leftMargin = -room
            rightMargin = -room
            topMargin = -room
            bottomMargin = -room
        })
        filmographyOverlay.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(38)
        })
        root.addView(filmographyOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openFilmography() {
        if (!::filmographyOverlay.isInitialized || filmographyOpen) return
        filmographyOpen = true
        if (infoOpen) {
            infoOverlay.visibility = View.INVISIBLE
        } else {
            hero.visibility = View.INVISIBLE
        }
        filmographyOverlay.visibility = View.VISIBLE
        filmographyOverlay.alpha = 0f
        filmographyOverlay.translationY = dp(20).toFloat()
        filmographyOverlay.animate().alpha(1f).translationY(0f).setDuration(220).start()
        filmographyFirst?.requestFocus()
    }

    private fun hideFilmography() {
        if (!filmographyOpen) return
        filmographyOpen = false
        filmographyOverlay.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(180).withEndAction {
            filmographyOverlay.visibility = View.GONE
            if (infoOpen) {
                infoOverlay.visibility = View.VISIBLE
            } else {
                hero.visibility = View.VISIBLE
            }
            (lastFocusedActor ?: if (infoOpen) infoOverlay else playButton).requestFocus()
        }.start()
    }

    // ---------- shared ----------

    private var resolvingStreams: Cancellable? = null
    private lateinit var loadingView: FrameLayout
    private lateinit var loadingStatus: TextView

    private fun cancelStreamResolution() {
        resolvingStreams?.cancel()
        resolvingStreams = null
        if (::loadingView.isInitialized) loadingView.visibility = View.GONE
    }

    private fun playTrailer(m: Meta) {
        if (resolvingStreams != null) return
        val ready = m.trailerStreams + imdbTrailers
        if (ready.isNotEmpty()) {
            startTrailerPlayer(m, ready)
            return
        }
        if (!m.id.startsWith("tt")) {
            openExternalTrailer(m.trailerYtId)
            return
        }
        var canceled = false
        resolvingStreams = object : Cancellable { override fun cancel() { canceled = true } }
        loadingStatus.text = getString(R.string.loading_trailer)
        loadingView.visibility = View.VISIBLE
        loadingView.bringToFront()
        background({ imdbTrailerStreams(m.id) }) { result ->
            if (canceled) return@background
            cancelStreamResolution()
            val streams = result.getOrDefault(emptyList())
            if (streams.isNotEmpty()) {
                imdbTrailers = streams
                startTrailerPlayer(m, streams)
            } else {
                openExternalTrailer(m.trailerYtId)
            }
        }
    }

    private fun startTrailerPlayer(m: Meta, streams: List<Stream>) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("type", type)
            .putExtra("id", metaId)
            .putExtra("videoId", "$metaId:trailer")
            .putExtra("name", m.name.ifBlank { name })
            .putExtra("poster", m.poster ?: poster)
            .putExtra("background", m.background)
            .putExtra("logo", m.logo)
            .putExtra("isTrailer", true)
            .putExtra("trailerYtId", m.trailerYtId)
            .putExtra("sources", org.json.JSONArray(streams.map { it.toJson() }).toString()))
    }

    private fun openExternalTrailer(ytId: String?) {
        val id = ytId?.takeIf { it.isNotBlank() } ?: return
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$id")))
        }.isSuccess
        if (!opened) runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$id")))
        }
    }

    private fun openSources(videoId: String, startOver: Boolean, next: String? = null, label: String? = null, pick: Boolean = false) {
        if (resolvingStreams != null) return
        val m = meta
        val nextId = next ?: m?.videos?.let { vs -> vs.getOrNull(vs.indexOfFirst { it.id == videoId } + 1)?.id }
        val video = m?.videos?.firstOrNull { it.id == videoId }
        val short = label ?: video?.let { "S${it.season} E${it.episode}" }
        val resumeAt = if (startOver) 0L else item?.let { Library.resumeAt(it, videoId) } ?: 0L
        val intent = Intent(this, StreamsActivity::class.java)
            .putExtra("type", type).putExtra("id", metaId).putExtra("name", m?.name ?: name).putExtra("poster", m?.poster ?: poster)
            .putExtra("videoId", videoId).putExtra("nextVideoId", nextId).putExtra("startOver", startOver).putExtra("pick", pick)
            .putExtra("background", m?.background)
            .putExtra("label", listOfNotNull(m?.name ?: name, short).joinToString("  ·  "))
            // For the sources page: the title's logo, the episode's still and "S1 E3 · OutKast".
            .putExtra("logo", m?.logo).putExtra("still", video?.thumbnail).putExtra("runtime", m?.runtime)
            .putExtra("blurStill", Settings.blurThumbnails(this) && video != null && video.id !in Library.watched(item, m?.videos.orEmpty()))
            .putExtra("episode", video?.let { v -> listOfNotNull("S${v.season} E${v.episode}", v.title.takeIf { it.isNotBlank() }).joinToString("  ·  ") })
            .putExtra("resumeAt", resumeAt)

        if (pick || Settings.alwaysList(this)) {
            startActivity(intent)
            return
        }

        startActivity(Intent(this, PlayerActivity::class.java).putExtras(intent))
    }

    override fun onPause() {
        cancelStreamResolution()
        super.onPause()
    }

    // Back from the player: progress, the resume point and My List may have changed.
    override fun onRestart() {
        super.onRestart()
        background({ runCatching { Stremio.library(key, listOf(metaId)).firstOrNull() }.getOrNull() }) { result ->
            item = result.getOrNull() ?: item
            val wasGuide = guideOpen
            if (infoOpen) { root.removeView(infoOverlay); infoOpen = false }
            hero.visibility = View.VISIBLE
            showHero(meta)
            meta?.videos?.takeIf { type == "series" && it.isNotEmpty() }?.let(::buildGuide)
            if (wasGuide) showGuide()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && resolvingStreams != null) {
                cancelStreamResolution()
                return true
            }
            if (guideOpen) {
                val focused = currentFocus
                if (focused in seasonButtons.values) when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { episodeRows.firstOrNull()?.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> return true
                }
                val at = episodeRows.indexOf(focused)
                if (at >= 0) when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                    KeyEvent.KEYCODE_DPAD_UP -> if (at == 0) { seasonButtons[selectedSeason]?.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (at == episodeRows.lastIndex) return true
                }
            } else if (filmographyOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                hideFilmography()
                return true
            } else if (similarOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                hideSimilar()
                return true
            } else if (infoOpen && !filmographyOpen) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (currentFocus === storyScrollView && infoCastCards.isNotEmpty()) {
                        infoCastCards.first().requestFocus()
                        return true
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (currentFocus?.let { v -> infoCastCards.any { it === v || isInside(v, it) } } == true) {
                        storyScrollView?.requestFocus()
                        return true
                    }
                }
            } else if (!infoOpen && !similarOpen && !filmographyOpen && currentFocus?.let { v -> castChips.any { it === v || isInside(v, it) } } == true) {
                val chipIndex = castChips.indexOfFirst { it === currentFocus || isInside(currentFocus!!, it) }
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { playButton.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> return true
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (chipIndex == 0) return true
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (chipIndex == castChips.lastIndex) return true
                }
            } else if (!infoOpen && !similarOpen && !filmographyOpen &&
                currentFocus?.let { it === playButton || (::buttonRow.isInitialized && isInside(it, buttonRow)) } == true) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    castChips.firstOrNull()?.requestFocus()
                    return true
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && currentFocus === playButton) {
                    return true
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && ::buttonRow.isInitialized &&
                    currentFocus === (0 until buttonRow.childCount).map { buttonRow.getChildAt(it) }.lastOrNull { it.visibility == View.VISIBLE && it.isFocusable }) {
                    return true
                }
            }
            if (!infoOpen && !similarOpen && !filmographyOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                currentFocus?.let { isInside(it, hero) } == true &&
                (if (type == "series") ::guide.isInitialized else ::similarOverlay.isInitialized)) {
                if (type == "series") showGuide() else showSimilar()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when {
            filmographyOpen -> hideFilmography()
            infoOpen -> hideInfo()
            guideOpen -> hideGuide()
            similarOpen -> hideSimilar()
            else -> super.onBackPressed()
        }
    }

    private companion object {
        const val GUIDE_ART = 0.6f // the film's brightness behind the episode guide
        const val STARTED = 60_000L // ms watched before a title counts as started
    }
}
