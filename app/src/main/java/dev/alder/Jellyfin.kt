package dev.alder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

data class JellyfinAccount(val serverUrl: String, val accessToken: String, val userId: String, val username: String)
data class JellyfinItem(
    val id: String, val name: String, val type: String, val overview: String,
    val imageTag: String?, val year: Int?, val runTimeTicks: Long?,
    val played: Boolean, val playbackPositionTicks: Long,
    val genres: List<String> = emptyList(), val tagline: String? = null,
    val communityRating: Double? = null, val officialRating: String? = null
)
data class JellyfinPlayback(val itemId: String, val mediaSourceId: String?, val playSessionId: String?, val streamUrl: String, val directPlay: Boolean)
class JellyfinException(message: String, val statusCode: Int? = null) : java.io.IOException(message)

/**
 * Jellyfin API access scoped to the active Alder profile.
 * Passwords are never persisted; only the server URL and server-issued access token are stored locally.
 */
object Jellyfin {
    private const val PREFS = "jellyfin"
    private const val CLIENT_NAME = "Alder"
    private const val DEVICE_NAME = "Android TV"
    private const val DEVICE_ID = "alder-android-tv"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(context: Context, name: String) = name + "@" + (Profiles.active(context)?.id ?: "none")

    fun account(context: Context): JellyfinAccount? {
        val p = prefs(context)
        val server = p.getString(key(context, "server"), null) ?: return null
        val token = p.getString(key(context, "token"), null) ?: return null
        val userId = p.getString(key(context, "userId"), null) ?: return null
        return JellyfinAccount(server, token, userId, p.getString(key(context, "username"), "").orEmpty())
    }

    fun serverUrl(context: Context) = prefs(context).getString(key(context, "server"), "").orEmpty()

    fun configure(context: Context, serverUrl: String) {
        prefs(context).edit().putString(key(context, "server"), normalizeServerUrl(serverUrl)).apply()
    }

    fun saveAccount(context: Context, account: JellyfinAccount) {
        prefs(context).edit().putString(key(context, "server"), normalizeServerUrl(account.serverUrl))
            .putString(key(context, "token"), account.accessToken).putString(key(context, "userId"), account.userId)
            .putString(key(context, "username"), account.username).apply()
    }

    fun disconnect(context: Context) {
        prefs(context).edit().remove(key(context, "token")).remove(key(context, "userId"))
            .remove(key(context, "username")).apply()
    }

    fun forgetProfile(context: Context, profileId: String) {
        prefs(context).edit().remove("server@$profileId").remove("token@$profileId")
            .remove("userId@$profileId").remove("username@$profileId").apply()
    }

    fun normalizeServerUrl(value: String): String {
        val raw = value.trim().trimEnd('/')
        require(raw.isNotEmpty()) { "Enter a Jellyfin server address." }
        val uri = java.net.URI(if ("://" in raw) raw else "http://$raw")
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "Use a valid HTTP or HTTPS server address." }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "The server address must not contain credentials, a query, or a fragment."
        }
        return uri.toASCIIString().trimEnd('/')
    }

    fun authenticate(serverUrl: String, username: String, password: String): JellyfinAccount {
        val server = normalizeServerUrl(serverUrl)
        require(username.isNotBlank()) { "Enter your Jellyfin username." }
        require(password.isNotEmpty()) { "Enter your Jellyfin password." }
        val auth = "MediaBrowser Client=\"$CLIENT_NAME\", Device=\"$DEVICE_NAME\", DeviceId=\"$DEVICE_ID\", Version=\"1\""
        val body = JSONObject().put("Username", username).put("Pw", password).toString()
        val answer = Http.post("$server/Users/AuthenticateByName", body, timeoutMs = 20_000,
            headers = mapOf("X-Emby-Authorization" to auth, "Accept" to "application/json"))
        val json = runCatching { JSONObject(answer) }.getOrElse { throw JellyfinException("The server returned an invalid sign-in response.") }
        val token = json.optString("AccessToken")
        val user = json.optJSONObject("User")
        val userId = user?.optString("Id").orEmpty()
        if (token.isBlank() || userId.isBlank()) throw JellyfinException("Jellyfin did not return an access token and user ID.")
        return JellyfinAccount(server, token, userId, user?.optString("Name").orEmpty().ifBlank { username })
    }

    fun request(context: Context, path: String, query: Map<String, String> = emptyMap()): String {
        val account = account(context) ?: throw JellyfinException("Jellyfin is not connected.")
        require(path.startsWith("/") && !path.startsWith("//")) { "Invalid Jellyfin API path." }
        val suffix = if (query.isEmpty()) "" else query.entries.joinToString("&", prefix = "?") {
            encode(it.key) + "=" + encode(it.value)
        }
        return Http.get(account.serverUrl + path + suffix, timeoutMs = 20_000,
            headers = mapOf("X-Emby-Token" to account.accessToken, "Accept" to "application/json"))
    }

    fun items(context: Context, parentId: String? = null, includeTypes: List<String> = listOf("Movie", "Series"),
              limit: Int = 100, startIndex: Int = 0): List<JellyfinItem> {
        val account = account(context) ?: return emptyList()
        val query = linkedMapOf("UserId" to account.userId, "Recursive" to "true",
            "IncludeItemTypes" to includeTypes.joinToString(","), "SortBy" to "DateCreated",
            "SortOrder" to "Descending", "Fields" to "Overview,DateCreated,RunTimeTicks,UserData,ProductionYear,ImageTags",
            "Limit" to limit.coerceIn(1, 200).toString(), "StartIndex" to startIndex.coerceAtLeast(0).toString())
        if (!parentId.isNullOrBlank()) query["ParentId"] = parentId
        return JSONObject(request(context, "/Users/" + encode(account.userId) + "/Items", query))
            .optJSONArray("Items").toItems()
    }

    fun item(context: Context, itemId: String): JellyfinItem {
        val account = account(context) ?: throw JellyfinException("Jellyfin is not connected.")
        val response = JSONObject(request(context, "/Users/" + encode(account.userId) + "/Items/" + encode(itemId),
            mapOf("Fields" to "Overview,Genres,People,RunTimeTicks,UserData,ProductionYear,ImageTags,Taglines,CommunityRating,OfficialRating")))
        return response.toItem() ?: throw JellyfinException("Jellyfin returned an invalid item.")
    }

    fun search(context: Context, query: String, limit: Int = 30): List<JellyfinItem> {
        val account = account(context) ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val response = JSONObject(request(context, "/Users/" + encode(account.userId) + "/Items",
            mapOf("UserId" to account.userId, "SearchTerm" to query, "Recursive" to "true",
                "IncludeItemTypes" to "Movie,Series,Episode", "Limit" to limit.coerceIn(1, 100).toString(),
                "Fields" to "Overview,RunTimeTicks,UserData,ProductionYear,ImageTags")))
        return response.optJSONArray("Items").toItems()
    }

    fun views(context: Context): List<JellyfinItem> {
        val account = account(context) ?: return emptyList()
        return JSONObject(request(context, "/Users/" + encode(account.userId) + "/Views"))
            .optJSONArray("Items").toItems()
    }

    fun resumeItems(context: Context, limit: Int = 20): List<JellyfinItem> {
        val account = account(context) ?: return emptyList()
        return JSONObject(request(context, "/Users/" + encode(account.userId) + "/Items/Resume",
            mapOf("UserId" to account.userId, "Limit" to limit.coerceIn(1, 100).toString(),
                "Fields" to "Overview,RunTimeTicks,UserData,ProductionYear,ImageTags")))
            .optJSONArray("Items").toItems()
    }

    fun latestItems(context: Context, parentId: String, limit: Int = 20): List<JellyfinItem> {
        val account = account(context) ?: return emptyList()
        return JSONArray(request(context, "/Users/" + encode(account.userId) + "/Items/Latest",
            mapOf("ParentId" to parentId, "Limit" to limit.coerceIn(1, 100).toString(),
                "Fields" to "Overview,RunTimeTicks,UserData,ProductionYear,ImageTags"))).toItems()
    }

    fun playback(context: Context, itemId: String): JellyfinPlayback {
        val account = account(context) ?: throw JellyfinException("Jellyfin is not connected.")
        val body = JSONObject().put("UserId", account.userId).toString()
        val response = JSONObject(Http.post(account.serverUrl + "/Items/" + encode(itemId) + "/PlaybackInfo",
            body, headers = mapOf("X-Emby-Token" to account.accessToken, "Accept" to "application/json")))
        val sources = response.optJSONArray("MediaSources") ?: JSONArray()
        if (sources.length() == 0) throw JellyfinException("Jellyfin did not provide a playable media source.")
        val source = (0 until sources.length()).map { sources.getJSONObject(it) }
            .firstOrNull { it.optBoolean("SupportsDirectPlay", true) || it.optBoolean("SupportsDirectStream") }
            ?: sources.getJSONObject(0)
        val sourceId = source.optString("Id").takeIf { it.isNotBlank() }
        val session = response.optString("PlaySessionId").takeIf { it.isNotBlank() }
        val direct = source.optBoolean("SupportsDirectPlay", true) || source.optBoolean("SupportsDirectStream")
        val transcodePath = source.optString("TranscodingUrl").takeIf { it.isNotBlank() }
            ?: response.optString("TranscodingUrl").takeIf { it.isNotBlank() }
        val stream = if (!direct && transcodePath != null) {
            val url = if (transcodePath.startsWith("http", true)) transcodePath
                else account.serverUrl + "/" + transcodePath.trimStart('/')
            if (url.contains("api_key=", true)) url else url + (if ("?" in url) "&" else "?") + "api_key=" + encode(account.accessToken)
        } else buildString {
            append(account.serverUrl).append("/Videos/").append(encode(itemId)).append("/stream?Static=").append(direct)
            if (sourceId != null) append("&MediaSourceId=").append(encode(sourceId))
            if (session != null) append("&PlaySessionId=").append(encode(session))
            append("&api_key=").append(encode(account.accessToken))
        }
        return JellyfinPlayback(itemId, sourceId, session, stream, direct)
    }

    fun reportStarted(context: Context, itemId: String, mediaSourceId: String?, playSessionId: String?, directPlay: Boolean) {
        val account = account(context) ?: return
        val body = JSONObject().put("ItemId", itemId).put("PlayMethod", if (directPlay) "DirectPlay" else "Transcode")
            .put("CanSeek", true).put("IsPaused", false).put("PositionTicks", 0)
        mediaSourceId?.let { body.put("MediaSourceId", it) }
        playSessionId?.let { body.put("PlaySessionId", it) }
        Http.post(account.serverUrl + "/Sessions/Playing", body.toString(),
            headers = mapOf("X-Emby-Token" to account.accessToken, "Accept" to "application/json"))
    }

    fun reportPlayback(context: Context, itemId: String, positionTicks: Long, paused: Boolean = false, playSessionId: String? = null) {
        val account = account(context) ?: return
        val body = JSONObject().put("ItemId", itemId).put("PositionTicks", positionTicks).put("IsPaused", paused)
        playSessionId?.let { body.put("PlaySessionId", it) }
        Http.post(account.serverUrl + "/Sessions/Playing/Progress", body.toString(),
            headers = mapOf("X-Emby-Token" to account.accessToken, "Accept" to "application/json"))
    }

    fun reportStopped(context: Context, itemId: String, positionTicks: Long, playSessionId: String? = null) {
        val account = account(context) ?: return
        val body = JSONObject().put("ItemId", itemId).put("PositionTicks", positionTicks)
        playSessionId?.let { body.put("PlaySessionId", it) }
        Http.post(account.serverUrl + "/Sessions/Playing/Stopped", body.toString(),
            headers = mapOf("X-Emby-Token" to account.accessToken, "Accept" to "application/json"))
    }
    fun imageUrl(context: Context, itemId: String, tag: String?, maxWidth: Int = 600): String? {
        val account = account(context) ?: return null
        if (tag.isNullOrBlank()) return null
        return account.serverUrl + "/Items/" + encode(itemId) + "/Images/Primary?tag=" + encode(tag) +
            "&maxWidth=" + maxWidth.coerceIn(100, 2000) + "&api_key=" + encode(account.accessToken)
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun JSONObject.toItem(): JellyfinItem? {
        val data = optJSONObject("UserData")
        val tags = optJSONArray("Taglines")
        val tagline = tags?.optString(0)?.takeIf { it.isNotBlank() }
        return JellyfinItem(
            id = optString("Id"), name = optString("Name"), type = optString("Type"),
            overview = optString("Overview"),
            imageTag = optJSONObject("ImageTags")?.optString("Primary")?.takeIf { it.isNotBlank() },
            year = optInt("ProductionYear").takeIf { it > 0 },
            runTimeTicks = optLong("RunTimeTicks").takeIf { it > 0 },
            played = data?.optBoolean("Played") == true,
            playbackPositionTicks = data?.optLong("PlaybackPositionTicks") ?: 0L,
            genres = optJSONArray("Genres").let { array ->
                if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            },
            tagline = tagline,
            communityRating = optDouble("CommunityRating").takeIf { it > 0.0 },
            officialRating = optString("OfficialRating").takeIf { it.isNotBlank() }
        ).takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    private fun JSONArray?.toItems(): List<JellyfinItem> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val data = item.optJSONObject("UserData")
            item.toItem()
        }
    }
}
