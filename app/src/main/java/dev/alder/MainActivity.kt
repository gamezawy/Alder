package dev.alder

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TextClock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal fun synopsisAtSentence(text: String, limit: Int): String {
    if (limit >= text.length) return text
    // A sentence ends at . ! or ? before a space, but not after "Sgt.", "Dr.", "U.S." and the like.
    val end = (limit - 1 downTo 0).firstOrNull { i ->
        text[i] in ".!?" && text.getOrNull(i + 1) == ' ' && !(text[i] == '.' && text.substring(text.lastIndexOf(' ', i) + 1, i)
            .let { word -> '.' in word || word.length <= 3 && word.firstOrNull()?.isUpperCase() == true })
    }
    if (end != null) return text.substring(0, end + 1)
    val clipped = text.take(limit).trimEnd()
    val whole = if (text.getOrNull(limit - 1)?.isLetterOrDigit() == true && text.getOrNull(limit)?.isLetterOrDigit() == true)
        clipped.substringBeforeLast(' ', clipped) else clipped
    return whole.trimEnd(' ', ',', ';', ':', '-', '.', '!', '?') + "…"
}

/** "Because you watched Crayon Shin-chan: Pursuit" → "More like Crayon Shin-chan". */
internal fun homeRowName(name: String): String {
    val prefix = "Because you watched "
    if (!name.startsWith(prefix, ignoreCase = true)) return name.trim().take(36).trimEnd()
    val title = name.substring(prefix.length).substringBefore(":").substringBefore(" (").substringBefore(" - ").trim()
    return "More like ${title.take(26).trimEnd()}"
}

internal fun seasons(count: Int) = if (count == 1) "1 season" else "$count seasons"

internal fun catalogGenres(names: List<String>): List<String> {
    val preferred = listOf("Drama", "Comedy", "Crime", "Sci-Fi", "Anime", "Documentary")
    return names.filterNot { it.equals("All", true) }.distinctBy { it.lowercase() }
        .sortedWith(compareBy<String> { name ->
            preferred.indexOfFirst { it.equals(name, true) }.let { if (it < 0) preferred.size else it }
        }.thenBy { it })
}

internal fun settledGenres(names: List<String>, results: Map<String, Boolean>): List<String>? =
    if (names.any { it !in results }) null else names.filter { results[it] == true }

/**
 * A row's short name: personal picks are "For you", charts "Popular", and a long themed name ("Short & Sweet
 * Adventure") keeps only the [genres] it names, so it can merge with that genre's row.
 */
internal fun shortRowName(name: String, genres: Collection<String> = emptyList()): String {
    val n = name.replace("Science Fiction", "Sci-Fi", ignoreCase = true).trim()
    return when {
        n.contains(" in India", true) && n.contains("recommend", true) -> n // a region's picks, not the viewer's
        n.contains("pick", true) || n.contains("recommend", true) -> "For you"
        n.contains("popular", true) || n.contains("trending", true) -> "Popular"
        n.length <= 18 -> n
        else -> genres.filter { n.contains(it, true) }.take(2).joinToString(" & ").ifEmpty { n.take(28).trimEnd() }
    }
}

internal fun isAddonMessage(name: String) = name.contains("Authentication Required", ignoreCase = true)

internal fun listRowCount(titles: Int, columns: Int) = (titles + columns - 1) / columns

internal fun nextCatalogSection(total: Int, loaded: Int) = minOf(total, loaded + 4)

internal fun homeSectionTitle(title: String, isMovie: Boolean): String {
    if (title.contains("movie", true) || title.contains("film", true) ||
        title.contains("show", true) || title.contains("series", true)) return title
    val kind = if (isMovie) "Movies" else "Shows"
    val singular = if (isMovie) "Movie" else "Show"
    return when {
        title.equals("For you", true) -> if (isMovie) "Movies for you" else "Shows for you"
        title.equals("Top Picks for You", true) -> "Top $singular Picks"
        title.equals("Recommended for You", true) -> "Recommended $kind"
        title.contains(" in ", true) -> title.replace(" in ", " $kind in ", ignoreCase = true)
        title.contains(" on ", true) -> title.replace(" on ", " $kind on ", ignoreCase = true)
        title.endsWith(" Now", true) -> title.removeSuffix(" Now") + " $kind"
        else -> "$title $kind"
    }
}

internal fun catalogAnswerKey(page: String, title: String, genre: String?, sources: List<String>) =
    (listOf(page, title, genre.orEmpty()) + sources).joinToString("\u0000")

/**
 * Catalog and title types that are live: TV channels, sport and events. Other types (anime, YouTube's video channels,
 * podcasts) have no page.
 */
internal fun isLiveType(type: String) = type.lowercase() in setOf("tv", "live", "sport", "sports", "event", "events")

/**
 * A channel logo's colour, for the wash behind it when the channel has no art: the average of its colourful opaque
 * pixels, dimmed so white words still read over it. Null for a logo with no colour to speak of (white, black, grey).
 */
internal fun logoTint(pixels: IntArray): Int? {
    val opaque = pixels.filter { it ushr 24 >= 128 }
    val colourful = opaque.filter { p ->
        val r = p ushr 16 and 255; val g = p ushr 8 and 255; val b = p and 255
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        max >= 40 && (max - min) >= 0.35f * max
    }
    if (colourful.isEmpty() || colourful.size * 20 < opaque.size) return null
    val r = colourful.sumOf { it ushr 16 and 255 } / colourful.size
    val g = colourful.sumOf { it ushr 8 and 255 } / colourful.size
    val b = colourful.sumOf { it and 255 } / colourful.size
    val scale = 120f / maxOf(r, g, b, 1)
    return (0xFF shl 24) or ((r * scale).toInt().coerceAtMost(255) shl 16) or
        ((g * scale).toInt().coerceAtMost(255) shl 8) or (b * scale).toInt().coerceAtMost(255)
}

// Home for the profile in use, built to feel like a cinema rather than a catalogue. The whole screen is one title's
// art: sharp where nothing sits on it, with light shading behind text and cards.
//  - Browse (every page, Home opening on Continue Watching): one row of wide 16:9 art cards along the bottom, the
//    focused title's logo, facts and description top left. Down and up slide whole rows in.
//  - Cinema (the screensaver): after IDLE without a key, one Continue Watching title fills the screen with its logo,
//    where you are and Resume, moving on to the next every ADVANCE. OK resumes; any other key returns to where you were.
//    The TV's own screensaver takes over later (10 min on the test TV), so nothing stays still for hours.
class MainActivity : Screen() {
    /** [label] names a page in intents and saved answers; [words] is what the top bar shows. */
    private enum class Page(val label: String, val words: Int) {
        JELLYFIN_HOME("JellyfinHome", R.string.tab_home), JELLYFIN_LIBRARY("JellyfinLibrary", R.string.provider_library),
        HOME("Home", R.string.tab_home), SHOWS("Shows", R.string.tab_shows), MOVIES("Movies", R.string.tab_movies),
        LIVE("Live", R.string.tab_live), LIBRARY("My List", R.string.tab_my_list)
    }
    private enum class Mode { CINEMA, BROWSE }
    private enum class ListFilter { ALL, MOVIES, SHOWS }
    private enum class ListSort { RECENT, NAME, UNWATCHED }

    private lateinit var profile: Profile
    private var jellyfinWasConnected = false
    override val backdrop get() = art
    private lateinit var scroller: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var header: FrameLayout
    private var updateDot: View? = null
    private lateinit var genreStrip: HorizontalScrollView
    private val genreChips = mutableMapOf<String?, TextView>()
    private val listFilterChips = mutableMapOf<ListFilter, TextView>()
    private val listSortChips = mutableMapOf<ListSort, TextView>()
    private var lastListChip: TextView? = null
    private val navItems = mutableMapOf<Page, TextView>()
    private var page = Page.HOME
    private val Page.isCatalog get() = this == Page.SHOWS || this == Page.MOVIES
    /** One row of cards under the focused title's art: every page but My List's grid. */
    private val Page.isBrowse get() = this == Page.JELLYFIN_HOME || this == Page.JELLYFIN_LIBRARY || this == Page.HOME || isCatalog || this == Page.LIVE
    private val Page.isJellyfin get() = this == Page.JELLYFIN_HOME || this == Page.JELLYFIN_LIBRARY
    private var mode = Mode.BROWSE
    private var library: List<JSONObject> = emptyList()
    // ponytail: row answers this session, including their catalogs; unbounded, but a few hundred titles per page.
    private val answers = java.util.concurrent.ConcurrentHashMap<String, List<Pair<List<Meta>, Boolean>>>()
    private var genre: String? = null
    private var jumpTo: String? = null // a chip's row that wasn't made yet when OK was pressed
    private var listFilter = ListFilter.ALL
    private var listSort = ListSort.RECENT
    private var librarySlot: FrameLayout? = null
    private var listTotal = 0
    private var listRows = 0
    private var listBuild = 0
    private lateinit var listCount: TextView
    private lateinit var listHint: TextView
    private lateinit var heroAdded: TextView
    private var search: ImageView? = null
    private var liveTv: ImageView? = null
    private val rows = mutableListOf<Row>()
    private data class JellyfinShelf(
        val title: String, val items: List<JellyfinItem>, val parentId: String? = null,
        val includeTypes: List<String> = emptyList(), val pageable: Boolean = false,
        val wide: Boolean = false, val continueWatching: Boolean = false
    )
    private val jellyfinShelfCache = java.util.concurrent.ConcurrentHashMap<String, List<JellyfinShelf>>()
    private var refreshJellyfinRows: (() -> Unit)? = null
    private var homeContinueSlot: FrameLayout? = null
    private var stremioContinueForHome: List<Item> = emptyList()
    private var jellyfinContinueForHome: List<Item> = emptyList()
    private var homeContinueSignature: String? = null
    private var focusedSomething = false
    private val metas = mutableMapOf<String, Meta>() // full details, fetched once per title

    // The art, at full strength and full bleed, over the previous title's art while it crossfades in.
    private lateinit var art: ImageView
    private lateinit var artBack: ImageView
    // Not glass: a light darkening of the art's top edge while the top bar shows. The left and bottom shades are painted
    // into the art itself ([shaded]).
    private lateinit var topShade: View

    // A chevron in the right margin while the row the remote is in has more cards past the screen's edge.
    private lateinit var moreHint: ImageView
    private lateinit var nextRowHint: TextView
    private lateinit var cardCaption: TextView
    private var captionX = 0 // px from the left margin to the focused card, where its caption goes
    private var browseRowTop = 0
    private var browseRowH = 0

    private var artTask = Runnable {}
    private val artW by lazy { resources.displayMetrics.widthPixels }

    /**
     * The art cropped to the screen with its shades painted in, once per picture: the left darkening behind the words
     * and the long one down to the cards. As views over the art they were two more layers the GPU blended every frame,
     * a third of this TV's frame time.
     */
    private val shaded = Images.Look("shaded") { src ->
        val w = artW
        val h = resources.displayMetrics.heightPixels
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
        val dw = src.width * scale
        val dh = src.height * scale
        canvas.drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        val bright = samplePixels(src, 16, 24)?.let { brightLeft(it, 16) } ?: false
        shade(canvas, w, h, bright)
        src.recycle()
        out.copy(Bitmap.Config.HARDWARE, false).also { out.recycle() }
    }

    /**
     * A live channel with no art of its own: the dark base with a soft glow of its logo's colour (the logo itself sits
     * top left, where a film's title goes), shaded like any other art. A logo without colour leaves the plain base.
     */
    private val washed = Images.Look("washed") { src ->
        val w = artW
        val h = resources.displayMetrics.heightPixels
        val tint = samplePixels(src, 24, 24)?.let(::logoTint)
        src.recycle()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(BASE)
        if (tint != null) canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = RadialGradient(w * 0.7f, h * 0.32f, w * 0.7f, tint and 0x00FFFFFF or (0xB3 shl 24), tint and 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
        shade(canvas, w, h, false)
        out.copy(Bitmap.Config.HARDWARE, false).also { out.recycle() }
    }

    /** The left darkening behind the words, the long one down to the cards, and a light one under the top bar. */
    private fun shade(canvas: Canvas, w: Int, h: Int, bright: Boolean) {
        val shade = Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.55f, 0f,
                if (bright) 0xF70E0C0A.toInt() else 0xEB0E0C0A.toInt(), 0x000E0C0A, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w * 0.55f, h.toFloat(), shade)
        shade.shader = LinearGradient(0f, h.toFloat(), 0f, h * 0.55f, 0xF70E0C0A.toInt(), 0x000E0C0A, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), shade)
        shade.shader = LinearGradient(0f, 0f, 0f, h * 0.14f, 0x8C0E0C0A.toInt(), 0x000E0C0A, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.14f, shade)
        for (edge in listOf(GradientDrawable.Orientation.LEFT_RIGHT, GradientDrawable.Orientation.RIGHT_LEFT,
            GradientDrawable.Orientation.TOP_BOTTOM, GradientDrawable.Orientation.BOTTOM_TOP)) {
            GradientDrawable(edge, intArrayOf(0x73000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000))
                .apply { setBounds(0, 0, w, h); draw(canvas) }
        }
    }

    // Browse: the focused title, top left.
    private var heroItem: Item? = null
    private lateinit var browseInfo: LinearLayout
    private lateinit var heroName: TextView
    private lateinit var heroLogo: ImageView
    private lateinit var heroFacts: TextView
    private lateinit var heroRating: TextView
    private lateinit var heroScore: TextView
    private var heroBottom = 0 // px from the screen's foot to the hero block, for a poster row
    private var wideRow = false // the row the remote is in has wide cards (Continue Watching): the hero sits lower
    private lateinit var heroAbout: TextView
    private lateinit var heroActions: LinearLayout
    private lateinit var heroResume: TextView

    // Cinema: one title, low on the left; the stage takes the remote's keys while it shows.
    private var cinema: List<Item> = emptyList()
    private var cinemaAt = 0
    private lateinit var stage: FrameLayout
    private lateinit var cinemaInfo: LinearLayout
    private lateinit var cinemaName: TextView
    private lateinit var cinemaLogo: ImageView
    private lateinit var cinemaClock: TextClock // the cinema's own large clock; the top bar has the browsing one
    private lateinit var cinemaLine: TextView
    private lateinit var cinemaPlay: TextView
    private lateinit var dots: LinearLayout

    /** A title in a row or the cinema. Details the catalog didn't send are filled in from the meta addon. */
    private class Item(
        val id: String, val type: String, val name: String, val poster: String?,
        var art: String?, var logo: String?, var about: String, var facts: List<String>,
        val videoId: String?, val progress: Float,
        val resume: Boolean, // has watch progress (Continue Watching, Library): its facts come from the meta addon
        val left: String?, // Continue Watching: "S1 E3 · 31 min left"
        val timeLeft: String? = null, // the "31 min left" part of [left]
        val stoppedAt: Long? = null,
        val addedAt: String? = null,
        val finished: Boolean = false,
        var newEpisode: Boolean = false,
        val providerItem: JellyfinItem? = null,
    ) {
        val live get() = isLiveType(type) // a channel or event: plays from its card, no title page
        var filled = false
        var settled = false // its details are in (or failed): what it has now is all it will get
        var warming = false
        val onFill = mutableListOf<() -> Unit>()
    }

    // Title details, newest request first, so the cards on screen get theirs before the ones scrolled past.
    private val metaPool = Images.newestFirst(6)
    private var generation = 0 // which showPage answers belong to: late ones from an earlier page are dropped
    private var loadMoreSections: (() -> Unit)? = null
    private var touched = false // a key was pressed since the page opened: fresh data then waits for the next visit
    private var cinemaReady = false // the cinema's title has its art and logo (or gave up waiting) and may show
    private var cinemaToken = Any()
    private var beforeCinema: View? = null // where the remote was when the cinema took over
    private var cinemaAwake = false
    private var cinemaWakeKey = -1
    private val idle = Runnable { enterCinema() }
    private val idleDim = Runnable { dimHome(true) }
    private var homeDimmed = false
    private val advance = Runnable { if (mode == Mode.CINEMA && cinema.size > 1) showCinema((cinemaAt + 1) % cinema.size) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = Profiles.active(this) ?: return switchProfile()
        jellyfinWasConnected = Jellyfin.account(this) != null
        main.postDelayed({ if (!isFinishing) whatsNewOnce() }, WHATS_NEW_WAIT)
        // Last launch's first cinema title: start its art and logo now, while the library is still being read.
        getSharedPreferences("home", MODE_PRIVATE).let { prefs ->
            Images.prefetch(prefs.getString("art:${profile.id}", null), artW, urgent = true, look = shaded)
            Images.prefetch(prefs.getString("logo:${profile.id}", null), dp(LOGO_W), urgent = true)
        }

        val screenH = resources.displayMetrics.heightPixels
        // A row heading's real height: the TV's font size setting can make it taller than any fixed guess.
        val headingH = caps("").lineHeight
        val rowH = headingH + dp(ROW_TOP_ROOM + CARD_H + ROW_BOTTOM_ROOM)
        val rowTop = screenH - rowH - dp(SAFE_BOTTOM) // one row on screen; the track in the left margin says where you are
        browseRowTop = rowTop
        browseRowH = rowH
        content = column(Gravity.START)
        scroller = object : ScrollView(this) {
            override fun requestChildFocus(child: View, focused: View) {
                super.requestChildFocus(child, focused)
                last = focused
                snapTo(focused)
            }

            // Rows snap into place (below) instead of scrolling just far enough, which left them cut off.
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0

            // The remote moves focus, and focus moves the rows; this never scrolls on its own. Otherwise Down on the
            // last row scrolled it away into the empty space below.
            override fun executeKeyEvent(event: KeyEvent) = false

            // Coming down from the top bar, the remote returns to where it was.
            override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
                val back = last?.takeIf { it.isAttachedToWindow && !hasFocus() && direction == View.FOCUS_DOWN }
                if (back != null) views.add(back) else super.addFocusables(views, direction, focusableMode)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            addView(content)
            setOnScrollChangeListener { _, _, _, _, _ -> updateImages(); loadMoreSections?.invoke() }
        }

        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        artBack = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        topShade = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x66000000, 0x1A000000, 0)); alpha = 0f }

        // Black from the window alone: a black layout too would be one more full screen painted under the art.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BASE))
        setContentView(FrameLayout(this).apply {
            addView(artBack, FrameLayout.LayoutParams(-1, -1))
            addView(art, FrameLayout.LayoutParams(-1, -1))
            addView(topShade, FrameLayout.LayoutParams(-1, dp(HEADER_H + 180)))
            addView(scroller, FrameLayout.LayoutParams(-1, rowH).apply { topMargin = rowTop })
            moreHint = ImageView(context).apply { setImageResource(R.drawable.ic_back); scaleX = -1f; alpha = 0f }
            addView(moreHint, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.END).apply {
                rightMargin = (dp(PAD) - dp(32)) / 2; topMargin = rowTop + headingH + dp(ROW_TOP_ROOM + CARD_H / 2 - 16)
            })
            cardCaption = caption("").apply { visibility = View.GONE }
            addView(cardCaption, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(CAPTION_BOTTOM)
            })
            nextRowHint = caps("").apply { visibility = View.GONE }
            addView(nextRowHint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(PAD); bottomMargin = dp(CAPTION_BOTTOM + 5)
            })
            listCount = text("", SMALL, color = TEXT_3).apply { visibility = View.GONE }
            addView(listCount, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(PAD); topMargin = dp(232)
            })
            // On glass: it sits over the next row's posters.
            listHint = text(R.string.home_hold_ok, SMALL, color = TEXT_2).apply {
                visibility = View.GONE
                background = glass(CHIP_H / 2)
                setPadding(dp(14), dp(4), dp(14), dp(5))
            }
            addView(listHint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(PAD); bottomMargin = dp(18)
            })
            // Sits on the row band, so a larger font size grows it upwards into the art, never down into the rows.
            addView(browseInfo(), FrameLayout.LayoutParams(dp(HERO_W + 2 * FOCUS_ROOM), -2, Gravity.BOTTOM).apply {
                heroBottom = screenH - rowTop + dp(HERO_GAP)
                // Room inside the block for the focused Resume to grow into (it was cut flat on the left).
                leftMargin = dp(PAD - FOCUS_ROOM); bottomMargin = heroBottom
            })
            addView(stage(), FrameLayout.LayoutParams(-1, -1))
            genreStrip = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                visibility = View.GONE
            }
            addView(genreStrip, FrameLayout.LayoutParams(-1, dp(44), Gravity.TOP).apply {
                topMargin = dp(HEADER_H + 6)
            })
            addView(header().apply { alpha = 0f }, FrameLayout.LayoutParams(-1, dp(HEADER_H))) // fades in if the page wants it
            val (loading, loadingText) = loadingOverlay()
            loadingView = loading
            loadingStatus = loadingText
            addView(loadingView, FrameLayout.LayoutParams(-1, -1))
        })

        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            rows.filter { it.active && (now == null || !isInside(now, it.view)) }.forEach { it.light(false) }
            if (mode == Mode.CINEMA) chrome() // the top bar shows only while the remote is on it
            // The caption names the focused card: none while the remote is up in the top bar.
            if (now != null && isInside(now, header)) cardCaption.visibility = View.GONE
            // Rows settle after a focus move (the next heading can peek in): name it once, whichever shows.
            main.removeCallbacks(nextRowCheck); main.postDelayed(nextRowCheck, 300)
        }
        val initialPage = Page.entries.firstOrNull { it.label == intent.getStringExtra("page") }
            ?: when (Settings.startTab(this)) {
                "stremio" -> Page.HOME
                "movies" -> if (Settings.showMovies(this)) Page.MOVIES else Page.HOME
                "shows" -> if (Settings.showShows(this)) Page.SHOWS else Page.HOME
                "library" -> Page.LIBRARY
                else -> Page.HOME
            }
        jellyfinWasConnected = Jellyfin.account(this) != null
        showPage(initialPage)
        main.postDelayed(::warmPages, WARM_AFTER)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Page.entries.firstOrNull { it.label == intent.getStringExtra("page") }
            ?.let { requested -> when (requested) {
                Page.JELLYFIN_HOME -> Page.HOME
                Page.JELLYFIN_LIBRARY -> Page.LIBRARY
                else -> requested
            } }?.takeIf { it != page }?.let(::showPage)
    }

    /**
     * Once Home has settled: the top rows' first posters on Shows and Movies, from last time's catalogs, so opening those
     * pages from the top bar draws them at once instead of as blank tiles.
     */
    /**
     * A page's rows: catalogs with the same purpose share a row, and Shows and Movies end with a row per main genre (the
     * first catalog that filters by it) unless a row of that name is already there; the chips jump to these rows.
     */
    private fun sections(p: Page, shelves: List<Pair<Addon.Catalog, Shelf>>, featuredHome: Boolean = false): List<Triple<String, List<Pair<Addon.Catalog, Shelf>>, String?>> {
        if (featuredHome) {
            val homeShelves = shelves.filter { it.second.page == Page.HOME }
            val movieShelves = shelves.filter { it.second.page == Page.MOVIES }
            val showShelves = shelves.filter { it.second.page == Page.SHOWS }
            val homeSections = homeShelves.map { Triple(it.second.title, listOf(it), null as String?) }
            val genres = emptyList<String>()
            val movieMerged = movieShelves.groupBy { shortRowName(it.second.title, genres) }
                .map { (name, list) -> Triple(homeSectionTitle(name, isMovie = true), list, null as String?) }
                .take(3)
            val showMerged = showShelves.groupBy { shortRowName(it.second.title, genres) }
                .map { (name, list) -> Triple(homeSectionTitle(name, isMovie = false), list, null as String?) }
                .take(3)
            val combined = mutableListOf<Triple<String, List<Pair<Addon.Catalog, Shelf>>, String?>>()
            val maxCount = maxOf(movieMerged.size, showMerged.size)
            for (i in 0 until maxCount) {
                if (i < movieMerged.size) combined += movieMerged[i]
                if (i < showMerged.size) combined += showMerged[i]
            }
            return homeSections + combined
        }
        val genres = catalogGenres(shelves.flatMap { it.first.genres })
        return if (!p.isCatalog) shelves.map { Triple(it.second.title, listOf(it), null as String?) } else {
            val merged = shelves.groupBy { shortRowName(it.second.title, genres) }.map { (name, list) -> Triple(name, list, null as String?) }
            merged + genres.take(GENRE_ROWS).filter { g -> merged.none { it.first.equals(g, true) } }
                .mapNotNull { g -> shelves.firstOrNull { g in it.first.genres }?.let { Triple(g, listOf(it), g as String?) } }
        }
    }

    /**
     * Shows' and Movies' rows read from disk into memory ahead, so the first switch to them builds at once too; and My
     * List's first three rows of posters fetched, so its grid doesn't open dark on the left while they download.
     */
    private fun warmPages() = background({
        Stremio.savedLibrary(profile.authKey).orEmpty()
            .filter { !it.optBoolean("removed") && it.optString("type") in listOf("movie", "series") }
            .sortedByDescending { it.optString("_ctime") }.take(LIST_COLUMNS * 3)
            .forEach { libraryItem(it).poster?.let { url -> Images.prefetch(url, (dp(LIST_W) * CARD_GROW).toInt()) } }
        val catalogs = Stremio.savedAddons(profile.authKey).orEmpty().flatMap { it.catalogs }
        for (p in listOf(Page.SHOWS, Page.MOVIES)) {
            val shelves = catalogs.mapNotNull { c -> shelf(c)?.takeIf { it.page == p }?.let { c to it } }.sortedBy { it.second.rank }
            sections(p, shelves).forEachIndexed { i, (title, sources, picked) ->
                val key = catalogAnswerKey(p.label, title, picked, sources.map { (c, _) ->
                    "${c.addon.transportUrl}/${c.type}/${c.id}"
                })
                if (answers.containsKey(key)) return@forEachIndexed
                val kept = sources.map { (c, _) -> (c.addon.savedCatalog(c, picked) ?: return@forEachIndexed) to true }
                answers.putIfAbsent(key, kept)
                if (i < 2) kept.flatMap { it.first }.take(FIRST).forEach { Images.prefetch(it.poster ?: it.background, (dp(CARD_W) * CARD_GROW).toInt()) }
                // The first title's full-screen art too, so the page opens on its own picture, not the last page's.
                if (i == 0) kept.firstOrNull()?.first?.firstOrNull()?.background?.let { Images.prefetch(it, artW, look = shaded) }
            }
        }
    }) {}

    private var lastFeaturedHome = false
    private var lastShowMovies = true
    private var lastShowShows = true
    private var lastShowLive = true
    private var lastHiddenRows: Set<String> = emptySet()

    // Back from a title or the player: Continue Watching may have moved on, so this asks Stremio, not the disk. Only
    // the library's rows are redrawn, so the remote stays where it was on the rest of the page.
    override fun onRestart() {
        super.onRestart()
        val featured = Settings.featuredHome(this)
        val showMovies = Settings.showMovies(this)
        val showShows = Settings.showShows(this)
        val showLive = Settings.showLive(this)
        val hiddenRows = Settings.hiddenRows(this)
        val hiddenRowsChanged = hiddenRows != lastHiddenRows
        lastHiddenRows = hiddenRows
        lastShowMovies = showMovies
        lastShowShows = showShows
        lastShowLive = showLive
        applyNavVisibility()
        if ((page == Page.MOVIES && !showMovies) || (page == Page.SHOWS && !showShows) || (page == Page.LIVE && !showLive)) {
            showPage(Page.HOME)
            return
        }
        if (hiddenRowsChanged || (page == Page.HOME && featured != lastFeaturedHome)) {
            lastFeaturedHome = featured
            showPage(page)
        } else {
            if (page == Page.HOME) renderHomeContinueRow(generation)
            refreshLibrary?.invoke()
            refreshAddons?.invoke()
            refreshJellyfinRows?.invoke()
        }
    }

    private var refreshAddons: (() -> Unit)? = null // the open page's add-on check, run again on coming back to Alder

    private var refreshLibrary: (() -> Unit)? = null // the open page's library rows (Home, Library), asked again
    private var last: View? = null // where the remote last was in the rows

    override fun onResume() {
        super.onResume()
        val connected = Jellyfin.account(this) != null
        if (::content.isInitialized && connected != jellyfinWasConnected) showPage(page)
        jellyfinWasConnected = connected
        // ponytail: idle dim and the cinema screensaver are off (2026-10-26, not wanted yet); post `idle` here to bring the cinema back.
        updateDot?.visibility = if (Updates.hasUpdate(this)) View.VISIBLE else View.GONE
        if (Updates.due(this)) background({ Updates.latest(applicationContext) }) { r ->
            val release = r.getOrNull()?.takeIf { newerVersion(it.version, Updates.mine(this)) } ?: return@background
            updateDot?.visibility = View.VISIBLE
            if (Updates.offerOnce(this, release.version)) choose(getString(R.string.updates_ready, release.version),
                listOf(getString(R.string.action_later), getString(R.string.action_install)),
                getString(R.string.updates_ready_desc)) { if (it == 1) installUpdate(release) }
        }
    }

    private var resolvingStreams: Cancellable? = null
    private lateinit var loadingView: FrameLayout
    private lateinit var loadingStatus: TextView

    private fun cancelStreamResolution() {
        resolvingStreams?.cancel()
        resolvingStreams = null
        if (::loadingView.isInitialized) loadingView.visibility = View.GONE
    }

    override fun onPause() {
        cancelStreamResolution()
        super.onPause()
        main.removeCallbacks(idle)
        main.removeCallbacks(idleDim)
        main.removeCallbacks(advance)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && resolvingStreams != null) {
                cancelStreamResolution()
                return true
            }
            touched = true
            dimHome(false)
        }
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (mode == Mode.CINEMA) {
            if (event.keyCode == cinemaWakeKey) {
                if (event.action == KeyEvent.ACTION_UP) cinemaWakeKey = -1
                return true
            }
            if (!cinemaAwake) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    cinemaAwake = true
                    cinemaWakeKey = event.keyCode
                    cinemaPlay.isFocusable = true
                    cinemaPlay.requestFocus()
                }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) enterBrowse()
                return true
            }
            if (!ok) return true
        }
        // Back from the rows goes up, not out: to the chips on a page that has them (a genre jump left you deep in
        // the page), else to the open page's name. Back from there leaves as usual.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && mode == Mode.BROWSE && scroller.hasFocus()) {
            if (event.action == KeyEvent.ACTION_UP) {
                val chip = if (page == Page.LIBRARY) lastListChip ?: listFilterChips[listFilter] else genreChips[genre]
                if (chip?.isShown != true || !chip.requestFocus()) navItems[page]?.requestFocus()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && (page.isCatalog || page == Page.LIBRARY) && mode == Mode.BROWSE) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && header.hasFocus()) {
                (if (page == Page.LIBRARY) lastListChip ?: listFilterChips[listFilter] else genreChips[genre])
                    ?.requestFocus()?.let { if (it) return true }
                if (page.isCatalog && genreStrip.visibility != View.VISIBLE && firstRow()?.focusFirst() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && genreStrip.hasFocus()) {
                (last?.takeIf { it.isAttachedToWindow && it.isShown } ?: firstRow()?.cards?.firstOrNull())
                    ?.requestFocus()?.let { if (it) return true }
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && currentFocus?.let { focused ->
                    firstRow()?.view?.let { isInside(focused, it) }
                } == true) {
                (if (page == Page.LIBRARY) lastListChip ?: listFilterChips[listFilter] else genreChips[genre])
                    ?.requestFocus()?.let { if (it) return true }
                if (page.isCatalog && genreStrip.visibility != View.VISIBLE && navItems[page]?.requestFocus() == true) return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && scroller.hasFocus() && mode == Mode.BROWSE &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            val ordered = rows.filter { it.view.isAttachedToWindow }.sortedBy { offsetIn(it.view) }
            val at = ordered.indexOfFirst { r -> currentFocus?.let { isInside(it, r.view) } == true }
            if (at >= 0) {
                val next = ordered.getOrNull(at + if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                if (next != null) {
                    next.view.visibility = View.VISIBLE
                    if (next.cards.getOrNull(minOf(ordered[at].selected, next.cards.lastIndex))?.requestFocus() == true) return true
                }
            }
        }
        // Up from Home's row goes to the top bar by hand: while the row slides after fast Right presses, the focus
        // search measures the moving card and sometimes finds nothing above it.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN && page == Page.HOME &&
            mode == Mode.BROWSE && currentFocus?.let { f -> firstRow()?.view?.let { isInside(f, it) } } == true) {
            if (navItems[page]?.requestFocus() == true) return true
        }
        // Down from the top bar goes to the titles, past Resume (one Up from the cards), to where the remote last was.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && header.hasFocus()) {
            val to = last?.takeIf { it.isAttachedToWindow && it.isShown } ?: firstRow()?.let { it.cards[it.selected] }
            if (to != null) { to.requestFocus(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dimHome(on: Boolean) {
        if ((!page.isBrowse && page != Page.LIBRARY) || mode != Mode.BROWSE || homeDimmed == on) return
        homeDimmed = on
        val alpha = if (on) 0.3f else 1f
        header.animate().alpha(alpha).setDuration(400).start()
        scroller.animate().alpha(alpha).setDuration(400).start()
        browseInfo.animate().alpha(if (heroItem == null) 0f else alpha).setDuration(400).start()
        cardCaption.animate().alpha(alpha).setDuration(400).start()
        nextRowHint.animate().alpha(alpha).setDuration(400).start()
        genreStrip.animate().alpha(alpha).setDuration(400).start()
        listCount.animate().alpha(alpha).setDuration(400).start()
        listHint.animate().alpha(alpha).setDuration(400).start()
    }

    private fun switchProfile() {
        startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    // ---------- top bar ----------

    private fun header(): View {
        header = object : FrameLayout(this) {
            // Coming up from below, the remote lands on the page you're on.
            override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
                val current = navItems[page]
                if (hasFocus() || direction != View.FOCUS_UP || current == null) super.addFocusables(views, direction, focusableMode)
                else views.add(current)
            }
        }.apply { setPadding(dp(PAD), dp(18), dp(PAD), dp(6)) }

        // The profile: a small round avatar on the margin. OK opens a small menu (switch profile, settings).
        val avatar = text(profile.name.take(1).uppercase(), SMALL, bold = true, color = TUNGSTEN).apply {
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.nav_profile)
            val rest = glassOver(this, 20)
            fun style(focused: Boolean) {
                background = if (focused) glass(20, true) else rest
                setTextColor(TUNGSTEN)
                grow(focused).start()
            }
            style(false)
            isFocusable = true
            setOnFocusChangeListener { _, has -> style(has) }
            setOnClickListener { profileMenu() }
        }
        val dot = View(this).apply {
            background = rounded(TUNGSTEN, 4)
            elevation = dp(4).toFloat()
            visibility = if (Updates.hasUpdate(this@MainActivity)) View.VISIBLE else View.GONE
        }
        updateDot = dot
        val avatarBox = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(avatar, FrameLayout.LayoutParams(dp(30), dp(30)))
            addView(dot, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(1)
                marginEnd = dp(1)
            })
        }
        // Every destination in one dark capsule, Search first as an icon (Left from Home), like Google TV's top bar.
        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        // A gap between pills, so the open page's and the focused one never touch.
        fun <V : View> V.inNav(width: Int = -2, height: Int = -2) = apply {
            isFocusable = true
            nav.addView(this, LinearLayout.LayoutParams(width, height).apply { if (nav.childCount > 0) marginStart = dp(NAV_GAP) })
        }
        // As tall as the words' pills (match parent), the glyph 18 dp inside it.
        search = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            contentDescription = getString(R.string.nav_search)
            setPadding(dp(13), dp(7), dp(13), dp(7))
            setOnFocusChangeListener { _, _ -> styleNav() }
            setOnClickListener { startActivity(Intent(this@MainActivity, SearchActivity::class.java)) }
        }.inNav(width = dp(44), height = -1)
        liveTv = ImageView(this).apply {
            setImageResource(R.drawable.ic_tv)
            contentDescription = getString(R.string.provider_live_tv)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnFocusChangeListener { _, _ -> styleNav() }
            setOnClickListener { startActivity(Intent(this@MainActivity, IptvActivity::class.java)) }
        }.inNav(width = dp(44), height = -1)
        // One destination bar for both sources. Jellyfin is the default Home/Library; Stremio remains
        // a clearly named section inside Alder rather than opening a second-looking home screen.
        // One navigation shell, matching Alder's original main-branch layout. Both providers contribute rows to it.
        val navOrder = Page.entries.filterNot { it.isJellyfin }
        navOrder.forEach { p ->
            navItems[p] = text(p.words, BODY).apply {
                gravity = Gravity.CENTER
                compoundDrawablePadding = dp(2)
                setPadding(dp(12), dp(4), dp(12), dp(2))
                setOnFocusChangeListener { _, has ->
                    styleNav()
                    main.removeCallbacks(switchPage)
                    if (has && p != page) { pending = p; main.postDelayed(switchPage, SWITCH_WAIT) }
                }
                setOnClickListener {
                    main.removeCallbacks(switchPage)
                    when {
                        p != page -> { jump = true; showPage(p) }
                        p.isCatalog -> genreChips[genre]?.requestFocus()
                        p == Page.LIBRARY -> (lastListChip ?: listFilterChips[listFilter])?.requestFocus()
                        else -> firstRow()?.focusFirst()
                    }
                }
                if (p == Page.LIVE) visibility = View.GONE
                if (p == Page.MOVIES && !Settings.showMovies(this@MainActivity)) visibility = View.GONE
                if (p == Page.SHOWS && !Settings.showShows(this@MainActivity)) visibility = View.GONE
            }.inNav()
        }
        nav.background = glassOver(nav, NAV_R + 4)
        header.addView(avatarBox, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        header.addView(TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            typeface = text("", SMALL).typeface
            textSize = SMALL
            setTextColor(TEXT_2)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        styleNav()
        return header
    }

    private var lastAddons: List<Addon> = emptyList()

    /** Merge both providers into one Continue Watching row, preferring the configured source for duplicates. */
    private fun renderHomeContinueRow(token: Int, opened: Long = android.os.SystemClock.uptimeMillis()) {
        val slot = homeContinueSlot ?: return
        if (token != generation || page != Page.HOME || isFinishing || isDestroyed) return
        val preferStremio = Settings.sourcePriority(this) == "stremio"
        val preferred = if (preferStremio) stremioContinueForHome else jellyfinContinueForHome
        val secondary = if (preferStremio) jellyfinContinueForHome else stremioContinueForHome
        val seen = hashSetOf<String>()
        val items = (preferred + secondary).filter { item ->
            val title = item.name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
            val year = item.facts.firstOrNull { it.firstOrNull()?.isDigit() == true }?.take(4).orEmpty()
            val type = if (item.type == "episode") "series" else item.type
            seen.add("${type}|${title}|${year}")
        }
        val signature = items.joinToString("|") {
            "${it.id}:${it.progress}:${it.resume}:${it.timeLeft.orEmpty()}"
        }
        if (signature == homeContinueSignature) return
        val focusedTag = currentFocus?.takeIf { isInside(it, slot) }?.tag
        homeContinueSignature = signature
        rows.filter { isInside(it.view, slot) }.forEach { old ->
            old.cards.forEach { it.unload() }
            rows.remove(old)
        }
        slot.removeAllViews()
        if (items.isNotEmpty()) {
            slot.addView(row(getString(R.string.row_continue), items.take(START), later(items)).view)
            startCinema(items.take(CINEMA_MAX))
            android.util.Log.d("Alder", "unified Continue in ${android.os.SystemClock.uptimeMillis() - opened} ms")
        } else {
            cinema = emptyList()
        }
        if (focusedTag != null) (slot.findViewWithTag<View>(focusedTag)
            ?: rows.firstOrNull { isInside(it.view, slot) }?.cards?.firstOrNull())?.requestFocus()
        main.post(::updateImages)
    }

    /**
     * Jellyfin shelves are ordinary Alder rows, placed before Stremio's catalog rows. A warm in-memory snapshot is
     * drawn immediately; the server refresh runs behind it and only redraws when the data actually changed.
     */
    private fun appendJellyfinRows(p: Page, token: Int, opened: Long) {
        if (p !in setOf(Page.HOME, Page.SHOWS, Page.MOVIES)) return
        val account = Jellyfin.account(this) ?: return
        val cacheKey = "${profile.id}|${account.serverUrl}|${account.userId}|${p.label}"
        val shelfSlot = slot()
        var shown: String? = null

        fun signature(shelves: List<JellyfinShelf>) = shelves.joinToString("||") { shelf ->
            shelf.title + "|" + shelf.items.joinToString(",") {
                "${it.id}:${it.played}:${it.playbackPositionTicks}:${it.imageTag.orEmpty()}"
            }
        }

        fun display(shelves: List<JellyfinShelf>) {
            if (token != generation || page != p || isFinishing || isDestroyed) return
            val next = signature(shelves)
            if (shown == next) return
            val focusedTag = currentFocus?.takeIf { isInside(it, shelfSlot) }?.tag
            shown = next
            jellyfinShelfCache[cacheKey] = shelves
            if (p == Page.HOME) {
                jellyfinContinueForHome = shelves.firstOrNull { it.continueWatching }
                    ?.items?.map(::jellyfinHomeItem).orEmpty()
                renderHomeContinueRow(token, opened)
            }
            rows.filter { isInside(it.view, shelfSlot) }.forEach { old ->
                old.cards.forEach { it.unload() }
                rows.remove(old)
            }
            shelfSlot.removeAllViews()
            shelves.filterNot { it.continueWatching }.forEach { shelf ->
                val all = shelf.items.map(::jellyfinHomeItem)
                if (all.isEmpty()) return@forEach
                val more: ((Int) -> List<Item>)? = if (shelf.pageable && shelf.parentId != null) { from ->
                    Jellyfin.items(applicationContext, shelf.parentId, shelf.includeTypes, limit = PAGE, startIndex = from)
                        .map(::jellyfinHomeItem)
                } else later(all)
                shelfSlot.addView(row(shelf.title, all.take(START), more, wide = shelf.wide).view)
            }
            if (focusedTag != null) (shelfSlot.findViewWithTag<View>(focusedTag)
                ?: rows.firstOrNull { isInside(it.view, shelfSlot) }?.cards?.firstOrNull())?.requestFocus()
            main.post(::updateImages)
        }

        jellyfinShelfCache[cacheKey]?.let(::display)

        fun refresh() {
            background({ loadJellyfinShelves(p) }) { result ->
                if (token != generation || page != p || isFinishing || isDestroyed) return@background
                result.onSuccess { shelves ->
                    display(shelves)
                    android.util.Log.d("Alder", "Jellyfin shelves on ${p.label}: ${android.os.SystemClock.uptimeMillis() - opened} ms")
                }.onFailure { error ->
                    android.util.Log.w("Alder", "Jellyfin shelves on ${p.label} failed: $error")
                }
            }
        }
        refreshJellyfinRows = ::refresh
        refresh()
    }

    private fun loadJellyfinShelves(p: Page): List<JellyfinShelf> {
        if (Jellyfin.account(applicationContext) == null) return emptyList()
        val views = runCatching { Jellyfin.views(applicationContext) }.getOrDefault(emptyList())
        return when (p) {
            Page.HOME -> buildList {
                val resume = runCatching { Jellyfin.resumeItems(applicationContext) }.getOrDefault(emptyList())
                    .filter { it.type.equals("Movie", true) || it.type.equals("Series", true) || it.type.equals("Episode", true) }
                if (resume.isNotEmpty()) add(JellyfinShelf(
                    "", resume, continueWatching = true
                ))
                views.forEach { view ->
                    val latest = runCatching { Jellyfin.latestItems(applicationContext, view.id, 18) }
                        .getOrDefault(emptyList())
                        .filter {
                            it.type.equals("Movie", true) || it.type.equals("Series", true) || it.type.equals("Episode", true)
                        }
                        .distinctBy { (it.seriesName?.takeIf { series -> series.isNotBlank() } ?: it.name).lowercase(Locale.ROOT) }
                    if (latest.isNotEmpty()) add(JellyfinShelf(
                        getString(R.string.provider_recently_added, view.name), latest
                    ))
                }
            }
            Page.SHOWS, Page.MOVIES -> {
                val types = if (p == Page.SHOWS) listOf("Series") else listOf("Movie")
                views.mapNotNull { view ->
                    val items = runCatching {
                        Jellyfin.items(applicationContext, view.id, includeTypes = types, limit = START)
                    }.getOrDefault(emptyList())
                    items.takeIf { it.isNotEmpty() }?.let {
                        JellyfinShelf(view.name, it, parentId = view.id, includeTypes = types, pageable = true)
                    }
                }
            }
            else -> emptyList()
        }
    }

    private fun jellyfinHomeItem(source: JellyfinItem): Item {
        val episode = source.type.equals("Episode", true)
        val type = when (source.type.lowercase(Locale.ROOT)) {
            "movie" -> "movie"
            "series" -> "series"
            "episode" -> "episode"
            else -> source.type.lowercase(Locale.ROOT)
        }
        val poster = Jellyfin.imageUrl(this, source.id, source.imageTag, 720)
        val backdrop = Jellyfin.backdropUrl(this, source.id, 1800)
        val duration = source.runTimeTicks ?: 0L
        val progress = if (duration > 0L) (source.playbackPositionTicks.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val remainingMinutes = if (duration > 0L && source.playbackPositionTicks > 0L)
            ((duration - source.playbackPositionTicks).coerceAtLeast(0L) / 600_000_000L).toInt() else 0
        val timeLeft = if (remainingMinutes > 0) {
            if (remainingMinutes >= 60) getString(R.string.time_left_hours, remainingMinutes / 60, remainingMinutes % 60)
            else getString(R.string.time_left_minutes, remainingMinutes)
        } else null
        val episodeLabel = if (episode) listOfNotNull(source.seasonNumber?.let { "S$it" }, source.episodeNumber?.let { "E$it" }).joinToString(" ") else null
        val displayName = if (episode) source.seriesName?.takeIf { it.isNotBlank() } ?: source.name else source.name
        return Item(
            source.id, type, displayName, poster, backdrop, null, source.overview,
            listOfNotNull(source.year?.toString(), source.officialRating, source.genres.take(2).joinToString(", ").ifBlank { null }),
            null, progress, resume = progress > 0f, left = listOfNotNull(episodeLabel, timeLeft).joinToString(DOT).ifBlank { null },
            timeLeft = timeLeft, stoppedAt = source.playbackPositionTicks / 10_000L, finished = source.played,
            providerItem = source
        ).also { it.filled = true; it.settled = true }
    }



    /** Live in the top bar only for a profile whose add-ons list channels, sport or events, and if allowed in Settings. */
    private fun showLiveTab(addons: List<Addon>) {
        lastAddons = addons
        val live = addons.any { a -> a.catalogs.any { shelf(it)?.page == Page.LIVE } }
        navItems[Page.LIVE]?.visibility = if ((live && Settings.showLive(this)) || page == Page.LIVE) View.VISIBLE else View.GONE
    }

    private fun applyNavVisibility() {
        navItems[Page.MOVIES]?.visibility = if (Settings.showMovies(this) || page == Page.MOVIES) View.VISIBLE else View.GONE
        navItems[Page.SHOWS]?.visibility = if (Settings.showShows(this) || page == Page.SHOWS) View.VISIBLE else View.GONE
        val live = lastAddons.any { a -> a.catalogs.any { shelf(it)?.page == Page.LIVE } }
        navItems[Page.LIVE]?.visibility = if ((live && Settings.showLive(this)) || page == Page.LIVE) View.VISIBLE else View.GONE
    }

    private var pending = Page.HOME
    private var jump = false // OK on a page's name: go down into its rows once they're in
    private val switchPage = Runnable { if (pending != page && navItems[pending]?.isFocused == true) { jump = false; showPage(pending) } }

    private fun profileMenu() {
        val update = Updates.available(this)
        val usual = listOf(getString(R.string.menu_switch_profile), getString(R.string.menu_settings))
        val options = if (update != null) listOf(getString(R.string.menu_install_update, update.version)) + usual else usual
        choose(profile.name, options) { i ->
            if (update != null) {
                when (i) {
                    0 -> installUpdate(update)
                    1 -> switchProfile()
                    2 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            } else {
                if (i == 0) switchProfile() else startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    /**
     * A lit pill follows the remote (as on Search); a tungsten dot marks the open page.
     */
    private fun styleNav() {
        navItems.forEach { (p, item) ->
            val focused = item.isFocused
            item.background = if (focused) glass(NAV_R, true) else null
            item.setTextColor(if (p == page || focused) TEXT else TEXT_2)
            item.text = getString(p.words)
            // The open page's dot hangs under its name; the others keep an empty one, so every name sits on one line.
            item.setCompoundDrawablesRelative(null, null, null, GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (p == page) TUNGSTEN else Color.TRANSPARENT)
                setBounds(0, 0, dp(4), dp(4))
            })
        }
        search?.let {
            it.background = if (it.isFocused) glass(NAV_R, true) else null
            it.setColorFilter(if (it.isFocused) TEXT else TEXT_2)
        }
        liveTv?.let {
            it.background = if (it.isFocused) glass(NAV_R, true) else null
            it.setColorFilter(if (it.isFocused) TEXT else TEXT_2)
        }
    }

    // ---------- the art, and what shows over it ----------

    /**
     * Shows [item]'s art across the screen, crossfading from the last title's: the old picture stays underneath until
     * the new one is in, so nothing flashes dark or soft. Art that's already here shows at once; otherwise it waits a
     * beat, so racing along a row doesn't start a download per card.
     */
    private fun showArt(item: Item, ready: (() -> Unit)? = null) {
        main.removeCallbacks(artTask)
        if (item.live && item.art == null && item.poster == null) return run { if (item.settled) clearArt(); ready?.invoke() }
        val (url, look) = backdropOf(item) ?: return
        if (url == art.tag) return run { ready?.invoke() }
        artTask = Runnable {
            art.animate().cancel()
            // Racing along a row cancels fades midway: only a picture that's mostly in replaces the one behind,
            // otherwise the back keeps the last full picture and nothing flashes black.
            if (art.drawable != null && art.alpha > 0.5f) {
                artBack.setImageDrawable(art.drawable)
                artBack.scaleX = art.scaleX
                artBack.scaleY = art.scaleY
            }
            art.scaleX = 1f
            art.scaleY = 1f
            art.alpha = 0f
            fun revealArt() {
                // Once the new art covers it, the old one lets go: otherwise the GPU paints a hidden full screen every frame.
                art.animate().alpha(1f).setDuration(if (artBack.drawable == null) 800 else 600).withEndAction {
                    artBack.setImageDrawable(null)
                    art.animate().scaleX(1.03f).scaleY(1.03f).setDuration(20_000).start()
                }.start()
                refrost()
                ready?.invoke()
            }
            val posterFallback = item.poster?.takeIf { item.art != null && it != item.art }
            Images.load(url, art, artW, urgent = true, look = look, failed = {
                if (posterFallback != null) {
                    Images.load(posterFallback, art, artW, urgent = true, look = look, failed = ready) {
                        revealArt()
                    }
                } else ready?.invoke()
            }) {
                revealArt()
            }
        }
        if (Images.cached(url, artW, look) || ready != null) artTask.run() else main.postDelayed(artTask, 120)
    }

    /**
     * What fills the screen behind [item]: its art; once it's certain there's none, a live channel's logo as a colour
     * wash, or else the poster (a poster cropped to the screen is blurry and half a face, so only as a last resort).
     */
    private fun backdropOf(item: Item): Pair<String, Images.Look>? = item.art?.let { it to shaded }
        ?: item.poster?.takeIf { item.settled }?.let { it to if (item.live) washed else shaded }

    /** A live channel with no pictures at all: the plain dark base, rather than the last title's art staying on. */
    private fun clearArt() {
        art.animate().cancel()
        art.tag = null
        artBack.setImageDrawable(null)
        art.animate().alpha(0f).setDuration(300).withEndAction { if (art.tag == null) art.setImageDrawable(null) }.start()
    }

    /** Gets [item]'s full-screen art ready ahead (fetching its details first if need be), so moving to it is instant. */
    private fun warm(item: Item) {
        if (item.warming) return
        item.warming = true
        item.logo?.let { Images.prefetch(it, dp(LOGO_W)) }
        if (item.art != null) return Images.prefetch(item.art, artW, look = shaded)
        item.onFill += { backdropOf(item)?.let { (url, look) -> Images.prefetch(url, artW, look = look) }; item.logo?.let { Images.prefetch(it, dp(LOGO_W)) } }
        fill(item)
    }

    /** What's on screen for the mode. */
    private fun chrome() {
        val cinemaOn = mode == Mode.CINEMA
        val headerOn = !cinemaOn || header.hasFocus()
        header.animate().alpha(if (headerOn) 1f else 0f).setDuration(250).start()
        topShade.animate().alpha(if (headerOn) 1f else 0f).setDuration(250).start()
        // Lighter in the cinema (only its few words sit low on the left), full while browsing under the cards.
        browseInfo.animate().alpha(if (cinemaOn || heroItem == null) 0f else 1f).setDuration(250).start()
        cardCaption.alpha = if (cinemaOn || page == Page.LIBRARY) 0f else 1f
        nextRowHint.alpha = if (cinemaOn || page == Page.LIBRARY) 0f else 1f
        genreStrip.alpha = if (cinemaOn) 0f else 1f
        listCount.alpha = if (cinemaOn) 0f else 1f
        listHint.alpha = if (cinemaOn) 0f else 1f
        if (cinemaOn) {
            moreHint.animate().alpha(0f).setDuration(250).start()
        }
        cinemaInfo.animate().alpha(if (cinemaOn && cinemaReady) 1f else 0f).setDuration(250).start()
        cinemaClock.animate().alpha(if (cinemaOn && !headerOn) 1f else 0f).setDuration(250).start()
        dots.animate().alpha(if (cinemaOn && cinemaReady && cinema.size > 1) 1f else 0f).setDuration(250).start()
    }

    // ---------- cinema ----------

    private fun stage(): View {
        cinemaName = text("", SERIF_HERO, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(520) }
        cinemaLogo = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; maxHeight = dp(LOGO_H); maxWidth = dp(LOGO_W) }
        cinemaLine = text("", SMALL, color = TEXT).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(INFO_W) }
        cinemaPlay = pill(R.string.action_resume, R.drawable.ic_play).apply {
            isFocusable = false
            setOnClickListener { cinema.getOrNull(cinemaAt)?.let(::play) }
        }
        cinemaInfo = column(Gravity.START,
            FrameLayout(this).apply { addView(cinemaName); addView(cinemaLogo) },
            cinemaLine.marginTop(14),
            cinemaPlay.marginTop(22),
        ).apply { alpha = 0f }
        // Page dots as tvOS draws them: small and quiet, the current one a short pill.
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; alpha = 0f }
        stage = FrameLayout(this).apply {
            addView(cinemaInfo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(PAD); bottomMargin = dp(64) })
            addView(dots, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(30) })
            cinemaClock = TextClock(context).apply {
                alpha = 0f
                format12Hour = "h:mm a"; format24Hour = "HH:mm"
                typeface = text("", SERIF_TITLE, serif = true).typeface
                textSize = SERIF_TITLE
                setTextColor(TEXT)
            }
            addView(cinemaClock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(27); rightMargin = dp(PAD) })
            isFocusable = false
        }
        return stage
    }

    /** Cinema title [index]: its art, logo, where you are (episode, its name, time left) and Resume. */
    private fun showCinema(index: Int) {
        val item = cinema.getOrNull(index) ?: return
        val same = cinemaAt == index && cinemaReady && cinemaName.text.toString() == item.name
        cinemaAt = index
        fill(item)
        // A new title's words come in with its picture, once both its art and logo are here (or after a moment
        // without them), instead of the name, then the logo, then the art popping in one after another.
        val token = Any().also { cinemaToken = it }
        if (!same) { cinemaReady = false; chrome() }
        var waiting = 2
        fun part() {
            if (cinemaToken !== token || --waiting > 0 || cinemaReady) return
            cinemaReady = true
            chrome()
        }
        main.postDelayed({ if (cinemaToken === token && !cinemaReady && item.settled) { waiting = 1; part() } }, REVEAL_WAIT)
        cinemaName.text = item.name
        cinemaName.visibility = View.INVISIBLE
        if (!same) { cinemaLogo.setImageDrawable(null); cinemaLogo.tag = null }
        val logoUrl = item.logo
        if (logoUrl == null) { cinemaName.visibility = View.VISIBLE; part() }
        else Images.load(logoUrl, cinemaLogo, dp(LOGO_W), urgent = true, failed = { cinemaName.visibility = View.VISIBLE; part() }) { part() }
        cinemaLine.text = where(item)
        cinemaPlay.text = getString(if (item.progress > 0) R.string.action_resume else R.string.action_play)
        dots.removeAllViews()
        cinema.indices.forEach { i ->
            dots.addView(View(this).apply { background = rounded(if (i == index) TUNGSTEN else TEXT_3, 3) },
                LinearLayout.LayoutParams(dp(if (i == index) 18 else 6), dp(6)).apply { marginStart = dp(if (i > 0) 8 else 0) })
        }
        if (item.art != null || item.settled) showArt(item) { part() } // else its details call this again
        cinema.getOrNull((index + 1) % cinema.size)?.let(::warm)
        main.removeCallbacks(advance)
        if (mode == Mode.CINEMA) main.postDelayed(advance, ADVANCE)
    }

    private fun enterCinema() {
        if (cinema.isEmpty() || mode == Mode.CINEMA) return
        beforeCinema = currentFocus
        cinemaAt = 0
        cinemaAwake = false
        cinemaWakeKey = -1
        cinemaPlay.isFocusable = false
        mode = Mode.CINEMA
        stage.isFocusable = true
        stage.requestFocus()
        scroller.animate().translationY(dp(RISE).toFloat()).alpha(0f).setDuration(300).withEndAction {
            if (mode == Mode.CINEMA) scroller.visibility = View.INVISIBLE
        }.start()
        chrome()
        showCinema(cinemaAt)
    }

    /** Wakes from the cinema to where the remote was; the card taking focus brings its own art back. */
    private fun enterBrowse() {
        mode = Mode.BROWSE
        dimHome(false)
        main.removeCallbacks(advance)
        scroller.visibility = View.VISIBLE
        scroller.animate().translationY(0f).alpha(1f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        chrome()
        val back = beforeCinema?.takeIf { it.isAttachedToWindow && it.isShown } ?: firstRow()?.let { it.cards[it.selected] }
        stage.isFocusable = false
        back?.requestFocus()
        heroItem?.let(::showHero) // a card taking focus does this itself; the top bar doesn't
    }

    // ---------- browse: the focused title, top left ----------

    private fun browseInfo(): View {
        heroName = text("", SERIF_HERO, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        // adjustViewBounds resets the scale type to FIT_CENTER, so FIT_START comes after it (logo on the margin).
        heroLogo = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; maxHeight = dp(55) }
        heroFacts = text("", SMALL, color = TEXT_2).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        // A small tag, as tall as the letters: SMALL's leading and font padding made it a block twice the mock's.
        heroRating = imdbTag().apply { visibility = View.GONE }
        heroScore = text("", SMALL, color = TEXT_2).onArt().apply { visibility = View.GONE }
        heroAdded = text("", SMALL, color = TEXT_3).onArt().apply { visibility = View.GONE }
        heroAbout = text("", BODY, color = TEXT).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        heroResume = pill(R.string.action_resume, R.drawable.ic_play).apply {
            setOnClickListener { heroItem?.let(::play) }
            setOnLongClickListener { heroItem?.let { play(it, pick = true) }; true }
        }
        // Only Resume: OK on any card already opens its title page. It shows only for a title in progress, which lives in
        // the first row, so Up from any other row goes straight to the row above.
        heroActions = LinearLayout(this).apply { clipChildren = false; addView(heroResume) }
        // No progress line here: the focused card already shows it, right below.
        browseInfo = column(Gravity.START,
            FrameLayout(this).apply {
                addView(heroName, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
                addView(heroLogo, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            },
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(heroFacts)
                addView(heroRating, wrap().apply { marginStart = dp(10) })
                addView(heroScore, wrap().apply { marginStart = dp(6) })
                addView(heroAdded, wrap().apply { marginStart = dp(6) })
            }.marginTop(8),
            heroAbout.marginTop(8),
            heroActions.marginTop(16),
        ).apply {
            alpha = 0f
            setPadding(dp(FOCUS_ROOM), 0, dp(FOCUS_ROOM), 0)
            // Too tall for the art above the rows (a large font size, a two-line name): the description gives up lines
            // until the block clears the top bar's words (they end well above its 76 dp). showHero gives them back for
            // the next title.
            addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
                if (page == Page.HOME || page == Page.LIBRARY) return@addOnLayoutChangeListener
                val lines = heroAbout.maxLines
                if (top < dp(HEADER_H - 12) && lines > 0) heroAbout.post { heroAbout.setLines(lines - 1) }
            }
        }
        return browseInfo
    }

    /** The focused title: its art across the screen, and logo, facts (and time left) and description top left. */
    private fun showHero(item: Item) {
        heroItem = item
        heroName.text = item.name
        heroName.visibility = View.VISIBLE
        heroLogo.visibility = View.GONE
        heroLogo.setImageDrawable(null)
        Images.load(item.logo, heroLogo, dp(LOGO_W), failed = { if (heroItem === item) heroName.visibility = View.VISIBLE }) {
            if (heroItem === item) { heroLogo.visibility = View.VISIBLE; heroName.visibility = View.INVISIBLE }
        }
        val meta = metas[item.id]
        val year = meta?.year ?: item.facts.firstOrNull { it.firstOrNull()?.isDigit() == true }
        val genre = meta?.genres?.firstOrNull() ?: item.facts.firstOrNull { it.firstOrNull()?.isDigit() == false && !it.startsWith("IMDb") }?.substringBefore(",")
        val length = if (item.type == "series") meta?.videos?.map { it.season }?.filter { it > 0 }?.distinct()?.size?.takeIf { it > 0 }
            ?.let { plural(R.plurals.seasons, it) }
            else meta?.runtime
        // A show in progress describes its next episode, so the line names that episode rather than the show.
        val code = item.left?.split(DOT)?.firstOrNull { it.startsWith("S") && " E" in it }
        val factsList = mutableListOf<String>()
        if (item.newEpisode) factsList += getString(R.string.new_episode)
        if (code != null) factsList += listOfNotNull(code, episode(item)?.title?.takeIf { it.isNotEmpty() })
        else factsList += listOfNotNull(year, genre, length)
        heroFacts.text = factsList.joinToString(DOT)
        val rating = meta?.rating ?: item.facts.firstOrNull { it.startsWith("IMDb") }?.removePrefix("IMDb ")
        heroRating.visibility = if (rating.isNullOrBlank()) View.GONE else View.VISIBLE
        heroScore.visibility = heroRating.visibility
        heroScore.text = rating
        // Leads with a dot only when facts or a rating sit before it (a title with no details yet showed "· Added").
        val lead = if (heroFacts.text.isEmpty() && heroRating.visibility == View.GONE) "" else "·  "
        heroAdded.text = item.addedAt?.let { lead + getString(R.string.added_when, addedAgo(it)) } ?: ""
        (heroAdded.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.marginStart = if (lead.isEmpty()) 0 else dp(6)
        heroAdded.visibility = if (page == Page.LIBRARY && heroAdded.text.isNotEmpty()) View.VISIBLE else View.GONE
        heroAbout.maxLines = 2
        heroAbout.visibility = if (page == Page.LIBRARY) View.GONE else View.VISIBLE
        val description = episode(item)?.overview?.takeIf { it.isNotBlank() } ?: item.about
        heroAbout.text = description
        heroAbout.post {
            if (heroItem !== item || heroAbout.text.toString() != description) return@post
            val layout = heroAbout.layout ?: return@post
            if (layout.lineCount >= 2 && layout.getEllipsisCount(1) > 0) {
                val end = layout.getLineStart(1) + layout.getEllipsisStart(1)
                heroAbout.text = synopsisAtSentence(description, end)
            }
        }
        heroActions.visibility = if (page != Page.HOME && page != Page.LIBRARY && item.resume) View.VISIBLE else View.GONE
        if (page.isBrowse) showCaption(item)
        if (mode == Mode.BROWSE) browseInfo.animate().alpha(if (homeDimmed && (page.isBrowse || page == Page.LIBRARY)) 0.3f else 1f).setDuration(150).start()
        showArt(item)
    }

    private fun showCaption(item: Item) {
        val remaining = item.timeLeft
        val stop = item.stoppedAt?.takeIf { it > 0 }?.let { getString(R.string.stopped_at, android.text.format.DateUtils.formatElapsedTime(it / 1000)) }
        val meta = metas[item.id]
        val detail = if (page == Page.HOME) meta?.year ?: item.facts.firstOrNull { it.firstOrNull()?.isDigit() == true }
            else if (item.type == "series") meta?.videos?.map { it.season }?.filter { it > 0 }?.distinct()?.size?.takeIf { it > 0 }
                ?.let { plural(R.plurals.seasons, it) }
            else meta?.runtime
        cardCaption.text = listOfNotNull(remaining, stop).takeIf { it.isNotEmpty() }?.joinToString(DOT)
            ?: listOfNotNull(item.name, detail).joinToString(DOT)
        cardCaption.visibility = View.VISIBLE
        // Measured with its new words, so a caption near the right end stops at the margin.
        cardCaption.post(::placeCaption)
    }

    private fun placeCaption() {
        cardCaption.translationX = minOf(captionX, cardCaption.rootView.width - 2 * dp(PAD) - cardCaption.width).coerceAtLeast(0).toFloat()
        clearHint()
    }

    /** The next row's name and the caption share one line: a long caption that reaches the name hides it. */
    private fun clearHint() {
        if (nextRowHint.text.isEmpty() || cardCaption.visibility != View.VISIBLE) return
        nextRowHint.post {
            val captionEnd = cardCaption.left + cardCaption.translationX + cardCaption.width
            nextRowHint.visibility = if (captionEnd + dp(SECTION_GAP) > nextRowHint.left) View.INVISIBLE else View.VISIBLE
        }
    }

    /** One quiet line for a title in progress: the episode and its name (or, for a film, its facts), then the time left. */
    private fun where(item: Item): String {
        val parts = item.left?.split(DOT).orEmpty()
        val code = parts.firstOrNull { it.startsWith("S") && " E" in it }
        val about = if (code != null) listOfNotNull(code, episode(item)?.title?.takeIf { it.isNotEmpty() }) else item.facts
        return (about + listOfNotNull(item.timeLeft)).joinToString(DOT)
    }

    /** "Series · 2022 · Crime": the kind, the year and the first genre, nothing that needs reading twice. */
    private fun facts(item: Item): String {
        val year = item.facts.firstOrNull { it.firstOrNull()?.isDigit() == true }
        val genre = item.facts.firstOrNull { it.firstOrNull()?.isDigit() == false && !it.startsWith("IMDb") }?.substringBefore(",")
        return listOfNotNull(if (item.type == "series") "Series" else "Movie", year, genre).joinToString(DOT)
    }

    /** The episode Continue Watching points at, once the title's details are in. */
    private fun episode(item: Item) = item.videoId?.let { v -> metas[item.id]?.videos?.firstOrNull { it.id == v } }

    // ---------- pages ----------

    /**
     * Opens page [p]. With [quick], it draws from last time's answers kept on disk (library, addons, catalogs) at once
     * and asks the network behind them: catalogs refresh for the next visit, and the library replaces what's shown
     * only if it changed and the remote hasn't been touched yet.
     */
    private fun showPage(requestedPage: Page, quick: Boolean = true) {
        val p = when (requestedPage) {
            Page.JELLYFIN_HOME -> Page.HOME
            Page.JELLYFIN_LIBRARY -> Page.LIBRARY
            else -> requestedPage
        }
        dimHome(false)
        if (p != page) {
            genre = null // a genre belongs to the page it was picked on
            jumpTo = null
            genreStrip.removeAllViews()
            genreChips.clear()
        }
        page = p
        val grid = p == Page.LIBRARY
        scroller.layoutParams = (scroller.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = if (grid) dp(LIST_TOP) else browseRowTop
            height = if (grid) resources.displayMetrics.heightPixels - dp(LIST_TOP) else browseRowH
        }
        browseInfo.layoutParams = (browseInfo.layoutParams as FrameLayout.LayoutParams).apply {
            bottomMargin = if (grid) resources.displayMetrics.heightPixels - dp(220) else heroBottom
        }
        wideRow = false
        librarySlot = null
        listCount.visibility = if (grid) View.VISIBLE else View.GONE
        listHint.visibility = if (grid) View.VISIBLE else View.GONE
        val g = ++generation
        loadMoreSections = null
        val opened = android.os.SystemClock.uptimeMillis()
        refreshLibrary = null
        refreshJellyfinRows = null
        touched = false
        applyNavVisibility()
        styleNav()
        content.removeAllViews()
        rows.forEach { r -> r.cards.forEach { it.unload() } }
        rows.clear()
        homeContinueSlot = if (p == Page.HOME) slot() else null
        stremioContinueForHome = emptyList()
        jellyfinContinueForHome = emptyList()
        homeContinueSignature = null
        genreStrip.animate().cancel()
        genreStrip.alpha = 1f
        genreStrip.visibility = if (p.isCatalog) View.INVISIBLE else if (grid) View.VISIBLE else View.GONE
        if (grid) showListChips()
        else if (!p.isCatalog) { genreStrip.removeAllViews(); genreChips.clear() }
        cardCaption.visibility = View.GONE
        nextRowHint.visibility = View.GONE
        moreHint.alpha = 0f
        lastHiddenRows = Settings.hiddenRows(this)
        if (p == Page.HOME) { lastFeaturedHome = Settings.featuredHome(this); cinema = emptyList() } // other pages keep Home's, so the screensaver works there too
        focusedSomething = false
        // The last page's title goes with it; an empty page (no library yet, no catalogs) shows none.
        heroItem = null
        browseInfo.animate().alpha(0f).setDuration(150).start()
        scroller.scrollTo(0, 0)
        mode = Mode.BROWSE
        main.removeCallbacks(advance)
        scroller.visibility = View.VISIBLE
        scroller.translationY = 0f
        scroller.alpha = 1f
        stage.isFocusable = false
        chrome()
        val key = profile.authKey
        appendJellyfinRows(p, g, opened)

        // Nothing at all came back (no network, Stremio down): say so once, with a retry, instead of a blank page.
        var failedShown = false
        fun failedToLoad(error: Throwable) {
            forgetIfSessionGone(error)
            if (failedShown || isFinishing || page != p) return
            failedShown = true
            val view = failed(getString(R.string.home_unreachable)) { showPage(page) }
            content.addView(view, 0, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(PAD) })
            view.post { if (!focusedSomething && !header.hasFocus()) view.getChildAt(2).requestFocus() }
        }

        if (p == Page.HOME || p == Page.LIBRARY) {
            val slot = if (p == Page.HOME) homeContinueSlot ?: slot() else slot()
            if (grid) librarySlot = slot
            var shown: String? = null // what's on screen, to tell whether the fresh library changed anything
            var fresh = false
            fun show(list: List<JSONObject>, force: Boolean = false) {
                val cw = Library.continueWatching(list).filterNot { raw ->
                    val s = raw.optJSONObject("state") ?: return@filterNot false
                    if (s.optLong("timeOffset") != 1L) return@filterNot false
                    val vid = s.text("video_id") ?: return@filterNot false
                    val id = raw.optString("_id")
                    val type = raw.optString("type")
                    val m = metas[id] ?: runCatching { Stremio.savedAddons(key)?.savedMeta(type, id) ?: Stremio.addons(key).savedMeta(type, id) }.getOrNull()
                    if (m != null) metas[id] = m
                    val v = m?.videos?.firstOrNull { it.id == vid }
                    v != null && !isEpisodeAired(v.released)
                }
                val what = (if (p == Page.HOME) cw else list).joinToString {
                    it.optString("_id") + it.optString("_mtime") + it.optBoolean("removed") + it.optJSONObject("state")?.optLong("timeOffset")
                }
                // Saved copies wait once the remote is in use; Stremio's own answer always goes in (focus stays on
                // its title below), or a stale disk copy could hide a show for the whole visit.
                if (what == shown || (shown != null && touched && !force)) return
                val firstShow = shown == null
                shown = what
                // The remote was on one of these titles: it stays on that title wherever it moved (watched: to the front).
                val was = (currentFocus?.takeIf { isInside(it, slot) })?.tag
                library = list
                if (p == Page.HOME) Profiles.active(this)?.let { me -> background({ WatchNext.sync(applicationContext, me.authKey, cw, me.pin != null) }) {} }
                rows.filter { isInside(it.view, slot) }.forEach { r -> r.cards.forEach { it.unload() }; rows.remove(r) }
                slot.removeAllViews()
                if (p == Page.HOME) {
                    stremioContinueForHome = cw.map(::libraryItem)
                    renderHomeContinueRow(g, opened)
                } else {
                    showLibrary(slot)
                }
                if (was != null) (slot.findViewWithTag(was) ?: rows.firstOrNull { isInside(it.view, slot) }?.cards?.firstOrNull())?.requestFocus()
                // Continue came in after a quicker row below took the first focus: the remote starts on Continue.
                else if (p == Page.HOME && firstShow && !touched && focusedSomething && scroller.hasFocus())
                    rows.firstOrNull { isInside(it.view, slot) }?.focusFirst()
            }
            if (quick && library.isNotEmpty()) show(library) // the library already in memory: no wait on a revisit
            if (quick) background({ Stremio.savedLibrary(key) }) { r -> if (g == generation && !fresh) r.getOrNull()?.let { show(it) } }
            fun ask() = background({ Stremio.library(key) }) { result ->
                if (g != generation) return@background
                fresh = true
                result.fold({ show(it, force = true) }) { if (shown == null) failedToLoad(it) }
            }
            ask()
            refreshLibrary = { ask() }
        }
        if (p == Page.LIBRARY) return

        var built = false
        var builtFrom: List<String>? = null // the add-ons the rows came from, to tell when Stremio's fresh list differs
        fun build(addons: List<Addon>) {
            if (g == generation) showLiveTab(addons)
            if (built || g != generation) return
            built = true
            builtFrom = addons.map { it.id }
            val featuredHome = p == Page.HOME && Settings.featuredHome(this@MainActivity)
            // Each catalog on its page under a plain name, personal rows first; the sort keeps the addons' order within a rank.
            val shelves = addons.flatMap { it.catalogs }.mapNotNull { c ->
                shelf(c)?.takeIf { it.page == p || (featuredHome && (it.page == Page.MOVIES || it.page == Page.SHOWS) && it.rank <= 2) }?.let { c to it }
            }.sortedBy { it.second.rank }
            val catalogs = shelves.map { it.first }
            if (catalogs.isEmpty()) {
                val none = when (p) {
                    Page.HOME -> R.string.home_no_picks
                    Page.MOVIES -> R.string.page_no_movie_catalogs
                    Page.LIVE -> R.string.page_no_live_catalogs
                    else -> R.string.page_no_show_catalogs
                }
                // Home holds only Continue and Watchly's picks, so a new account sees nothing there without a word.
                content.addView(text(none, BODY, color = TEXT_3)
                    .apply { setPadding(dp(PAD), 0, dp(PAD), 0) })
            }
            val genres = catalogGenres(catalogs.flatMap { it.genres })
            val hiddenRows = Settings.hiddenRows(this@MainActivity)
            val sections = sections(p, shelves, featuredHome).filterNot { it.first in hiddenRows }
            val genreNames = (if (p.isCatalog) sections.take(nextCatalogSection(sections.size, 0)).map { it.first }.filter { name -> genres.any { it.equals(name, true) } }
                else emptyList()).toMutableList()
            val genreResults = mutableMapOf<String, Boolean>()
            if (p.isCatalog && genreNames.isEmpty()) {
                showGenreChips(emptyList())
                genreStrip.visibility = View.VISIBLE
            }
            // Keep request and view work bounded even when an account has many catalogs.
            var batchPending = 0
            fun buildSection(i: Int) {
                val section = sections[i]
                val (title, sources, picked) = section
                if (p.isCatalog && genres.any { it.equals(title, true) } && title !in genreNames) genreNames += title
                val rowCatalogs = sources.map { it.first }
                val slot = slot()
                val key = catalogAnswerKey(p.label, title, picked, rowCatalogs.map { c ->
                    "${c.addon.transportUrl}/${c.type}/${c.id}"
                })
                val sourceAnswers = arrayOfNulls<Pair<List<Meta>, Boolean>>(rowCatalogs.size)
                var completed = 0
                var reported = false
                var rowVersion = 0
                lateinit var onAnswer: (Result<List<Pair<List<Meta>, Boolean>>>) -> Unit
                onAnswer = onAnswer@{ answer ->
                    if (g != generation) return@onAnswer
                    val version = ++rowVersion
                    val results = answer.getOrNull() ?: emptyList()
                    val items = results.flatMap { it.first }.filterNot { isAddonMessage(it.name) }.distinctBy { it.id }.map(::metaItem)
                    if (p == Page.HOME && cinema.isEmpty() && items.isNotEmpty()) startCinema(items.take(CINEMA_MAX))
                    if (completed == rowCatalogs.size && !reported) {
                        reported = true
                        batchPending--
                        if (batchPending == 0) main.post { loadMoreSections?.invoke() }
                    }
                    if (completed == rowCatalogs.size && title in genreNames) {
                        genreResults[title] = items.isNotEmpty()
                        // Resolve empty rows before showing chips; off-screen row views arrive later.
                        settledGenres(genreNames, genreResults)?.let { ready ->
                            showGenreChips(ready)
                            rows.firstOrNull { row -> currentFocus?.let { isInside(it, row.view) } == true }
                                ?.title?.let(::markChip)
                            genreStrip.alpha = 0f
                            genreStrip.visibility = View.VISIBLE
                            genreStrip.animate().alpha(1f).setDuration(180).start()
                        }
                    }
                    // The rest of the first page first, then the addon's next pages when it takes `skip`.
                    // shortcut: genre rows use each source's first page; add genre-aware paging if those rows feel short.
                    val more: ((Int) -> List<Item>)? = if (rowCatalogs.size == 1) {
                        val catalog = rowCatalogs[0]
                        if (catalog.pages && picked == null) { from ->
                            if (from < items.size) items.drop(from).take(PAGE)
                            else catalog.addon.catalog(catalog, from).filterNot { isAddonMessage(it.name) }.map(::metaItem)
                        } else later(items)
                    } else {
                        val all = items.toMutableList()
                        val counts = results.map { it.first.size }.toMutableList()
                        val exhausted = BooleanArray(rowCatalogs.size)
                        val nextPage: (Int) -> List<Item> = { from ->
                            if (from >= all.size && picked == null) for (j in rowCatalogs.indices) {
                                val catalog = rowCatalogs[j]
                                if (!catalog.pages || exhausted[j]) continue
                                val next = catalog.addon.catalog(catalog, counts[j])
                                counts[j] += next.size
                                exhausted[j] = next.isEmpty()
                                val known = all.map { it.id }.toSet()
                                all += next.filter { it.id !in known && !isAddonMessage(it.name) }.distinctBy { it.id }.map(::metaItem)
                                if (all.size > from) break
                            }
                            all.drop(from).take(PAGE)
                        }
                        nextPage
                    }
                    // Build nearby rows first, spacing the others within this small batch across frames.
                    fun add() {
                        if (g != generation || version != rowVersion) return
                        if (items.isEmpty()) { if (completed == rowCatalogs.size) main.post { loadMoreSections?.invoke() }; return }
                        val old = rows.firstOrNull { it.view.parent === slot }
                        if (old?.view?.hasFocus() == true) { old.pendingUpdate = ::add; return }
                        old?.let { it.cards.forEach { card -> card.unload() }; rows.remove(it); slot.removeAllViews() }
                        val made = row(title, items.take(maxOf(START, old?.items?.size ?: START)), more, wide = (p == Page.LIVE))
                        slot.addView(made.view)
                        if (i == 0) android.util.Log.d("Alder", "page ${p.label}: first row in ${android.os.SystemClock.uptimeMillis() - opened} ms")
                        updateNextRow()
                        if (jumpTo == title) { jumpTo = null; made.view.post { made.focusFirst() } }
                    }
                    if (i < 2) add() else main.postDelayed(::add, (i % 4) * ROW_STAGGER)
                    // Nothing to continue: the cinema shows the first catalog's top titles instead.
                    if (p == Page.HOME && i == 0 && items.isNotEmpty()) main.postDelayed({ if (cinema.isEmpty() && page == p) startCinema(items.take(CINEMA_MAX)) }, 1500)
                    main.post(::updateImages)
                }
                // Answers already read this session (or warmed from disk) build the row at once: switching to a page
                // doesn't wait for the thread pool behind the last page's refreshes. A disk answer (true) refreshes
                // once; after that the row is marked done for the session.
                fun update() {
                    val current = sourceAnswers.map { it ?: (emptyList<Meta>() to true) }
                    if (current.any { it.first.isNotEmpty() }) answers[key] = current
                    onAnswer(Result.success(current))
                }
                fun refresh(index: Int) {
                    val catalog = rowCatalogs[index]
                    background({ catalog.addon.catalog(catalog, picked, CATALOG_WAIT) }) { fresh ->
                        if (g != generation) return@background
                        fresh.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                            sourceAnswers[index] = it to false
                            update()
                        }
                    }
                }
                val kept = answers[key]
                if (quick && kept != null && kept.size == rowCatalogs.size) {
                    kept.forEachIndexed { index, answer -> sourceAnswers[index] = answer.first to false }
                    completed = rowCatalogs.size
                    update()
                    kept.forEachIndexed { index, answer -> if (answer.second) refresh(index) }
                } else rowCatalogs.forEachIndexed { index, catalog ->
                    background({
                        runCatching {
                            (if (quick) catalog.addon.savedCatalog(catalog, picked) else null)?.let { it to true }
                                ?: (catalog.addon.catalog(catalog, picked, CATALOG_WAIT) to false)
                        }.getOrElse { e ->
                            android.util.Log.w("Alder", "row $title (${catalog.id}, genre $picked) failed: $e")
                            emptyList<Meta>() to false
                        }
                    }) { answer ->
                        if (g != generation) return@background
                        sourceAnswers[index] = answer.getOrDefault(emptyList<Meta>() to false)
                        completed++
                        update()
                        if (sourceAnswers[index]?.second == true) refresh(index)
                    }
                }
            }
            var nextSection = nextCatalogSection(sections.size, 0)
            batchPending = nextSection
            for (i in 0 until nextSection) buildSection(i)
            loadMoreSections = {
                val focusedSlot = rows.firstOrNull { it.view.hasFocus() }?.view?.parent as? View
                val nearFocus = focusedSlot != null && content.indexOfChild(focusedSlot) >= content.childCount - 3
                val nearEnd = batchPending == 0 && scroller.scrollY + scroller.height >= content.height - dp(350)
                if (g == generation && nextSection < sections.size && (nearFocus || nearEnd)) {
                    val end = nextCatalogSection(sections.size, nextSection)
                    batchPending = end - nextSection
                    for (i in nextSection until end) buildSection(i)
                    nextSection = end
                }
            }
        }
        if (quick) background({ Stremio.savedAddons(key) }) { r -> r.getOrNull()?.let(::build) }
        // Every page asks Stremio for the add-on list once as it opens (and again when Alder comes back to the front), and
        // redraws only when the list differs from the one its rows came from: add-ons added or removed elsewhere.
        fun checkAddons(drawn: (List<Addon>) -> Unit) = background({ Stremio.addons(key, refresh = true) }) { r -> r.fold({ fresh ->
            if (g == generation) showLiveTab(fresh)
            if (built && g == generation && builtFrom != fresh.map { it.id }) showPage(p, quick = false) else drawn(fresh)
        }) { if (!built && g == generation) failedToLoad(it) } }
        checkAddons(::build)
        refreshAddons = { checkAddons {} }
    }

    private class Shelf(val page: Page, val title: String, val rank: Int)

    private fun showListChips() {
        genreStrip.removeAllViews()
        genreChips.clear()
        listFilterChips.clear()
        listSortChips.clear()
        lastListChip = null
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(PAD), dp(2), dp(PAD), dp(2)) }
        fun add(label: String, selected: Boolean, click: () -> Unit): TextView {
            val control = chip(label, selected).apply {
                setOnFocusChangeListener { v, has ->
                    v.background = glass(CHIP_H / 2, has)
                    if (has) lastListChip = this
                }
                setOnClickListener { click() }
            }
            line.addView(control, LinearLayout.LayoutParams(-2, dp(CHIP_H)).apply { if (line.childCount > 0) marginStart = dp(6) })
            return control
        }
        listOf(ListFilter.ALL to getString(R.string.filter_all), ListFilter.MOVIES to getString(R.string.tab_movies),
            ListFilter.SHOWS to getString(R.string.tab_shows)).forEach { (value, label) ->
            listFilterChips[value] = add(label, value == listFilter) {
                if (listFilter != value) { listFilter = value; styleListChips(); redrawLibrary() }
            }
        }
        line.addView(text(R.string.list_sort, SMALL, color = TEXT_3), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(20) })
        listOf(ListSort.RECENT to getString(R.string.list_sort_recent), ListSort.NAME to getString(R.string.list_sort_name),
            ListSort.UNWATCHED to getString(R.string.list_sort_unwatched))
            .forEach { (value, label) ->
                listSortChips[value] = add(label, value == listSort) {
                    if (listSort != value) { listSort = value; styleListChips(); redrawLibrary() }
                }
            }
        genreStrip.addView(line)
    }

    private fun styleListChips() {
        listFilterChips.forEach { (value, view) -> view.select(getString(when (value) {
            ListFilter.ALL -> R.string.filter_all; ListFilter.MOVIES -> R.string.tab_movies; ListFilter.SHOWS -> R.string.tab_shows
        }), value == listFilter) }
        listSortChips.forEach { (value, view) -> view.select(getString(when (value) {
            ListSort.RECENT -> R.string.list_sort_recent; ListSort.NAME -> R.string.list_sort_name
            ListSort.UNWATCHED -> R.string.list_sort_unwatched
        }), value == listSort) }
    }

    private fun addedAgo(value: String): String {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val whenAdded = runCatching { parser.parse(value)?.time }.getOrNull() ?: return getString(R.string.added_recently)
        return android.text.format.DateUtils.getRelativeTimeSpanString(whenAdded, System.currentTimeMillis(),
            android.text.format.DateUtils.DAY_IN_MILLIS).toString()
    }

    /** All, then one chip per genre row on the page; a chip jumps to its row, and the dot follows the row in use. */
    private fun showGenreChips(names: List<String>) {
        val hadFocus = genreStrip.hasFocus()
        val focusedGenre = genreChips.entries.firstOrNull { it.value.hasFocus() }?.key
        genreStrip.removeAllViews()
        genreChips.clear()
        val line = LinearLayout(this).apply {
            clipChildren = false
            setPadding(dp(PAD), dp(2), dp(PAD), dp(2))
        }
        (listOf<String?>(null) + names).forEach { value ->
            val control = chip(value ?: getString(R.string.filter_all), value == null).apply {
                setOnClickListener {
                    val target = if (value == null) firstRow() else rows.firstOrNull { it.title == value && it.view.isAttachedToWindow }
                    // Rows far down the page are still being made: the remote goes there as soon as it's in.
                    if (target != null) target.focusFirst() else jumpTo = value
                }
            }
            genreChips[value] = control
            line.addView(control, LinearLayout.LayoutParams(-2, dp(CHIP_H)).apply { if (line.childCount > 0) marginStart = dp(6) })
        }
        genreStrip.addView(line)
        if (hadFocus) (genreChips[focusedGenre] ?: genreChips[null])?.requestFocus()
    }

    private fun markChip(rowTitle: String) {
        val open = rowTitle.takeIf { it in genreChips }
        genre = open // Back and Down from the top bar return to this chip
        genreChips.forEach { (value, chip) -> chip.select(value ?: getString(R.string.filter_all), value == open) }
    }

    /**
     * Where a catalog's row goes and what it's called, like Netflix: Home is what you're watching and what it led to
     * ("Because you watched…"); everything else sits on Movies or Shows by type, without the addon's name or a type
     * suffix. Personal picks come first (rank 0–1), then what's popular (2), then themed lists (3). TV channels, sport
     * and events go to Live, one row per catalog under its own name; other types (anime, video channels) are left out.
     */
    private fun shelf(c: Addon.Catalog): Shelf? {
        val typePage = when {
            c.type == "movie" -> Page.MOVIES
            c.type == "series" -> Page.SHOWS
            isLiveType(c.type) -> return Shelf(Page.LIVE, tidy(c.name), 3)
            else -> return null
        }
        val india = mapOf(
            "trendingmovies" to "Trending in India", "trendingtv" to "Trending in India",
            "popmov" to "Popular in India",
            "nfxprm" to "Popular on Netflix & Prime", "nfxprmtv" to "Popular on Netflix & Prime",
            "hstzee" to "Popular on Hotstar, SonyLIV & Zee5", "hstzeetv" to "Popular on Hotstar, SonyLIV & Zee5",
            "recmov" to "Recommended in India", "atpmub" to "Recommended in India",
        )
        val lowerId = c.id.lowercase()
        return when {
            c.addon.id.contains("watchly") && c.id.startsWith("watchly.item") -> Shelf(Page.HOME, homeRowName(c.name), 0)
            c.addon.id.contains("watchly") && c.id == "watchly.rec" -> Shelf(typePage, "Top Picks for You", 0)
            c.addon.id.startsWith("trakt.addon") && c.id.startsWith("recommendations") -> Shelf(typePage, "Recommended for You", 1)
            c.addon.id.startsWith("trakt.addon") && c.id.startsWith("trending") -> Shelf(typePage, "Trending Now", 2)
            c.addon.id == "in.rdata.indiastreams" -> Shelf(typePage, india[c.id] ?: c.name, 2)
            lowerId in setOf("top", "popular", "trending") || c.name.equals("Top", true) || c.name.equals("Popular", true) || c.name.equals("Trending", true) -> Shelf(typePage, tidy(c.name), 2)
            else -> Shelf(typePage, tidy(c.name), 3)
        }
    }

    /** Addon names as a person would write them: no "TV Shows:" label, no run-together keyword tags. */
    private fun tidy(name: String) = name.removePrefix("TV Shows: ").removePrefix("MyTrakt - ")
        // ponytail: Watchly glues TMDB keyword tags onto theme names ("Comedy Aftercreditsstinger"); a word this long is one.
        .split(" ").filter { it.length < 16 }.joinToString(" ")

    private fun startCinema(items: List<Item>) {
        cinema = items
        cinemaAt = 0
        if (mode != Mode.CINEMA) return
        showCinema(0)
        stage.requestFocus()
        chrome()
    }

    private fun slot() = FrameLayout(this).also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }

    private fun redrawLibrary() {
        val slot = librarySlot ?: return
        val was = currentFocus?.takeIf { isInside(it, slot) }?.tag
        rows.filter { isInside(it.view, slot) }.forEach { row -> row.cards.forEach { it.unload() }; rows.remove(row) }
        slot.removeAllViews()
        scroller.scrollTo(0, 0)
        showLibrary(slot)
        if (was != null) (slot.findViewWithTag<View>(was) ?: firstRow()?.cards?.firstOrNull() ?: listFilterChips[listFilter])?.requestFocus()
        else firstRow()?.items?.firstOrNull()?.let { fill(it); showHero(it) }
    }

    private fun showLibrary(slot: FrameLayout) {
        val all = library.filter { !it.optBoolean("removed") && it.optString("type") in listOf("movie", "series") }
            .filter { listFilter == ListFilter.ALL || it.optString("type") == if (listFilter == ListFilter.MOVIES) "movie" else "series" }
        val ordered = when (listSort) {
            ListSort.RECENT -> all.sortedByDescending { it.optString("_ctime") }
            ListSort.NAME -> all.sortedBy { it.optString("name").lowercase() }
            ListSort.UNWATCHED -> all.sortedWith(compareBy<JSONObject> { libraryFinished(it) }.thenByDescending { it.optString("_ctime") })
        }
        val items = ordered.map(::libraryItem)
        val build = ++listBuild
        listTotal = items.size
        listRows = listRowCount(items.size, LIST_COLUMNS)
        listCount.text = if (items.isEmpty()) "" else resources.getQuantityString(R.plurals.list_position, items.size, 1, listRows, items.size)
        listHint.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty()) {
            heroItem = null
            browseInfo.alpha = 0f
            art.animate().cancel()
            art.setImageDrawable(null)
            art.tag = null
            art.alpha = 0f
            artBack.setImageDrawable(null)
            val message = when {
                all.isEmpty() && listFilter == ListFilter.ALL -> R.string.list_empty
                listFilter == ListFilter.MOVIES -> R.string.list_empty_movies
                listFilter == ListFilter.SHOWS -> R.string.list_empty_shows
                else -> R.string.list_empty_unwatched
            }
            slot.addView(text(message, BODY, color = TEXT_2).apply { setPadding(dp(PAD), dp(30), 0, 0) })
            return
        }
        val column = column(Gravity.START)
        items.chunked(LIST_COLUMNS).forEachIndexed { index, cards ->
            fun add() { if (page == Page.LIBRARY && slot === librarySlot && build == listBuild)
                column.addView(row("", cards, grid = true).view)
            }
            if (index < 3) add() else main.postDelayed(::add, ROW_LATER + index * ROW_STAGGER)
        }
        slot.addView(column)
    }

    private fun libraryFinished(item: JSONObject): Boolean {
        val state = item.optJSONObject("state") ?: return false
        return state.optInt("timesWatched") > 0 && state.optLong("timeOffset") == 0L
    }

    private fun listMenu(item: Item) {
        val original = library.firstOrNull { it.optString("_id") == item.id } ?: return
        val options = listOf(getString(R.string.action_remove_from_list)) +
            (if (item.finished) emptyList() else listOf(getString(R.string.action_mark_watched)))
        choose(item.name, options) { selected ->
            val updated = JSONObject(original.toString())
            if (selected == 0) Library.setInList(updated, false)
            else Library.markTitleWatched(updated, metas[item.id]?.videos.orEmpty())
            background({ Stremio.saveLibrary(profile.authKey, listOf(updated)) }) { result ->
                result.fold({
                    library = library.map { if (it.optString("_id") == item.id) updated else it }
                    if (page == Page.LIBRARY) redrawLibrary()
                }) { error ->
                    forgetIfSessionGone(error)
                    if (page == Page.LIBRARY && !isFinishing)
                        choose(getString(R.string.list_update_failed), listOf(getString(R.string.action_ok)), getString(R.string.check_connection)) { }
                }
            }
        }
    }

    /** Hold OK on a Continue Watching title: choose a source yourself, or take the title off the row. */
    private fun continueMenu(item: Item) {
        choose(item.name, listOf(getString(R.string.action_choose_source), getString(R.string.action_remove_from_continue))) { selected ->
            if (selected == 0) return@choose play(item, pick = true)
            val original = library.firstOrNull { it.optString("_id") == item.id } ?: return@choose
            val updated = JSONObject(original.toString())
            val offset = Library.hideFromContinueWatching(updated)
            background({ Stremio.saveLibrary(profile.authKey, listOf(updated)) }) { result ->
                result.fold({
                    Settings.setHidden(this, Settings.hidden(this)
                        .put(item.id, JSONObject().put("name", item.name).put("timeOffset", offset)))
                    library = library.map { if (it.optString("_id") == item.id) updated else it }
                    refreshLibrary?.invoke() // the row (and Google TV's) is drawn again without it
                }) { error ->
                    forgetIfSessionGone(error)
                    if (!isFinishing) choose(getString(R.string.remove_failed), listOf(getString(R.string.action_ok)), getString(R.string.check_connection)) { }
                }
            }
        }
    }

    /** Hold OK on a catalog card: choose a source yourself, or take the whole row off the page. */
    private fun rowMenu(row: Row, item: Item) {
        val options = listOf(getString(R.string.action_choose_source)) +
            (if (row.title.isNotEmpty()) listOf(getString(R.string.action_hide_row, row.title)) else emptyList())
        choose(item.name, options) { selected ->
            if (selected == 0) play(item, pick = true)
            else {
                Settings.hideRow(this, row.title)
                showPage(page)
            }
        }
    }

    // ---------- rows ----------

    /** The rest of a list already in hand, a page at a time: a row of hundreds of cards would be slow to build. */
    private fun later(all: List<Item>): ((Int) -> List<Item>)? =
        if (all.size <= START) null else { from -> all.drop(from).take(PAGE) }

    private fun row(title: String, items: List<Item>, more: ((Int) -> List<Item>)? = null, wide: Boolean = false, grid: Boolean = false): Row = Row(title, items, more, wide, grid).also {
        rows += it
        main.post(::updateImages)
        if (!focusedSomething && mode == Mode.BROWSE) {
            // Rows answer in any order: give slower ones above a moment, then start at the top row, not the quickest.
            main.removeCallbacks(focusTop)
            main.postDelayed(focusTop, 400)
        }
        // A page opened by resting on its name: the remote stays in the top bar and the top row's first title shows
        // above as soon as the top rows are in (they answer within a few tens of ms of each other).
        if (header.hasFocus()) { main.removeCallbacks(heroTop); main.postDelayed(heroTop, 150) }
        main.post(::updateNextRow)
    }

    private val nextRowCheck = Runnable { updateNextRow() }

    private fun updateNextRow() {
        if (!page.isBrowse) return
        val visible = rows.filter { it.view.isAttachedToWindow }.sortedBy { offsetIn(it.view) }
        // No row in use (the remote is on the chips or the top bar): the row sitting in the band counts.
        val active = visible.indexOfFirst { it.active }.takeIf { it >= 0 }
            ?: visible.indexOfFirst { offsetIn(it.view) >= scroller.scrollY - dp(2) }.coerceAtLeast(0)
        // Rows below the active one keep their names hidden: one peeking in at the bottom sat under the caption.
        // The hint on the right names the next row instead.
        visible.forEachIndexed { i, row -> row.peek(i <= active) }
        nextRowHint.text = visible.getOrNull(active + 1)?.let { "▾  ${it.title.uppercase()}" } ?: ""
        nextRowHint.visibility = if (nextRowHint.text.isEmpty()) View.GONE else View.VISIBLE
        clearHint()
    }

    private val focusTop = Runnable {
        if (focusedSomething || mode != Mode.BROWSE) return@Runnable
        focusedSomething = true
        if (header.hasFocus() && !jump) return@Runnable
        jump = false
        firstRow()?.focusFirst()
    }

    private val heroTop = Runnable {
        val first = firstRow()?.takeIf { header.hasFocus() && mode == Mode.BROWSE } ?: return@Runnable
        first.items[first.selected].takeIf { it !== heroItem }?.let { fill(it); showHero(it) }
    }

    /** The row nearest the top of the page: where Up goes back to the cinema from. */
    private fun firstRow(): Row? = rows.filter { it.view.isAttachedToWindow }.minByOrNull { offsetIn(it.view) }

    /**
     * One row of wide art cards along the bottom of the screen; only the focused row shows, the art fills the rest.
     * Left and right are handled here rather than by Android's focus search, so the row slides in one smooth motion,
     * one card per press, and its ends stop the remote.
     */
    /**
     * A row of cards. With [more], it asks for the titles after the ones it has when the remote gets near its end (a
     * catalog's next page, or the rest of a long library), and stops once an answer brings nothing new. A [wide] row
     * (Continue Watching) shows 16:9 art with the name, time left and progress on the card, two poster columns wide, and
     * ends on the same foot line as the poster rows.
     */
    private inner class Row(val title: String, first: List<Item>, private var more: ((Int) -> List<Item>)?, val wide: Boolean = false, val grid: Boolean = false) {
        val cardW = if (grid) LIST_W else if (wide) WIDE_W else CARD_W
        val cardH = if (grid) LIST_H else if (wide) WIDE_H else CARD_H
        val items = first.toMutableList()
        val cards = items.mapIndexed { i, item -> Card(item, i) }.toMutableList()
        private var fetching = false
        var selected = 0
        var pendingUpdate: (() -> Unit)? = null
        var active = false
        var loaded = false
        private var anim: ValueAnimator? = null
        private var held = false // the remote's key is held down: the row glides instead of stepping
        val strip = LinearLayout(this@MainActivity).apply {
            // Room above and below for the focused card to grow into without being clipped.
            setPadding(dp(PAD), dp(if (grid) LIST_TOP_ROOM else ROW_TOP_ROOM), dp(PAD), if (grid) dp(LIST_GAP) else dp(ROW_BOTTOM_ROOM))
            clipToPadding = false
            cards.forEachIndexed { i, c -> addView(c, LinearLayout.LayoutParams(dp(cardW), dp(cardH)).apply { if (i > 0) marginStart = dp(if (grid) LIST_GAP else CARD_GAP) }) }
        }
        val scroller: HorizontalScrollView = object : HorizontalScrollView(this@MainActivity) {
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                val left = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                if (!left && event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_DOWN) {
                    held = event.repeatCount > 0
                    cards.getOrNull(selected + if (left) -1 else 1)?.requestFocus()
                } else held = false
                return true // at either end the remote simply stays put
            }

            // Coming in from above or below, the remote lands on the card this row last had.
            override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
                if (!hasFocus() && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)) views.add(cards[selected])
                else super.addFocusables(views, direction, focusableMode)
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> showVisibleCards() }
            setOnScrollChangeListener { _, _, _, _, _ ->
                showVisibleCards()
                loadRest(this@Row)
            }
        }
        // The row the remote is in has a bright title; the next one, peeking in below, is a quiet hint.
        // One line, always: the row band is measured for one, and a second would push the cards off the screen.
        private val heading = caps(title).onArt().apply {
            setPadding(dp(PAD), 0, dp(PAD), 0)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(PAD + HERO_W) // a long "Because you watched…" stops at the hero's width
            alpha = QUIET
            if (grid) visibility = View.GONE
        }
        val view: View = column(Gravity.START, heading, scroller).apply { if (wide) setPadding(0, dp(CARD_H - WIDE_H), 0, 0) }

        init { if (grid) cards.forEach { it.dim(true) } }

        fun peek(show: Boolean) { heading.visibility = if (show) View.VISIBLE else View.INVISIBLE }


        /** In or out of this row: its title brightens or quiets, its cards come forward or settle back. */
        fun light(on: Boolean) {
            if (active == on) return
            active = on
            if (on) peek(true)
            heading.animate().alpha(if (on) 1f else QUIET).setDuration(200).start()
            cards.forEachIndexed { i, c -> c.dim(if (grid) !on || i != selected else on && i != selected) }
        }

        fun focusFirst() = cards[selected].requestFocus()

        private fun showVisibleCards() {
            val x = scroller.scrollX
            cards.forEach { it.show(it.isFocused || (it.right - x > dp(PAD) && it.left - x < scroller.width)) }
        }

        /**
         * The remote reached card [index]: slide it to the row's start, where the eye already is. Near the end the row
         * stops, so the last card rests at the right margin instead of leaving a hole. Cards that slid into the left
         * margin hide: everything lines up on the margin, with no sliver at the screen's edge. On the right, the next
         * card runs off the screen on purpose, to say there's more.
         */
        fun focus(index: Int) {
            val previous = selected
            selected = index
            main.post { loadMoreSections?.invoke() }
            if (active) {
                if (previous != index) { cards[previous].dim(true); cards[index].dim(false) }
            } else light(true)
            if (page.isBrowse) updateNextRow()
            if (page.isCatalog) markChip(title)
            if (grid) listCount.text = resources.getQuantityString(R.plurals.list_position, listTotal, rows.indexOf(this) + 1, listRows, listTotal)
            fill(items[index])
            cards[index].load()
            if (index >= items.size - AHEAD) fetchMore()
            // Racing past, the cards beside aren't where the remote will stop: fetching their art would only queue
            // full-screen downloads and decodes that compete with the row's own pictures.
            if (!held) listOf(index - 1, index + 1).mapNotNull { items.getOrNull(it) }.forEach(::warm)
            if (grid) {
                cards[index].show(true)
                moreHint.alpha = 0f
                return
            }
            anim?.cancel()
            val startScroll = scroller.scrollX
            val targetScroll = minOf(index * (dp(cardW) + dp(CARD_GAP)), (strip.width - scroller.width).coerceAtLeast(0))
            // Keep the cards at the current scroll position visible while the strip slides toward its destination.
            val right = scroller.width - dp(PAD) / 2
            showVisibleCards()
            // The caption sits under the focused card, also where the row stops sliding near its end.
            captionX = (cards[index].left - targetScroll - dp(PAD)).coerceAtLeast(0)
            val beyond = cards.any { it.right - targetScroll > right }
            moreHint.animate().alpha(if (beyond && mode == Mode.BROWSE && !page.isBrowse) 0.9f else 0f).setDuration(200).start()
            moreHint.translationY = if (wide) dp(CARD_H - WIDE_H + WIDE_H / 2 - CARD_H / 2).toFloat() else 0f
            if (!grid && wideRow != wide) {
                wideRow = wide
                browseInfo.layoutParams = (browseInfo.layoutParams as FrameLayout.LayoutParams).apply {
                    bottomMargin = heroBottom - if (wide) dp(CARD_H - WIDE_H) else 0
                }
            }
            // A held key repeats every 50 ms. Restarting a slowing-down slide on each repeat made the row lurch and brake
            // twenty times a second; a straight slide that lasts until the next repeat joins them into one glide.
            anim = ValueAnimator.ofInt(startScroll, targetScroll).setDuration(if (held) ViewConfiguration.getKeyRepeatDelay().toLong() else 200).apply {
                interpolator = if (held) LinearInterpolator() else DecelerateInterpolator()
                addUpdateListener { scroller.scrollTo(it.animatedValue as Int, 0) }
                start()
            }
        }

        private fun fetchMore() {
            val more = more ?: return
            if (fetching) return
            fetching = true
            val from = items.size
            background({ more(from) }) { result ->
                fetching = false
                val known = items.map { it.id }.toSet()
                val fresh = result.getOrElse { return@background }.filter { it.id !in known } // a failed page can be asked again
                if (fresh.isEmpty()) { this.more = null; return@background }
                for (item in fresh) {
                    val card = Card(item, items.size)
                    items += item
                    cards += card
                    strip.addView(card, LinearLayout.LayoutParams(dp(cardW), dp(cardH)).apply { marginStart = dp(CARD_GAP) })
                    card.alpha = 0f // shows once focus moves near, like the cards past the right margin
                    card.dim(active)
                    if (loaded) card.load()
                }
                // The slide was clamped to the row's old end; with more cards in, the focused one can reach the start.
                if (active && !grid) strip.post { if (active) { focus(selected); placeCaption() } }
            }
        }

        /** One title: its poster (the name only when there's none), or in a wide row its art with name, time left and progress. */
        inner class Card(val item: Item, val index: Int) : FrameLayout(this@MainActivity) {
            // Fading, a card that may overlap itself is drawn into a layer of its own first: an extra pass for every
            // card fading in or out. Fading each part on its own looks the same over 150 ms.
            override fun hasOverlappingRendering() = false
            // Grey only until the art arrives, so the GPU doesn't paint a hidden layer under every card.
            private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            // The name in words: always on a wide card, on a poster only when it has no picture. Made only then: a
            // text view per card was a good part of the cost of making a row.
            private val nameMade = lazy {
                text(item.name, if (wide) BODY else SMALL, bold = true).apply {
                    maxLines = if (wide) 1 else 2
                    ellipsize = TextUtils.TruncateAt.END
                    if (item.live) gravity = Gravity.CENTER
                }.also {
                    face.addView(it, LayoutParams(
                        if (item.live) -2 else -1,
                        -2,
                        if (item.live) Gravity.CENTER else Gravity.BOTTOM or Gravity.START
                    ).apply {
                        if (item.live) setMargins(dp(8), dp(8), dp(8), dp(8))
                        else setMargins(dp(inset), 0, dp(inset), dp(if (item.progress > 0) 22 else 12))
                    })
                }
            }
            private val name by nameMade
            private val inset = if (wide) 14 else 12
            private var shown: String? = null
            private var wanted = false // near the screen: load when the details arrive
            // The poster appears once it's decoded; until then the card is a plain dark tile.
            private val face = object : FrameLayout(context) { override fun hasOverlappingRendering() = false }.apply { alpha = 0f }
            // Darkens the cards beside the focused one. A shade, not transparency: a see-through card would let the
            // art behind it show through and look like it was floating on the poster.
            private val shade = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
            // On every card in progress, as Netflix does: comparing how far along titles are is the point of the row.
            private val progress = item.progress.takeIf { it > 0 }?.let { progressBar(it) }

            init {
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(if (grid) 6 else RADIUS).toFloat())
                }
                clipToOutline = true
                setBackgroundColor(BASE)
                face.addView(image, LayoutParams(-1, -1))
                // A dark foot under the words or the progress bar; a plain poster needs none.
                if ((wide && !item.live) || item.progress > 0) face.addView(View(context).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x66000000, 0x00000000))
                }, LayoutParams(-1, dp(cardH * (if (wide) 6 else 4) / 10), Gravity.BOTTOM))
                if (wide && !item.live) name.visibility = View.VISIBLE
                // Time left as a quiet label in the corner, where the eye checks before choosing what to resume.
                val timeLeft = item.timeLeft
                if (wide && timeLeft != null) face.addView(text(timeLeft, SMALL, bold = true).apply {
                    background = rounded(0x99000000.toInt(), 8)
                    setPadding(dp(8), dp(3), dp(8), dp(4))
                }, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(10), 0) })
                val addBadge = {
                    face.addView(caps(R.string.new_episode, TUNGSTEN).apply {
                        tag = "new_badge"
                        background = rounded(0xCC000000.toInt(), 6)
                        setPadding(dp(8), dp(3), dp(8), dp(4))
                    }, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) })
                }
                if (wide && item.newEpisode) addBadge()
                addView(face, LayoutParams(-1, -1))
                // A white bar inset and rounded inside the card's dark foot, so it reads as a bar and not a cropped edge.
                if (progress != null) addView(progress.apply { clipToOutline = true },
                    LayoutParams(-1, dp(3), Gravity.BOTTOM).apply { setMargins(dp(inset), 0, dp(inset), dp(11)) })
                if (grid && item.finished) addView(text("✓", SMALL, bold = true, color = TUNGSTEN).apply {
                    gravity = Gravity.CENTER
                    background = glass(12)
                }, LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
                addView(shade, LayoutParams(-1, -1))
                isFocusable = true
                tag = "card:${item.id}"
                item.onFill += {
                    if (wide && item.newEpisode && face.findViewWithTag<View>("new_badge") == null) addBadge()
                    if (wanted) load()
                }
                setOnFocusChangeListener { _, has ->
                    // Focus grows into the gaps without moving its neighbours, and wears a white edge that reads from the
                    // couch on any poster (the clip to the card's outline keeps it inside the rounded corners).
                    lift(has)
                    if (has) {
                        focus(index)
                        showHero(item)
                    } else main.post { if (!view.hasFocus()) pendingUpdate?.let { pendingUpdate = null; it() } }
                }
                // A channel has no title page to show: OK plays it, and holding OK lists its sources.
                setOnClickListener { if (item.live) play(item) else openDetails(item) }
                if (item.providerItem != null) setOnLongClickListener { openDetails(item); true }
                else if (grid) setOnLongClickListener { listMenu(item); true }
                else if (item.resume) setOnLongClickListener { continueMenu(item); true }
                else setOnLongClickListener { rowMenu(this@Row, item); true }
            }

            private var showing: Boolean? = null

            /** In view or slid into a margin; the row's slide supplies the motion. */
            fun show(on: Boolean) {
                if (on == showing) return
                showing = on
                alpha = if (on) 1f else 0f
            }

            fun dim(on: Boolean) = shade.animate().alpha(if (on) DIM else 0f).setDuration(200).start()

            /** On screen (or nearly): the poster (the wide art cropped for a title without one, else its name). */
            fun load() {
                wanted = true
                // A wide card waits for the title's art (it comes with its details) and takes the poster only if there's none.
                val pick = if (item.live) item.poster ?: item.art else if (wide) item.art ?: item.poster?.takeIf { item.settled } else item.poster ?: item.art
                if (pick == null || (!item.live && wide && item.art == null)) fill(item)
                val url = pick ?: run {
                    if (item.settled || item.live) {
                        if (item.live) face.background = glass(RADIUS)
                        name.visibility = View.VISIBLE
                        face.alpha = 1f
                    }
                    return
                }
                if (url == shown) return
                shown = url
                face.animate().cancel()
                face.alpha = 0f
                var now = true // from memory: no fade, it's simply there
                Images.load(url, image, (dp(cardW) * CARD_GROW).toInt(), failed = {
                    if (item.live) face.background = glass(RADIUS)
                    name.visibility = View.VISIBLE
                    face.alpha = 1f
                }) {
                    if (item.live) fitLogo()
                    if (nameMade.isInitialized()) name.visibility = if (wide && !item.live) View.VISIBLE else View.INVISIBLE
                    if (now) face.alpha = 1f else face.animate().alpha(1f).setDuration(180).start()
                    if (isFocused) lift(true)
                }
                now = false
            }

            /**
             * A channel's picture is often its logo: on a transparent background, or a shape far from the card's. That
             * sits whole inside the card on glass instead of being cropped; a picture shaped like the card fills it.
             */
            private fun fitLogo() {
                val bitmap = (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
                val shape = bitmap.width / bitmap.height.toFloat()
                val target = cardW / cardH.toFloat()
                val logo = bitmap.hasAlpha() || kotlin.math.abs(shape - target) > 0.25f
                image.scaleType = if (logo) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                val inset = if (logo) dp(if (wide) 8 else LOGO_INSET) else 0
                image.setPadding(inset, inset, inset, inset)
                face.background = if (logo) glass(RADIUS) else null
            }

            /** Far off screen: let the pictures go. */
            fun unload() {
                wanted = false
                shown = null
                image.tag = null
                image.setImageDrawable(null)
                image.setPadding(0, 0, 0, 0)
                face.background = null
                face.alpha = 0f
            }
        }
    }

    // ---------- items ----------

    /** Art, logo and facts the catalog didn't send, fetched once per title from the meta addon. */
    private fun fill(item: Item) {
        if (item.filled) return
        if (item.providerItem != null) {
            item.filled = true
            item.settled = true
            return
        }
        item.filled = true
        metas[item.id]?.let { applyMeta(item, it); return }
        val key = profile.authKey
        metaPool.execute {
            // Last time's details from disk when there are some; the network only for titles never seen here.
            val m = runCatching {
                Stremio.addons(key).let { it.savedMeta(item.type, item.id) ?: it.meta(item.type, item.id) }
            }.getOrNull()
            main.post {
                if (isDestroyed) return@post
                if (m == null) return@post settle(item)
                metas[item.id] = m
                applyMeta(item, m)
            }
        }
    }

    private fun applyMeta(item: Item, m: Meta) {
        if (item.art == null) item.art = m.background
        if (item.logo == null) item.logo = m.logo
        if (item.about.isEmpty()) item.about = m.description ?: ""
        if (item.facts.isEmpty() || item.resume) item.facts = facts(m)
        if (item.stoppedAt == 1L && item.videoId != null) {
            val v = m.videos.firstOrNull { it.id == item.videoId }
            if (v != null && isRecentlyAired(v.released)) item.newEpisode = true
        }
        // Home's first title (the first card the remote lands on): next launch starts on its art and logo.
        if (cinema.firstOrNull() === item && item.art != null) getSharedPreferences("home", MODE_PRIVATE).edit()
            .putString("art:${profile.id}", item.art).putString("logo:${profile.id}", item.logo).apply()
        settle(item)
    }

    /** [item] has all it will get: cards, the hero and the cinema waiting on it go ahead. */
    private fun settle(item: Item) {
        item.settled = true
        item.onFill.forEach { it() }
        if (heroItem === item && mode == Mode.BROWSE) showHero(item)
        if (mode == Mode.CINEMA && cinema.getOrNull(cinemaAt) === item) showCinema(cinemaAt)
    }

    private fun facts(m: Meta) = listOfNotNull(
        m.genres.take(2).joinToString(", ").ifEmpty { null }, m.year, m.rating?.let { "IMDb $it" },
    )

    // A channel's square or wide picture is its logo, and stands in for the title's logo above the cards.
    private fun metaItem(m: Meta) = Item(
        m.id, m.type, m.name, m.poster, m.background,
        m.logo ?: Images.logoFor(m.id) ?: m.poster?.takeIf { isLiveType(m.type) && m.posterShape in listOf("square", "landscape") },
        m.description ?: "", facts(m),
        null, 0f, resume = false, left = null,
    )

    private fun libraryItem(item: JSONObject): Item {
        val s = item.optJSONObject("state") ?: JSONObject()
        val videoId = s.text("video_id")
        val duration = s.optLong("duration")
        val offset = s.optLong("timeOffset")
        // "S1 E3" for an episode (ids look like tt123:1:3), and how much is left.
        val episode = videoId?.split(":")?.takeIf { it.size == 3 }?.let { "S${it[1]} E${it[2]}" }
        val mins = (duration - offset) / 60_000
        val left = if (duration > 0 && offset > 1) (if (mins >= 60) getString(R.string.time_left_hours, (mins / 60).toInt(), (mins % 60).toInt())
            else getString(R.string.time_left_minutes, mins.toInt())) else null
        val progress = if (duration > 0) (offset.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val id = item.getString("_id")
        val type = item.optString("type")
        val newEpisode = if (offset == 1L && videoId != null) {
            val key = profile.authKey
            val m = metas[id] ?: runCatching { Stremio.savedAddons(key)?.savedMeta(type, id) ?: Stremio.addons(key).savedMeta(type, id) }.getOrNull()
            if (m != null) metas[id] = m
            val v = m?.videos?.firstOrNull { it.id == videoId }
            v != null && isRecentlyAired(v.released)
        } else false
        return Item(
            id, type, item.optString("name"), item.text("poster"),
            null, Images.logoFor(id), "", emptyList(), videoId, progress,
            resume = progress > 0, left = listOfNotNull(episode, left).joinToString(DOT).ifEmpty { null }, timeLeft = left, stoppedAt = offset,
            addedAt = item.text("_ctime"), finished = libraryFinished(item),
            newEpisode = newEpisode,
        )
    }

    /** Resume: straight to the sources for the episode Continue Watching points at; with [pick] (held OK) they're all listed. */
    private fun play(item: Item, pick: Boolean = false) {
        item.providerItem?.let { provider ->
            if (provider.type.equals("Series", true)) { openDetails(item); return }
            val episode = provider.type.equals("Episode", true)
            startActivity(Intent(this, ProviderPlayerActivity::class.java)
                .putExtra("jellyfinItemId", provider.id)
                .putExtra("title", provider.seriesName ?: item.name)
                .putExtra("episodeTitle", if (episode) listOfNotNull(provider.seasonNumber?.let { "S%02d".format(it) }, provider.episodeNumber?.let { "E%02d".format(it) }, provider.name).joinToString("  ·  ") else "")
                .putExtra("seriesId", provider.seriesId)
                .putExtra("resumeAtMs", provider.playbackPositionTicks / 10_000L))
            return
        }
        if (resolvingStreams != null) return
        val videoId = item.videoId ?: item.id
        val videos = metas[item.id]?.videos.orEmpty()
        val next = videos.getOrNull(videos.indexOfFirst { it.id == videoId } + 1)?.takeIf { videos.any { it.id == videoId } }
        val episode = item.left?.substringBefore(" · ")?.takeIf { item.type == "series" && it.startsWith("S") }
        val intent = Intent(this, StreamsActivity::class.java)
            .putExtra("type", item.type).putExtra("id", item.id).putExtra("name", item.name).putExtra("poster", item.poster)
            .putExtra("videoId", videoId).putExtra("nextVideoId", next?.id).putExtra("pick", pick)
            .putExtra("background", metas[item.id]?.background ?: item.art)
            .putExtra("logo", metas[item.id]?.logo ?: item.logo)
            .putExtra("still", videos.firstOrNull { it.id == videoId }?.thumbnail)
            .putExtra("runtime", metas[item.id]?.runtime)
            .putExtra("episode", videos.firstOrNull { it.id == videoId }?.let { "S${it.season} E${it.episode}  ·  ${it.title}" })
            .putExtra("label", listOfNotNull(item.name, episode).joinToString("  ·  "))
            .putExtra("resumeAt", item.stoppedAt)

        if (pick || Settings.alwaysList(this)) {
            startActivity(intent)
            return
        }

        startActivity(Intent(this, PlayerActivity::class.java).putExtras(intent))
    }

    private fun openDetails(item: Item) {
        item.providerItem?.let { provider ->
            startActivity(Intent(this, ProviderDetailsActivity::class.java).putExtra("jellyfinItemId", provider.id))
            return
        }
        startActivity(Intent(this, DetailsActivity::class.java)
            .putExtra("type", item.type).putExtra("id", item.id).putExtra("name", item.name)
            .putExtra("poster", item.poster).putExtra("background", item.art).putExtra("videoId", item.videoId))
    }

    // ---------- vertical movement ----------

    /** Shows the focused row in the row band. */
    private fun snapTo(focused: View) {
        val rowView = rows.firstOrNull { isInside(focused, it.view) }?.view ?: return
        scroller.smoothScrollTo(0, offsetIn(rowView).coerceAtLeast(0))
    }

    // ---------- memory: pictures only near the screen ----------

    private fun updateImages() {
        val top = scroller.scrollY
        val height = scroller.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        // Requests run newest first, so the bottom rows are asked for first and the top row last: it loads first.
        for (r in rows.filter { it.view.isAttachedToWindow }.sortedByDescending { offsetIn(it.view) }) {
            val y = offsetIn(r.view)
            val near = r.view.hasFocus() || y < top + height * 3 && y + r.view.height > top - height
            val far = !r.view.hasFocus() && (y > top + height * 5 || y + r.view.height < top - height * 3)
            // Only the row on screen and its neighbours (where the remote can go next) stay in the drawing. The rest,
            // hundreds of cards, were walked by the RenderThread every frame though none of them shows.
            r.view.visibility = if (r.view.hasFocus() || y < top + height * (if (page == Page.LIBRARY) 3 else 2) && y + r.view.height > top - height) View.VISIBLE else View.INVISIBLE
            // An old row stays out of the title area after a genre jump; the focused row remains visible while it slides in.
            r.view.alpha = if (!r.view.hasFocus() && page != Page.LIBRARY && y + r.view.height <= top + dp(2)) 0f else 1f
            if (near && !r.loaded) {
                r.loaded = true
                r.cards.take(FIRST).asReversed().forEach { it.load() }
            } else if (far && r.loaded) {
                r.loaded = false
                r.cards.forEach { it.unload() }
            }
        }
    }

    /** A row scrolled sideways: its cards past the first screenful load too. */
    private fun loadRest(r: Row) {
        if (!r.loaded) return
        val step = dp(r.cardW + CARD_GAP)
        val first = (r.scroller.scrollX / step - 1).coerceAtLeast(0)
        val last = ((r.scroller.scrollX + r.scroller.width) / step + 2).coerceAtMost(r.cards.size)
        for (i in first until last) r.cards[i].load()
    }

    private fun offsetIn(v: View): Int {
        var y = 0
        var cur: View? = v
        while (cur != null && cur !== content) {
            y += cur.top
            cur = cur.parent as? View
        }
        return y
    }

    /**
     * "What's new" once after an update, on whichever profile opens Home first: never on a fresh install, and once per
     * version. Versions before this one kept no record, so an update is told from an install by the package's dates.
     */
    private fun whatsNewOnce() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val version = Updates.mine(this)
        val seen = prefs.getString("seenVersion", null)
        if (seen == version) return
        prefs.edit().putString("seenVersion", version).apply()
        val info = packageManager.getPackageInfo(packageName, 0)
        if (seen == null && info.firstInstallTime == info.lastUpdateTime) return // installed, not updated
        whatsNew(version, quiet = true)
    }

    // Signed out elsewhere (or the session expired): the profile stays, with its settings, and signs in again.
    private fun forgetIfSessionGone(error: Throwable) {
        if ((error as? Stremio.ApiException)?.code != Stremio.NO_SESSION || isFinishing) return
        Profiles.markSignedOut(this, profile.id)
        startActivity(Intent(this, ProfilesActivity::class.java).putExtra("signIn", profile.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private companion object {
        const val HEADER_H = 76 // dp
        const val WHATS_NEW_WAIT = 1_500L // ms after Home opens: its first row draws before the pop-up covers it
        // 2:3 posters, six and a bit across (56 + 7 × 120 + 6 × 16 = 992 of 960 dp): the seventh runs off the edge to
        // say there's more, like the TV apps the layout follows.
        const val CARD_W = POSTER_W
        const val CARD_H = POSTER_H
        // Continue Watching: 16:9, four across (4 × 200 + 3 × 16 = 848 dp, the row between the margins).
        const val WIDE_W = 200
        const val WIDE_H = WIDE_W * 9 / 16
        const val LIST_W = 120
        const val LIST_H = 180
        const val LIST_GAP = 16
        const val LIST_COLUMNS = 6
        const val LIST_TOP_ROOM = 8 // dp above a grid row for the focused poster's growth (4% of 180 dp) and rim
        const val LIST_TOP = 280 - LIST_TOP_ROOM
        const val INFO_W = 360 // the cinema's line
        const val NAV_R = 18 // dp: the top bar's pills
        const val NAV_GAP = 4 // dp between them
        const val WARM_AFTER = 3000L // ms after launch before other pages' posters are fetched ahead
        const val SWITCH_WAIT = 300L // ms the remote rests on a page's name before it opens: passing over names opens nothing
        const val LOGO_W = 360 // the cinema's title logo
        const val LOGO_H = 100
        const val RADIUS = 6
        const val LOGO_INSET = 14 // dp around a channel logo inside its card
        const val ROW_TOP_ROOM = HEADING_GAP + 9 // dp between the row title and its cards, plus the focused card's growth (5% of 180 dp a side)
        const val ROW_BOTTOM_ROOM = 38 // room for the focused caption below the poster
        const val SAFE_BOTTOM = 27
        const val CAPTION_BOTTOM = 16 // dp: low enough to leave the focused card a clear gap
        const val DIM = 0.5f
        const val QUIET = 0.65f // the next row remains visible without competing with the active one
        const val HERO_GAP = SECTION_GAP // dp between the focused title's block and the row heading below it
        const val RISE = 200 // dp the rows travel when they come up from the cinema
        // Cards a row starts with (a screenful and a bit), then how many come at a time as the remote nears its end.
        // Building every row's 25 cards at once held the main thread for over a second on a page switch (about 3.6 ms
        // a card), so the first row's posters, already decoded, didn't show until every row was made.
        const val FOCUS_ROOM = 8 // dp around the title block for a focused pill to grow into
        const val START = 9
        const val ROW_LATER = 350L // ms after a page opens before its rows out of sight are made
        const val ROW_STAGGER = 60L // ms between them
        const val PAGE = 12
        const val AHEAD = 8 // cards from a row's end when the next page is asked for: early enough that a row of 9 grows before it would stop short of the margin
        const val FIRST = 10 // cards per row loaded before it's scrolled sideways (a screenful, the one past the edge, one more)
        const val CINEMA_MAX = 8
        const val IDLE = 5 * 60_000L // ms without a key before the cinema takes over (the TV's own screensaver: 10 min)
        const val DIM_AFTER = 10_000L
        const val GENRE_ROWS = 6 // genre rows (and chips) at most on Shows and Movies
        const val CATALOG_WAIT = 6_000
        const val ADVANCE = 20_000L // ms each cinema title shows
        const val REVEAL_WAIT = 1200L // ms the cinema waits for a title's art and logo before showing its words anyway
    }
}
