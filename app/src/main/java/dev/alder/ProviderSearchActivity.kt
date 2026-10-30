package dev.alder

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

internal data class ProviderSearchHit(
    val provider: String,
    val title: String,
    val subtitle: String,
    val normalized: String,
    val jellyfin: JellyfinItem? = null,
    val stremio: Meta? = null
)

internal fun mergeProviderSearchHits(
    jellyfin: List<JellyfinItem>,
    stremio: List<Meta>,
    preferredProvider: String
): List<ProviderSearchHit> {
    val all = jellyfin.map {
        ProviderSearchHit("jellyfin", it.name, listOfNotNull(it.year?.toString(), it.type).joinToString(" · "),
            normalizeSearchTitle(it.name), jellyfin = it)
    } + stremio.map {
        ProviderSearchHit("stremio", it.name, listOfNotNull(it.year, it.type).joinToString(" · "),
            normalizeSearchTitle(it.name), stremio = it)
    }
    val priority = if (preferredProvider == "stremio") listOf("stremio", "jellyfin") else listOf("jellyfin", "stremio")
    return all.filter { it.normalized.isNotBlank() }
        .groupBy { it.normalized }
        .values.map { group -> group.sortedBy { priority.indexOf(it.provider) }.first() }
        .sortedWith(compareBy<ProviderSearchHit> { priority.indexOf(it.provider) }.thenBy { it.title.lowercase(Locale.ROOT) })
}

private fun normalizeSearchTitle(value: String) = value.lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]"), "")

class ProviderSearchActivity : Screen() {
    private lateinit var query: EditText
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var keyboard: LinearLayout
    private val characterKeys = mutableListOf<TextView>()
    private var arabic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arabic = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("searchKeyboardArabic", false)
        val root = column(Gravity.START).apply {
            setBackgroundColor(BASE)
            setPadding(dp(32), dp(24), dp(32), dp(18))
        }
        root.addView(text(getString(R.string.provider_search), SERIF_TITLE, serif = true))
        query = EditText(this).apply {
            hint = getString(R.string.nav_search)
            setTextColor(TEXT)
            setHintTextColor(TEXT_3)
            textSize = BODY
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            showSoftInputOnFocus = Settings.keyboardMode(this@ProviderSearchActivity) == "system"
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = glass(8)
        }
        root.addView(query, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(14) })
        keyboard = column(Gravity.START).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = glass(10)
            visibility = if (Settings.keyboardMode(this@ProviderSearchActivity) == "builtin") View.VISIBLE else View.GONE
        }
        if (keyboard.visibility == View.VISIBLE) buildKeyboard()
        root.addView(keyboard, LinearLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(8) })
        val searchButton = text(getString(R.string.provider_search), BODY, color = TUNGSTEN).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isFocusable = true
            rowFocus(8)
            setOnClickListener { runSearch() }
        }
        root.addView(searchButton)
        status = text("", SMALL, color = TEXT_2).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)
        results = column(Gravity.START)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(results) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        query.setOnEditorActionListener { _, _, _ -> runSearch(); true }
        query.requestFocus()
        if (Settings.keyboardMode(this) == "system") {
            query.post { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(query, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    private fun buildKeyboard() {
        val rows = if (arabic) listOf("ضصثقفغعهخح", "شسيبلاتنم", "ئءؤرلاى") else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        rows.forEach { letters ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            letters.forEach { letter ->
                val key = text(letter.toString(), BODY, color = TEXT_2).apply {
                    gravity = Gravity.CENTER
                    isFocusable = true
                    rowFocus(6)
                    setOnClickListener { insert(letter.toString()) }
                }
                characterKeys += key
                row.addView(key, LinearLayout.LayoutParams(0, dp(28), 1f).apply { marginEnd = dp(3) })
            }
            keyboard.addView(row, LinearLayout.LayoutParams(-1, dp(29)).apply { if (keyboard.childCount > 0) topMargin = dp(3) })
        }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun control(label: String, weight: Float, action: () -> Unit) {
            controls.addView(text(label, SMALL, color = TEXT_2).apply {
                gravity = Gravity.CENTER
                isFocusable = true
                rowFocus(6)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(28), weight).apply { marginEnd = dp(3) })
        }
        control(if (arabic) "EN" else "ع", 1f) {
            arabic = !arabic
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("searchKeyboardArabic", arabic).apply()
            val chars = (if (arabic) listOf("ضصثقفغعهخح", "شسيبلاتنم", "ئءؤرلاى") else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")).joinToString("")
            characterKeys.forEachIndexed { index, view -> view.text = chars.getOrNull(index)?.toString() ?: "" }
            (controls.getChildAt(0) as? TextView)?.text = if (arabic) "EN" else "ع"
        }
        control(getString(R.string.search_space), 3f) { insert(" ") }
        control("⌫", 1f) {
            val at = query.selectionStart.coerceAtLeast(0)
            if (at > 0) query.text.delete(at - 1, at)
        }
        keyboard.addView(controls, LinearLayout.LayoutParams(-1, dp(28)).apply { topMargin = dp(3) })
    }

    private fun insert(value: String) {
        val at = query.selectionStart.coerceIn(0, query.length())
        query.text.insert(at, value)
        query.setSelection(at + value.length)
    }

    private fun runSearch() {
        val value = query.text.toString().trim()
        if (value.isBlank()) return
        status.text = getString(R.string.searching_providers)
        results.removeAllViews()
        val profile = Profiles.active(this)
        background({
            val jellyfin = runCatching { Jellyfin.search(applicationContext, value) }.getOrDefault(emptyList())
            val stremio = if (profile == null || Profiles.signedOut(applicationContext, profile.id)) emptyList() else
                runCatching {
                    Stremio.addons(profile.authKey).flatMap { addon -> runCatching { addon.search(value) }.getOrDefault(emptyList()) }
                }.getOrDefault(emptyList())
            mergeProviderSearchHits(jellyfin, stremio, Settings.sourcePriority(applicationContext))
        }) { result ->
            result.onSuccess { hits ->
                status.text = getString(R.string.search_provider_results, hits.size)
                hits.forEach { hit ->
                    val row = column(Gravity.START).apply {
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        isFocusable = true
                        rowFocus(8)
                        setOnClickListener {
                            if (hit.jellyfin != null) {
                                startActivity(Intent(this@ProviderSearchActivity, ProviderPlayerActivity::class.java)
                                    .putExtra("jellyfinItemId", hit.jellyfin.id).putExtra("title", hit.jellyfin.name))
                            } else hit.stremio?.let { meta ->
                                startActivity(Intent(this@ProviderSearchActivity, DetailsActivity::class.java)
                                    .putExtra("type", meta.type).putExtra("id", meta.id).putExtra("name", meta.name)
                                    .putExtra("poster", meta.poster).putExtra("background", meta.background))
                            }
                        }
                    }
                    row.addView(text(hit.title, BODY, color = TEXT))
                    row.addView(text(hit.provider.uppercase(Locale.ROOT) + " · " + hit.subtitle, SMALL, color = TEXT_3))
                    results.addView(row, LinearLayout.LayoutParams(-1, -2))
                }
                if (hits.isEmpty()) status.text = getString(R.string.search_no_matches)
                results.getChildAt(0)?.requestFocus()
            }.onFailure { status.text = getString(R.string.search_unavailable) }
        }
    }
}
