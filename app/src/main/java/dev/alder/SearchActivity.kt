package dev.alder

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.util.Locale
import java.text.Normalizer
import java.util.concurrent.Future

/** More complete title matches lead; ties retain the installed add-ons' order. */
internal fun <T> rankSearchTitles(items: List<T>, query: String, title: (T) -> String): List<T> {
    fun words(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    val wanted = words(query)
    if (wanted.isEmpty()) return items
    return items.sortedBy { item ->
        val actual = words(title(item))
        when {
            actual == wanted -> 0
            actual.take(wanted.size) == wanted -> 1
            wanted.all { it in actual } -> 2
            actual.joinToString(" ").contains(wanted.joinToString(" ")) -> 3
            else -> 4
        }
    }
}

internal fun <T> searchAnswersInAddonOrder(answers: Array<List<T>?>): List<T> = answers.filterNotNull().flatten()

/** House Lights search: remote keyboard on the left, focused title and poster rows on the right. */
class SearchActivity : Screen() {
    private lateinit var art: ImageView
    private lateinit var artBack: ImageView
    private lateinit var input: EditText
    private lateinit var clearSearch: View
    private lateinit var resultCount: TextView
    private lateinit var recentBox: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var resultsScroll: ScrollView
    private lateinit var hero: View
    private lateinit var heroLogo: ImageView
    private lateinit var heroName: TextView
    private lateinit var heroFacts: TextView
    private lateinit var heroAbout: TextView
    private lateinit var heroTag: TextView
    private lateinit var heroScore: TextView
    private lateinit var keyboard: LinearLayout
    private val characterKeys = mutableListOf<TextView>()
    private val rightEdgeKeys = mutableSetOf<View>()
    private val cards = mutableListOf<Pair<View, View>>()
    private var firstKey: View? = null
    private var lastKey: View? = null
    private var lastKeyboardFocus: View? = null
    private var firstResult: View? = null
    private var focused: Meta? = null
    private var focusedProvider: JellyfinItem? = null
    private var jellyfinHits: List<JellyfinItem> = emptyList()
    private var jellyfinSearchDone = true
    private var numeric = false
    private var keyboardArabic = false
    private var languageLabel: TextView? = null
    private var request = 0
    private val searchTasks = mutableListOf<Future<*>>()
    private val typed = Runnable { search(false) }
    override val backdrop get() = art

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        keyboardArabic = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("searchKeyboardArabic", false)
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        artBack = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val height = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(BASE); clipChildren = false }
        root.addView(artBack, FrameLayout.LayoutParams(-1, -1))
        root.addView(art, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xA60E0C0A.toInt(), 0x8C0E0C0A.toInt(), 0x660E0C0A, 0x1A0E0C0A))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF70E0C0A.toInt(), 0xBF0E0C0A.toInt(), 0x000E0C0A))
        }, FrameLayout.LayoutParams(-1, dp(260), Gravity.BOTTOM))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x8C0E0C0A.toInt(), 0x000E0C0A))
        }, FrameLayout.LayoutParams(-1, dp(80), Gravity.TOP))
        root.addView(header(), FrameLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(24) })

        val field = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
        }
        field.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(TEXT_2)
        }, LinearLayout.LayoutParams(dp(15), dp(15)))
        input = EditText(this).apply {
            hint = getString(R.string.nav_search)
            setHintTextColor(TEXT_3)
            setTextColor(TEXT)
            textSize = BODY
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            showSoftInputOnFocus = Settings.keyboardMode(this@SearchActivity) == "system"
            background = null
            setPadding(dp(10), 0, dp(5), 0)
            setSelectAllOnFocus(false)
        }
        // The field's own caret shows only while it has focus, and focus lives on our keys: a tungsten bar after the
        // last letter stands in for it.
        val inputBox = FrameLayout(this)
        inputBox.addView(input, FrameLayout.LayoutParams(-1, -1))
        cursor = View(this).apply { setBackgroundColor(TUNGSTEN) }
        inputBox.addView(cursor, FrameLayout.LayoutParams(dp(1), dp(15), Gravity.CENTER_VERTICAL))
        field.addView(inputBox, LinearLayout.LayoutParams(0, dp(34), 1f))
        resultCount = text("", SMALL, color = TEXT_3)
        field.addView(resultCount)
        val clear = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(TEXT_2)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            contentDescription = getString(R.string.search_clear)
            visibility = View.GONE
            rowFocus(11)
            setOnClickListener {
                input.setText("")
                main.removeCallbacks(typed)
                search(false)
                firstKey?.requestFocus()
            }
        }
        field.addView(clear, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(4) })
        clearSearch = clear
        val fieldRest = glassOver(field, 17, shade = FROST_DENSE)
        field.background = fieldRest
        input.setOnFocusChangeListener { _, has ->
            field.background = if (has) android.graphics.drawable.LayerDrawable(arrayOf(fieldRest,
                GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(17).toFloat()
                    setStroke(dp(1), TUNGSTEN_RIM)
                })) else fieldRest
            placeCursor()
            if (!has) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            }
        }
        root.addView(field, FrameLayout.LayoutParams(dp(284), dp(34)).apply { leftMargin = dp(48); topMargin = dp(88) })

        keyboard = column(Gravity.START).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = glassOver(this, 14, shade = FROST_DENSE)
        }
        buildKeyboard()
        val useBuiltinKeyboard = Settings.keyboardMode(this) == "builtin"
        keyboard.visibility = if (useBuiltinKeyboard) View.VISIBLE else View.GONE
        root.addView(keyboard, FrameLayout.LayoutParams(dp(284), dp(190)).apply { leftMargin = dp(48); topMargin = dp(134) })

        recentBox = column(Gravity.START)
        root.addView(recentBox, FrameLayout.LayoutParams(dp(284), -2).apply { leftMargin = dp(48); topMargin = dp(345) })
        showRecent()

        heroLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
        heroName = text("", 48f, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        heroFacts = text("", SMALL, color = TEXT_2).onArt()
        heroTag = imdbTag()
        heroScore = text("", SMALL, color = TEXT_2).onArt()
        heroAbout = text("", BODY, color = TEXT).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        hero = column(Gravity.START).apply {
            addView(heroLogo, LinearLayout.LayoutParams(dp(210), dp(46)))
            addView(heroName, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(this@SearchActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(heroFacts)
                addView(heroTag, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                addView(heroScore, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(heroAbout, LinearLayout.LayoutParams(dp(420), -2).apply { topMargin = dp(5) })
            visibility = View.INVISIBLE
        }
        root.addView(hero, FrameLayout.LayoutParams(dp((width - 408).toInt()), dp(122)).apply {
            leftMargin = dp(360); topMargin = dp(88)
        })
        results = column(Gravity.START)
        resultsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(results)
        }
        // Runs to the screen's right edge, as Home's rows do: stopping 48 dp short sliced the last poster in half.
        root.addView(resultsScroll, FrameLayout.LayoutParams(dp((width - 360).toInt()), dp((height - 220).toInt())).apply {
            leftMargin = dp(360); topMargin = dp(220)
        })
        window.setBackgroundDrawable(null)
        setContentView(root)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                placeCursor()
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                ++request
                searchTasks.forEach { it.cancel(true) }
                searchTasks.clear()
                main.removeCallbacks(typed)
                main.postDelayed(typed, TYPING_PAUSE)
            }
        })
        input.setOnEditorActionListener { _, _, _ ->
            main.removeCallbacks(typed)
            search(true)
            true
        }
        // Our keyboard first: the field never takes focus on opening, so the Android keyboard stays shut until the keyboard key.
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        if (useBuiltinKeyboard) firstKey?.post { firstKey?.requestFocus() } else input.post {
            input.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        search(false)
    }

    private lateinit var cursor: View

    private fun placeCursor() {
        cursor.visibility = if (input.hasFocus()) View.GONE else View.VISIBLE
        cursor.translationX = input.paddingStart + input.paint.measureText(input.text.toString())
    }

    private fun header(): View {
        val profile = Profiles.active(this)
        val bar = FrameLayout(this).apply { setPadding(dp(48), 0, dp(48), 0) }
        val avatar = text(profile?.name?.take(1)?.uppercase() ?: "A", BODY, bold = true, color = TUNGSTEN).apply {
            gravity = Gravity.CENTER
            background = glass(15)
            rowFocus(15, rest = { glass(15) })
            setOnClickListener {
                choose(profile?.name ?: getString(R.string.nav_profile),
                    listOf(getString(R.string.menu_switch_profile), getString(R.string.menu_settings))) { picked ->
                    startActivity(Intent(this@SearchActivity,
                        if (picked == 0) ProfilesActivity::class.java else SettingsActivity::class.java)
                        .apply { if (picked == 0) {
                            putExtra("pick", true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        } })
                }
            }
        }
        bar.addView(avatar, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.START or Gravity.CENTER_VERTICAL))
        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val searchTab = FrameLayout(this).apply {
            isFocusable = true
            contentDescription = getString(R.string.nav_search)
            addView(ImageView(this@SearchActivity).apply {
                setImageResource(R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(TEXT)
            }, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(3) })
            addView(View(this@SearchActivity).apply { background = rounded(TUNGSTEN, 3) },
                FrameLayout.LayoutParams(dp(3), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(2) })
            setOnFocusChangeListener { _, has -> background = if (has) glass(18, true) else null }
            setOnClickListener { input.requestFocus() }
        }
        nav.addView(searchTab, LinearLayout.LayoutParams(dp(44), dp(30)))
        // The page goes to MainActivity by its English name; the bar shows it in the viewer's language.
        listOf("Home" to R.string.tab_home, "Shows" to R.string.tab_shows, "Movies" to R.string.tab_movies,
            "My List" to R.string.tab_my_list).forEach { (page, words) ->
            nav.addView(text(words, BODY, color = TEXT_2).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                isFocusable = true
                setOnFocusChangeListener { _, has ->
                    background = if (has) glass(18, true) else null
                    setTextColor(if (has) TEXT else TEXT_2)
                }
                setOnClickListener {
                    startActivity(Intent(this@SearchActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("page", page))
                    finish()
                }
            }, LinearLayout.LayoutParams(-2, dp(30)).apply { marginStart = dp(4) })
        }
        nav.background = glassOver(nav, 20, shade = FROST_READING)
        bar.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        bar.addView(TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            textSize = SMALL
            setTextColor(TEXT_2)
            typeface = text("", SMALL).typeface
        }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        return bar
    }

    private fun buildKeyboard() {
        val letterRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val letters = letterRows.joinToString("")
        val arabicLetters = listOf("ضصثقفغعهخح", "شسيبلاتنم", "ئءؤرلاى").joinToString("")
        letterRows.forEach { rowLetters ->
            val row = keyRow()
            rowLetters.forEachIndexed { column, letter ->
                val index = characterKeys.size
                val key = key(letter.toString(), row, 1, column == rowLetters.lastIndex) {
                    append(characterKeys[index].text.toString())
                }
                characterKeys += key
            }
        }
        characterKeys.forEachIndexed { index, view -> if (keyboardArabic) view.text = arabicLetters[index].toString() }
        val controls = keyRow()
        val mode = key("123", controls, 3, false) {
            numeric = !numeric
            val symbols = "1234567890@#$%&*()!?.,:/+"
            characterKeys.forEachIndexed { index, view ->
                view.text = if (numeric) symbols.getOrNull(index)?.toString() ?: "·" else (if (keyboardArabic) arabicLetters else letters).getOrNull(index)?.toString() ?: "·"
            }
            modeLabel?.text = if (numeric) "ABC" else "123"
        }
        modeLabel = mode
        languageLabel = key(if (keyboardArabic) "EN" else "ع", controls, 2, false) {
            keyboardArabic = !keyboardArabic
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("searchKeyboardArabic", keyboardArabic).apply()
            characterKeys.forEachIndexed { index, view ->
                if (!numeric) view.text = (if (keyboardArabic) arabicLetters else letters).getOrNull(index)?.toString() ?: "·"
            }
            languageLabel?.text = if (keyboardArabic) "EN" else "ع"
        }
        key("−", controls, 2, false) { append("-") }
        key("⌫", controls, 3, true) { delete() }
        val last = keyRow()
        key(getString(R.string.search_space).uppercase(), last, 8, true) { append(" ") }
        last.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            imageTintList = ColorStateList.valueOf(TEXT_3)
            scaleType = ImageView.ScaleType.CENTER
            alpha = 0.45f
            contentDescription = getString(R.string.search_voice_unavailable)
            isFocusable = false
            isClickable = false
        }, LinearLayout.LayoutParams(dp(49), -1).apply { marginStart = dp(3) })
    }

    private var modeLabel: TextView? = null

    private fun keyRow() = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        keyboard.addView(this, LinearLayout.LayoutParams(-1, dp(30)).apply { if (keyboard.childCount > 0) topMargin = dp(4) })
    }

    private fun key(label: String, row: LinearLayout, span: Int, rightEdge: Boolean, click: () -> Unit): TextView {
        val view = text(label, if (label.length > 1) SMALL else BODY, color = TEXT_2).apply {
            gravity = Gravity.CENTER
            isFocusable = true
        }
        view.rowFocus(7, rest = { if (lastKey === view) tile(7) else null }) { has ->
            if (has) lastKeyboardFocus = view
        }
        view.setOnClickListener {
            val previous = lastKey
            lastKey = view
            if (previous != null && previous !== view && !previous.hasFocus()) previous.background = null
            click()
        }
        row.addView(view, LinearLayout.LayoutParams(dp(23 * span + 3 * (span - 1)), -1).apply {
            if (row.childCount > 0) marginStart = dp(3)
        })
        if (firstKey == null) firstKey = view
        if (rightEdge) rightEdgeKeys += view
        return view
    }

    private fun append(value: String) {
        val at = input.selectionStart.coerceIn(0, input.length())
        input.text.insert(at, value)
        input.setSelection(at + value.length)
    }

    private fun delete() {
        val at = input.selectionStart.coerceIn(0, input.length())
        if (at > 0) {
            input.text.delete(at - 1, at)
            input.setSelection(at - 1)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focus = currentFocus
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focus === input && clearSearch.visibility == View.VISIBLE && clearSearch.requestFocus()) return true
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && focus === clearSearch && input.requestFocus()) return true
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focus === input) {
                if (firstKey?.requestFocus() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focus in rightEdgeKeys) {
                if (firstResult?.requestFocus() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && cards.any { it.first === focus }) {
                if (firstResult === focus || cards.indexOfFirst { it.first === focus } == 0) {
                    if ((lastKeyboardFocus ?: firstKey)?.requestFocus() == true) return true
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_DEL) { delete(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_SPACE) { append(" "); return true }
            val char = event.unicodeChar.takeIf { it in 32..126 }?.toChar()
            if (char != null && !event.isCtrlPressed && !event.isAltPressed) {
                append(char.toString())
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun recentKey() = "recent:${Profiles.active(this)?.id.orEmpty()}"

    private fun recent() = getSharedPreferences("search", MODE_PRIVATE).getString(recentKey(), "").orEmpty()
        .split("\n").filter { it.isNotBlank() }

    private fun remember(query: String) {
        val values = (listOf(query) + recent().filterNot { it.equals(query, ignoreCase = true) }).take(RECENT)
        getSharedPreferences("search", MODE_PRIVATE).edit().putString(recentKey(), values.joinToString("\n")).apply()
        showRecent()
    }

    private fun showRecent() {
        recentBox.removeAllViews()
        val values = recent()
        if (values.isEmpty()) return
        recentBox.addView(caps(R.string.search_recent, TEXT_3), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        values.chunked(2).forEach { pair ->
            val line = LinearLayout(this)
            pair.forEach { query ->
                line.addView(chip(query).apply {
                    textSize = SMALL
                    maxWidth = dp(110)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    contentDescription = getString(R.string.search_recent_hint, query)
                    setOnClickListener {
                        input.setText(query)
                        input.setSelection(query.length)
                        main.removeCallbacks(typed)
                        search(true)
                    }
                    setOnLongClickListener {
                        choose(getString(R.string.search_remove_recent),
                            listOf(getString(R.string.search_remove_query, query), getString(R.string.action_cancel))) { picked ->
                            if (picked == 0) {
                                getSharedPreferences("search", MODE_PRIVATE).edit()
                                    .putString(recentKey(), recent().filterNot { it == query }.joinToString("\n")).apply()
                                showRecent()
                            }
                        }
                        true
                    }
                }, LinearLayout.LayoutParams(-2, dp(24)).apply { if (line.childCount > 0) marginStart = dp(5) })
            }
            recentBox.addView(line, LinearLayout.LayoutParams(-1, dp(27)))
        }
        recentBox.addView(text(R.string.search_hold_to_remove, SMALL, color = TEXT_3),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
    }

    private fun search(moveFocus: Boolean) {
        val query = input.text.toString().trim()
        val token = ++request
        searchTasks.forEach { it.cancel(true) }
        searchTasks.clear()
        firstResult = null
        cards.clear()
        focused = null
        focusedProvider = null
        hero.visibility = View.INVISIBLE
        results.removeAllViews()
        resultsScroll.scrollTo(0, 0)
        resultCount.text = ""
        results.addView(ring(), LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(12) })
        jellyfinHits = emptyList()
        jellyfinSearchDone = query.isEmpty()

        if (query.isEmpty()) {
            background({
                val addons = Stremio.savedAddons(authKey.orEmpty()).orEmpty().ifEmpty { Stremio.addons(authKey.orEmpty()) }
                val catalogs = addons.flatMap { it.catalogs }
                    .filter { it.type == "movie" || it.type == "series" }
                catalogs.sortedByDescending { it.name.contains("popular", true) || it.name.contains("trending", true) }
                    .take(4).asSequence()
                    .map { catalog -> runCatching {
                        catalog.addon.savedCatalog(catalog)?.takeIf { it.isNotEmpty() } ?: catalog.addon.catalog(catalog)
                    }.getOrDefault(emptyList()).filterNot { isAddonMessage(it.name) }.take(PER_TYPE) }
                    .firstOrNull { it.isNotEmpty() }.orEmpty()
            }) { answer ->
                if (token != request) return@background
                val titles = answer.getOrNull().orEmpty()
                if (titles.isEmpty()) {
                    results.removeAllViews()
                    results.addView(failed(getString(R.string.search_popular_unavailable)) { search(false) })
                } else render(linkedMapOf(getString(R.string.search_popular) to titles), "", false)
            }
            return
        }

        var stremioHits = emptyList<Meta>()
        var stremioFinished = false
        var stremioFailed = false
        var moveFocusWhenReady = moveFocus

        fun updateResults(final: Boolean = false) {
            if (token != request || isFinishing || isDestroyed) return
            val merged = mergeProviderSearchHits(
                jellyfinHits, stremioHits, Settings.sourcePriority(applicationContext)
            )
            val ranked = rankSearchTitles(merged, query) { it.title }
            val movies = ranked.filter { hit ->
                hit.jellyfin?.type?.equals("Movie", true) ?: (hit.stremio?.type == "movie")
            }.take(PER_TYPE)
            val shows = ranked.filter { hit ->
                hit.jellyfin?.type?.equals("Series", true) ?: (hit.stremio?.type == "series")
            }.take(PER_TYPE)
            val sections = linkedMapOf<String, List<ProviderSearchHit>>()
            if (movies.isNotEmpty()) sections[getString(R.string.tab_movies)] = movies
            if (shows.isNotEmpty()) sections[getString(R.string.tab_shows)] = shows

            if (sections.isNotEmpty()) {
                renderUnified(sections, query, moveFocusWhenReady && (final || stremioFinished || jellyfinSearchDone))
                moveFocusWhenReady = false
            } else if (stremioFinished && jellyfinSearchDone) {
                results.removeAllViews()
                results.addView(if (stremioFailed)
                    failed(getString(R.string.search_unavailable)) { search(false) }
                else text(R.string.search_no_matches, BODY, color = TEXT_2))
                resultCount.text = ""
            }
        }

        background({ Jellyfin.search(applicationContext, query) }) { answer ->
            if (token != request) return@background
            jellyfinHits = answer.getOrDefault(emptyList())
            jellyfinSearchDone = true
            updateResults(final = true)
        }

        background({ Stremio.savedAddons(authKey.orEmpty()) ?: Stremio.addons(authKey.orEmpty()) }) { loaded ->
            if (token != request) return@background
            val addons = loaded.getOrElse {
                stremioFailed = true
                stremioFinished = true
                updateResults(final = true)
                return@background
            }
            val answers = arrayOfNulls<List<Meta>>(addons.size)
            var completed = 0
            var failedCount = 0
            fun update() {
                if (token != request) return
                stremioHits = searchAnswersInAddonOrder(answers)
                    .filter { it.type == "movie" || it.type == "series" }
                    .filterNot { isAddonMessage(it.name) }
                    .distinctBy { it.type to it.id }
                if (completed == addons.size) {
                    stremioFinished = true
                    stremioFailed = addons.isNotEmpty() && failedCount == completed
                }
                updateResults(final = stremioFinished)
            }
            if (addons.isEmpty()) {
                stremioFinished = true
                updateResults(final = true)
                return@background
            }
            main.postDelayed({
                if (token == request && !stremioFinished) {
                    stremioFinished = true
                    stremioFailed = completed > 0 && failedCount == completed
                    updateResults(final = true)
                }
            }, SEARCH_WAIT)
            addons.forEachIndexed { index, addon ->
                searchTasks += searchPool.submit {
                    val answer = runCatching { addon.search(query) }
                    main.post {
                        if (isDestroyed || token != request || stremioFinished) return@post
                        completed++
                        answer.fold({ answers[index] = it }) { failedCount++ }
                        update()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        searchTasks.forEach { it.cancel(true) }
        super.onDestroy()
    }

    private fun render(sections: Map<String, List<Meta>>, query: String, moveFocus: Boolean) {
        val hits = sections.mapValues { (_, metas) ->
            metas.map { meta ->
                ProviderSearchHit("stremio", meta.name,
                    listOfNotNull(meta.year, meta.type).joinToString(" · "), "", stremio = meta)
            }
        }
        renderUnified(hits, query, moveFocus)
    }

    private fun renderUnified(sections: Map<String, List<ProviderSearchHit>>, query: String, moveFocus: Boolean) {
        val selected = (currentFocus?.tag as? String)?.takeIf { resultsScroll.hasFocus() }
        cards.clear()
        firstResult = null
        results.removeAllViews()
        val total = sections.values.sumOf { it.size }
        resultCount.text = if (total == 0 || query.isEmpty()) "" else plural(R.plurals.search_titles, total)
        if (total == 0) {
            hero.visibility = View.INVISIBLE
            results.addView(text(R.string.search_no_matches, BODY, color = TEXT_2))
            return
        }
        sections.forEach { (label, list) ->
            val group = column(Gravity.START)
            group.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(caps(label).onArt())
                addView(text(list.size.toString(), SMALL, color = TEXT_3).onArt(),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(9) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            val caption = caption("").apply {
                visibility = View.INVISIBLE
                maxWidth = dp(280)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            val row = LinearLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                setPadding(dp(6), dp(8), dp(48), dp(8))
            }
            val scroller = object : HorizontalScrollView(this) {
                override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect) = 0
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                    if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT &&
                        row.getChildAt(row.childCount - 1)?.isFocused == true) return true
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }
            list.forEachIndexed { index, hit ->
                row.addView(card(hit, caption, scroller, index == 0), LinearLayout.LayoutParams(dp(88), dp(132)).apply {
                    if (index > 0) marginStart = dp(14)
                })
            }
            group.addView(scroller, LinearLayout.LayoutParams(-1, dp(148)))
            group.addView(caption, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            results.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(28) })
        }
        sections.values.firstOrNull()?.firstOrNull()?.let(::show)
        if (selected != null) (cards.firstOrNull { it.first.tag == selected }?.first ?: firstResult)?.requestFocus()
        else if (moveFocus) firstResult?.requestFocus()
    }

    private fun card(hit: ProviderSearchHit, caption: TextView, scroller: HorizontalScrollView, firstInRow: Boolean): View {
        val provider = hit.jellyfin
        val meta = hit.stremio
        val imageUrl = provider?.let { Jellyfin.imageUrl(this, it.id, it.imageTag, 600) }
            ?: meta?.poster ?: meta?.background
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(BASE) }
        val shade = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0.5f }
        val card = FrameLayout(this).apply {
            tag = if (provider != null) "jellyfin:${provider.id}" else "${meta?.type}:${meta?.id}"
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
            }
            clipToOutline = true
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(shade, FrameLayout.LayoutParams(-1, -1))
            cardFocus(6) { has ->
                scroller.clipChildren = true
                scroller.clipToPadding = true
                resultsScroll.clipChildren = true
                resultsScroll.clipToPadding = true
                shade.animate().alpha(if (has) 0f else 0.5f).setDuration(150).start()
                if (has) {
                    (scroller.parent as? View)?.let { group ->
                        resultsScroll.smoothScrollTo(0, group.top)
                        for (i in 0 until results.childCount) results.getChildAt(i).let { it.alpha = if (it.top < group.top) 0f else 1f }
                    }
                    val year = provider?.year?.toString() ?: meta?.year
                    caption.text = hit.title + (year?.let { "  ·  " + it.take(4) } ?: "")
                    caption.visibility = View.VISIBLE
                    val parentRow = parent as ViewGroup
                    val target = (left - parentRow.paddingStart).coerceIn(0, (parentRow.width - scroller.width).coerceAtLeast(0))
                    scroller.smoothScrollTo(target, 0)
                    for (i in 0 until parentRow.childCount) parentRow.getChildAt(i).let {
                        it.animate().alpha(if (it.left < target + parentRow.paddingStart - 1) 0f else 1f).setDuration(150).start()
                    }
                    caption.post {
                        caption.translationX = (left - target).toFloat()
                            .coerceIn(0f, (scroller.width - dp(48) - caption.width).coerceAtLeast(0).toFloat())
                    }
                    if (provider != null) showProvider(provider) else meta?.let(::show)
                } else caption.visibility = View.INVISIBLE
            }
            setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) remember(query)
                if (provider != null) {
                    startActivity(Intent(this@SearchActivity, ProviderDetailsActivity::class.java)
                        .putExtra("jellyfinItemId", provider.id))
                } else meta?.let { title ->
                    startActivity(Intent(this@SearchActivity, DetailsActivity::class.java)
                        .putExtra("type", title.type).putExtra("id", title.id).putExtra("name", title.name)
                        .putExtra("poster", title.poster).putExtra("background", title.background))
                }
            }
            if (firstInRow) setOnKeyListener { _, code, event ->
                if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT)
                    (lastKeyboardFocus ?: firstKey)?.requestFocus() == true
                else false
            }
        }
        cards += card to shade
        if (firstResult == null) firstResult = card
        imageUrl?.let { Images.load(it, image, dp(96)) }
        return card
    }

    private fun show(hit: ProviderSearchHit) {
        hit.jellyfin?.let(::showProvider) ?: hit.stremio?.let(::show)
    }

    private fun showProvider(item: JellyfinItem) {
        if (focusedProvider?.id == item.id && focused == null) return
        focused = null
        focusedProvider = item
        hero.visibility = View.VISIBLE
        val backdropUrl = Jellyfin.backdropUrl(this, item.id, 1800)
        val posterUrl = Jellyfin.imageUrl(this, item.id, item.imageTag, 1200)
        val url = backdropUrl ?: posterUrl
        if (url != null && url != art.tag) {
            art.animate().cancel()
            if (art.drawable != null) artBack.setImageDrawable(art.drawable)
            art.alpha = 0f
            Images.load(url, art, resources.displayMetrics.widthPixels, urgent = true, failed = {
                if (focusedProvider === item && url == backdropUrl && posterUrl != null) {
                    Images.load(posterUrl, art, resources.displayMetrics.widthPixels, urgent = true) {
                        if (focusedProvider === item) { art.animate().alpha(1f).setDuration(300).start(); refrost() }
                    }
                } else if (focusedProvider === item) {
                    art.setImageDrawable(artBack.drawable)
                    art.alpha = 1f
                    refrost()
                }
            }) {
                if (focusedProvider === item) { art.animate().alpha(1f).setDuration(300).start(); refrost() }
            }
        } else if (url == null) {
            art.tag = null
            art.setImageDrawable(null)
            artBack.setImageDrawable(null)
            refrost()
        }
        heroLogo.visibility = View.GONE
        heroName.visibility = View.VISIBLE
        heroName.text = item.name
        heroFacts.text = listOfNotNull(
            item.year?.toString(), item.officialRating,
            item.genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() }
        ).joinToString("  ·  ")
        heroTag.visibility = View.GONE
        heroScore.visibility = View.GONE
        heroAbout.text = item.overview
        heroAbout.visibility = if (item.overview.isBlank()) View.GONE else View.VISIBLE
    }

    private fun show(meta: Meta) {
        if (focused?.type == meta.type && focused?.id == meta.id) return
        focused = meta
        focusedProvider = null
        hero.visibility = View.VISIBLE
        val url = meta.background ?: meta.poster
        if (url != null && url != art.tag) {
            art.animate().cancel()
            if (art.drawable != null) artBack.setImageDrawable(art.drawable)
            art.alpha = 0f
            Images.load(url, art, resources.displayMetrics.widthPixels, urgent = true, failed = {
                if (focused === meta) {
                    art.setImageDrawable(artBack.drawable)
                    art.alpha = 1f
                    refrost()
                }
            }) {
                if (focused === meta) { art.animate().alpha(1f).setDuration(300).start(); refrost() }
            }
        } else if (url == null) {
            art.tag = null
            art.setImageDrawable(null)
            artBack.setImageDrawable(null)
            refrost()
        }
        heroName.text = meta.name
        heroName.visibility = View.GONE
        heroLogo.visibility = View.VISIBLE
        Images.load(meta.logo ?: Images.logoFor(meta.id), heroLogo, dp(210), urgent = true, failed = {
            if (focused === meta) { heroLogo.visibility = View.GONE; heroName.visibility = View.VISIBLE }
        })
        heroFacts.text = listOfNotNull(meta.year, meta.genres.firstOrNull(), meta.runtime?.let(::runtime)).joinToString("  ·  ") +
            if (meta.rating != null) "  ·" else ""
        heroTag.visibility = if (meta.rating == null) View.GONE else View.VISIBLE
        heroScore.visibility = heroTag.visibility
        heroScore.text = meta.rating.orEmpty()
        val description = meta.description.orEmpty()
        heroAbout.text = description
        heroAbout.visibility = if (meta.description.isNullOrBlank()) View.GONE else View.VISIBLE
        heroAbout.post {
            if (focused !== meta || heroAbout.text.toString() != description) return@post
            val layout = heroAbout.layout ?: return@post
            if (layout.lineCount >= 2 && layout.getEllipsisCount(1) > 0) {
                val end = layout.getLineStart(1) + layout.getEllipsisStart(1)
                heroAbout.text = synopsisAtSentence(description, end)
            }
        }
    }

    private companion object {
        const val RECENT = 6
        const val PER_TYPE = 12
        const val TYPING_PAUSE = 500L
        const val SEARCH_WAIT = 6000L
        val searchPool = Images.newestFirst(8)
    }
}
