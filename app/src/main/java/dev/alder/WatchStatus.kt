package dev.alder

import android.content.Context
import org.json.JSONObject

enum class WatchStatus(val storageValue: String) {
    NONE("none"),
    FAVORITE("favorite"),
    WATCHING("watching"),
    ON_HOLD("on_hold"),
    DROPPED("dropped"),
    WATCHED("watched");

    companion object {
        fun from(value: String?): WatchStatus = entries.firstOrNull { it.storageValue == value } ?: NONE
    }
}

/**
 * Alder-owned, profile-scoped status labels. These labels never modify Stremio library state or Jellyfin playback data.
 */
object WatchStatuses {
    private const val PREFS = "watch_statuses"
    private val providers = setOf("jellyfin", "stremio", "iptv")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(context: Context) = "items@" + (Profiles.active(context)?.id ?: "none")

    fun get(context: Context, provider: String, itemId: String): WatchStatus {
        if (provider !in providers || itemId.isBlank()) return WatchStatus.NONE
        return WatchStatus.from(read(context).optJSONObject(provider)?.optString(itemId))
    }

    fun set(context: Context, provider: String, itemId: String, status: WatchStatus) {
        require(provider in providers) { "Unknown content provider." }
        require(itemId.isNotBlank()) { "An item ID is required." }
        val all = read(context)
        val items = all.optJSONObject(provider) ?: JSONObject().also { all.put(provider, it) }
        if (status == WatchStatus.NONE) items.remove(itemId) else items.put(itemId, status.storageValue)
        if (items.length() == 0) all.remove(provider)
        prefs(context).edit().putString(key(context), all.toString()).apply()
    }

    fun items(context: Context, provider: String, status: WatchStatus): Set<String> {
        if (provider !in providers || status == WatchStatus.NONE) return emptySet()
        val items = read(context).optJSONObject(provider) ?: return emptySet()
        return items.keys().asSequence().filter { WatchStatus.from(items.optString(it)) == status }.toSet()
    }

    fun forgetProfile(context: Context, profileId: String) {
        prefs(context).edit().remove("items@" + profileId).apply()
    }

    private fun read(context: Context): JSONObject = runCatching {
        JSONObject(prefs(context).getString(key(context), "{}").orEmpty())
    }.getOrDefault(JSONObject())
}
