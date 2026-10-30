package dev.alder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

data class JellyfinServer(val address: String, val name: String, val id: String)
data class JellyfinAccount(val serverUrl: String, val accessToken: String, val userId: String, val username: String, val useModernAuthorization: Boolean = true)
data class JellyfinItem(
    val id: String, val name: String, val type: String, val overview: String,
    val imageTag: String?, val year: Int?, val runTimeTicks: Long?,
    val played: Boolean, val playbackPositionTicks: Long,
    val genres: List<String> = emptyList(), val tagline: String? = null,
    val communityRating: Double? = null, val officialRating: String? = null,
    val seasonNumber: Int? = null, val episodeNumber: Int? = null,
    val seriesName: String? = null, val seriesId: String? = null
)
data class JellyfinSubtitle(val url: String, val language: String, val label: String, val mimeType: String, val isDefault: Boolean)
data class JellyfinPlayback(val itemId: String, val mediaSourceId: String?, val playSessionId: String?, val streamUrl: String, val directPlay: Boolean, val subtitles: List<JellyfinSubtitle> = emptyList())
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

    private data class CachedResponse(val body: String, val expiresAt: Long)
    private val responseCache = ConcurrentHashMap<String, CachedResponse>()
    private val refreshingKeys = ConcurrentHashMap.newKeySet<String>()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(context: Context, name: String) = name + "@" + (Profiles.active(context)?.id ?: "none")

    fun account(context: Context): JellyfinAccount? {
        val p = prefs(context)
        val server = p.getString(key(context, "server"), null) ?: return null
        val storedToken = p.getString(key(context, "token"), null).orEmpty()
        val token = SecureStorage.decrypt(storedToken) ?: storedToken.takeIf { it.isNotBlank() }?.also { legacy ->
            runCatching { p.edit().putString(key(context, "token"), SecureStorage.encrypt(legacy)).apply() }
        } ?: return null
        val userId = p.getString(key(context, "userId"), null) ?: return null
        return JellyfinAccount(server, token, userId, p.getString(key(context, "username"), "").orEmpty(),
            p.getBoolean(key(context, "modernAuthorization"), true))
    }

    fun serverUrl(context: Context) = prefs(context).getString(key(context, "server"), "").orEmpty()

    fun configure(context: Context, serverUrl: String) {
        prefs(context).edit().putString(key(context, "server"), normalizeServerUrl(serverUrl)).apply()
    }

    fun saveAccount(context: Context, account: JellyfinAccount) {
        invalidateCache(context)
        prefs(context).edit().putString(key(context, "server"), normalizeServerUrl(account.serverUrl))
            .putString(key(context, "token"), SecureStorage.encrypt(account.accessToken)).putString(key(context, "userId"), account.userId)
            .putString(key(context, "username"), account.username)
            .putBoolean(key(context, "modernAuthorization"), account.useModernAuthorization).apply()
    }

    fun disconnect(context: Context) {
        invalidateCache(context)
        prefs(context).edit().remove(key(context, "token")).remove(key(context, "userId"))
            .remove(key(context, "username")).remove(key(context, "modernAuthorization")).apply()
    }

    fun forgetProfile(context: Context, profileId: String) {
        responseCache.keys.removeIf { it.startsWith(profileId + "|") }
        prefs(context).edit().remove("server@$profileId").remove("token@$profileId")
            .remove("userId@$profileId").remove("username@$profileId").remove("modernAuthorization@$profileId").apply()
    }

    fun normalizeServerUrl(value: String): String {
        val raw = value.trim().trimEnd('/')
        require(raw.isNotEmpty()) { "Enter a Jellyfin server address." }
        val uri = java.net.URI(if ("://" in raw) raw else "http://$raw")
        val scheme = uri.scheme?.lowercase()
            ?: throw IllegalArgumentException("Use a valid HTTP or HTTPS server address.")
        require(scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "Use a valid HTTP or HTTPS server address."
        }
        require(uri.userInfo == null) { "The server address must not contain credentials." }
        // Users often paste Jellyfin's browser URL (/web/#/home). API requests must target the server root.
        return java.net.URI(scheme, null, uri.host, uri.port, null, null, null)
            .toASCIIString().trimEnd('/')
    }

    fun discoverServers(timeoutMs: Long = 3_500): List<JellyfinServer> {
        val found = linkedMapOf<String, JellyfinServer>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 300
            val message = "Who is JellyfinServer?".toByteArray(Charsets.UTF_8)
            val broadcasts = linkedSetOf(InetAddress.getByName("255.255.255.255"))
            runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val network = interfaces.nextElement()
                    if (!network.isUp || network.isLoopback) continue
                    network.interfaceAddresses.mapNotNullTo(broadcasts) { it.broadcast }
                }
            }
            broadcasts.forEach { address ->
                runCatching { socket.send(DatagramPacket(message, message.size, address, 7359)) }
            }
            val deadline = System.nanoTime() + timeoutMs.coerceIn(500, 10_000) * 1_000_000
            while (System.nanoTime() < deadline) {
                val buffer = ByteArray(8_192)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val payload = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val server = runCatching {
                    val json = JSONObject(payload)
                    val address = json.optString("Address").ifBlank { "http://${packet.address.hostAddress}:8096" }
                    JellyfinServer(normalizeServerUrl(address),
                        json.optString("Name").ifBlank { "Jellyfin" }, json.optString("Id"))
                }.getOrNull() ?: continue
                found.putIfAbsent(server.address, server)
            }
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    fun authenticate(serverUrl: String, username: String, password: String): JellyfinAccount {
        val server = normalizeServerUrl(serverUrl)
        require(username.isNotBlank()) { "Enter your Jellyfin username." }
        require(password.isNotEmpty()) { "Enter your Jellyfin password." }
        // Jellyfin 12 disables deprecated X-Emby authorization. Detect the server version first;
        // if a proxy hides public info, the standard Authorization scheme remains supported by older servers.
        val serverVersion = runCatching {
            JSONObject(Http.get("$server/System/Info/Public", timeoutMs = 10_000)).optString("Version")
        }.getOrNull()
        val useModernAuthorization = JellyfinAuth.usesModernAuthorization(serverVersion)
        val body = JSONObject().put("Username", username).put("Pw", password).toString()
        val answer = try {
            Http.post("$server/Users/AuthenticateByName", body, timeoutMs = 20_000,
                headers = JellyfinAuth.loginHeaders(username, useModernAuthorization) +
                    ("Accept" to "application/json"), strict = true)
        } catch (e: HttpError) {
            val detail = runCatching { JSONObject(e.responseBody.orEmpty()).optString("Message") }.getOrNull()
                .orEmpty().ifBlank { e.responseBody.orEmpty().take(300) }
            throw JellyfinException("Jellyfin sign-in failed (HTTP ${e.code})" +
                if (detail.isBlank()) "." else ": $detail", e.code)
        }
        val json = runCatching { JSONObject(answer) }.getOrElse { throw JellyfinException("The server returned an invalid sign-in response.") }
        val token = json.optString("AccessToken")
        val user = json.optJSONObject("User")
        val userId = user?.optString("Id").orEmpty()
        if (token.isBlank() || userId.isBlank()) throw JellyfinException("Jellyfin did not return an access token and user ID.")
        return JellyfinAccount(server, token, userId, user?.optString("Name").orEmpty().ifBlank { username }, useModernAuthorization)
    }

    fun request(context: Context, path: String, query: Map<String, String> = emptyMap()): String {
        val account = account(context) ?: throw JellyfinException("Jellyfin is not connected.")
        require(path.startsWith("/") && !path.startsWith("//")) { "Invalid Jellyfin API path." }
        val suffix = if (query.isEmpty()) "" else query.entries.joinToString("&", prefix = "?") {
            encode(it.key) + "=" + encode(it.value)
        }
        return Http.get(account.serverUrl + path + suffix, timeoutMs = 20_000,
            headers = JellyfinAuth.tokenHeaders(account.accessToken, account.useModernAuthorization) +
                ("Accept" to "application/json"))
    }

    /**
     * Short-lived response cache for read-only library calls. It lets Home render immediately when the viewer
     * returns from details or playback, while the next expiry quietly refreshes the data in the background.
     */
    private fun cachedRequest(context: Context, path: String, query: Map<String, String> = emptyMap(), ttlMs: Long): String {
        val account = account(context) ?: throw JellyfinException("Jellyfin is not connected.")
        val profileId = Profiles.active(context)?.id ?: "none"
        val queryKey = query.toSortedMap().entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
        val key = profileId + "|" + account.serverUrl + "|" + account.userId + "|" + path + "|" + queryKey
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = responseCache[key]
        if (cached != null) {
            if (cached.expiresAt > now) return cached.body
            // Stale-while-revalidate: return the last good shelf immediately and refresh quietly.
            if (refreshingKeys.add(key)) Screen.pool.execute {
                try {
                    val fresh = request(context.applicationContext, path, query)
                    responseCache[key] = CachedResponse(fresh, android.os.SystemClock.elapsedRealtime() + ttlMs.coerceAtLeast(1_000))
                } catch (_: Throwable) {
                    // Keep the stale copy; a transient server hiccup must not blank the Home screen.
                } finally {
                    refreshingKeys.remove(key)
                }
            }
            return cached.body
        }
        val body = request(context, path, query)
        responseCache[key] = CachedResponse(body, now + ttlMs.coerceAtLeast(1_000))
        if (responseCache.size > 256) responseCache.entries.removeIf { it.value.expiresAt <= now }
        return body
    }

    /** Invalidate this profile's read cache after a playback session changes its resume state. */
    fun invalidateCache(context: Context) {
        val profileId = Profiles.active(context)?.id ?: "none"
        responseCache.keys.removeIf { it.startsWith(profileId + "|") }
    }

    fun items(context: Context, parentId: String? = null, includeTypes: List<String> = listOf("Movie", "Series"),
              limit: Int = 100, startIndex: Int = 0): List<JellyfinItem> {
        val account = account(context) ?: return emptyList()
        val query = linkedMapOf("UserId" to account.userId, "Recursive" to "true",
            "IncludeItemTypes" to includeTypes.joinToString(","), "SortBy" to "DateCreated",
            "SortOrder" to "Descending", "Fields" to "Overview,DateCreated,RunTimeTicks,UserData,ProductionYear,ImageTags",
            "Limit" to limit.coerceIn(1, 200).toString(), "StartIndex" to startIndex.coerceAtLeast(0).toString())
        if (!parentId.isNullOrBlank()) query["ParentId"] = parentId
        return JSONObject(cachedRequest(context, "/Users/" + encode(account.userId) + "/Items", query, 30_000L))
            .optJSONArray("Items").toItems()
    }

    fun item(context: Context, itemId: String): JellyfinItem {
        val account = account(context) ?: throw JellyfinException("Jellyfin is not connected.")
        val response = JSONObject(cachedRequest(context, "/Users/" + encode(account.userId) + "/Items/" + encode(itemId),
            mapOf("Fields" to "Overview,Genres,People,RunTimeTicks,UserData,ProductionYear,ImageTags,Taglines,CommunityRating,OfficialRating,IndexNumber,ParentIndexNumber,SeriesName,SeriesId"), 120_000L))
        return response.toItem() ?: throw JellyfinException("Jellyfin returned an invalid item.")
    }

    fun search(context: Context, query: String, limit: Int = 30): List<JellyfinItem> {
        val account = account(context) ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val response = JSONObject(cachedRequest(context, "/Users/" + encode(account.userId) + "/Items",
            mapOf("UserId" to account.userId, "SearchTerm" to query, "Recursive" to "true",
                "IncludeItemTypes" to "Movie,Series,Episode", "Limit" to limit.coerceIn(1, 100).toString(),
                "Fields" to "Overview,RunTimeTicks,UserData,ProductionYear,ImageTags,IndexNumber,ParentIndexNumber,SeriesName,SeriesId"), 15_000L))
        return response.optJSONArray("Items").toItems()
    }

    fun views(context: Context): List<JellyfinItem> {
        val account = account(context) ?: return emptyList()
        return JSONObject(cachedRequest(context, "/Users/" + encode(account.userId) + "/Views", ttlMs = 300_000L))
            .optJSONArray("Items").toItems()
    }

    fun resumeItems(context: Context, limit: Int = 20): List<JellyfinItem> {
        val account = account(context) ?: return emptyList()
        return JSONObject(cachedRequest(context, "/Users/" + encode(account.userId) + "/Items/Resume",
            mapOf("UserId" to account.userId, "Limit" to limit.coerceIn(1, 100).toString(),
                "Fields" to "Overview,RunTimeTicks,UserData,ProductionYear,ImageTags,IndexNumber,ParentIndexNumber,SeriesName,SeriesId"), 20_000L))
            .optJSONArray("Items").toItems()
    }

    fun itemsByIds(context: Context, ids: Set<String>): List<JellyfinItem> {
        val account = account(context) ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        return ids.toList().chunked(50).flatMap { batch ->
            val response = JSONObject(cachedRequest(context, "/Users/" + encode(account.userId) + "/Items",
                mapOf("Ids" to batch.joinToString(","), "Fields" to "Overview,RunTimeTicks,UserData,ProductionYear,ImageTags,IndexNumber,ParentIndexNumber,SeriesName,SeriesId"), 30_000L))
            response.optJSONArray("Items").toItems()
        }
    }

    fun latestItems(context: Context, parentId: String, limit: Int = 20): List<JellyfinItem> {
        val account = account(context) ?: return emptyList()
        return JSONArray(cachedRequest(context, "/Users/" + encode(account.userId) + "/Items/Latest",
            mapOf("ParentId" to parentId, "Limit" to limit.coerceIn(1, 100).toString(),
                "Fields" to "Overview,RunTimeTicks,UserData,ProductionYear,ImageTags,IndexNumber,ParentIndexNumber,SeriesName,SeriesId"), 60_000L)).toItems()
    }

    /** All episodes in a series, ordered by season and episode number for a normal TV browsing flow. */
    fun episodes(context: Context, seriesId: String, limit: Int = 500): List<JellyfinItem> {
        val account = account(context) ?: return emptyList()
        val query = mapOf("UserId" to account.userId,
            "Fields" to "Overview,RunTimeTicks,UserData,ProductionYear,ImageTags,IndexNumber,ParentIndexNumber,SeriesName,SeriesId",
            "SortBy" to "ParentIndexNumber,IndexNumber", "SortOrder" to "Ascending",
            "Limit" to limit.coerceIn(1, 1_000).toString())
        return JSONObject(cachedRequest(context, "/Shows/" + encode(seriesId) + "/Episodes", query, 60_000L))
            .optJSONArray("Items").toItems()
    }

    fun playback(context: Context, itemId: String): JellyfinPlayback {
        val account = account(context) ?: throw JellyfinException("Jellyfin is not connected.")
        val body = JSONObject().put("UserId", account.userId).toString()
        val response = JSONObject(Http.post(account.serverUrl + "/Items/" + encode(itemId) + "/PlaybackInfo",
            body, headers = JellyfinAuth.tokenHeaders(account.accessToken, account.useModernAuthorization) +
                ("Accept" to "application/json")))
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
            withStreamAuthorization(url, account)
        } else buildString {
            append(account.serverUrl).append("/Videos/").append(encode(itemId)).append("/stream?Static=").append(direct)
            if (sourceId != null) append("&MediaSourceId=").append(encode(sourceId))
            if (session != null) append("&PlaySessionId=").append(encode(session))
            if (!account.useModernAuthorization) append("&api_key=").append(encode(account.accessToken))
        }
        val subtitles = source.optJSONArray("MediaStreams")?.let { streams ->
            (0 until streams.length()).mapNotNull { index ->
                val track = streams.optJSONObject(index) ?: return@mapNotNull null
                if (!track.optString("Type").equals("Subtitle", true)) return@mapNotNull null
                val delivery = track.optString("DeliveryUrl").takeIf { it.isNotBlank() }
                if (!track.optBoolean("IsExternal") && delivery == null) return@mapNotNull null
                val trackIndex = track.optInt("Index", -1)
                if (trackIndex < 0) return@mapNotNull null
                val path = delivery ?: run {
                    if (sourceId == null) return@mapNotNull null
                    "/Videos/" + encode(itemId) + "/" + encode(sourceId) + "/Subtitles/" + trackIndex + "/Stream.vtt"
                }
                val full = if (path.startsWith("http", true)) path else account.serverUrl + "/" + path.trimStart('/')
                val url = withStreamAuthorization(full, account)
                val extension = path.substringBefore('?').substringAfterLast('.', "vtt").lowercase()
                val mime = when (extension) {
                    "srt", "subrip" -> "application/x-subrip"
                    "ass", "ssa" -> "text/x-ssa"
                    "ttml", "xml" -> "application/ttml+xml"
                    else -> "text/vtt"
                }
                val language = track.optString("Language").takeIf { it.isNotBlank() && it != "und" } ?: "und"
                val label = track.optString("DisplayTitle").takeIf { it.isNotBlank() }
                    ?: track.optString("Title").takeIf { it.isNotBlank() }
                    ?: track.optString("Language").takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.track_number, trackIndex + 1)
                JellyfinSubtitle(url, language, label, mime, track.optBoolean("IsDefault"))
            }
        }.orEmpty()
        return JellyfinPlayback(itemId, sourceId, session, stream, direct, subtitles)
    }

    fun reportStarted(context: Context, itemId: String, mediaSourceId: String?, playSessionId: String?, directPlay: Boolean, positionTicks: Long = 0L) {
        val account = account(context) ?: return
        val body = JSONObject().put("ItemId", itemId).put("PlayMethod", if (directPlay) "DirectPlay" else "Transcode")
            .put("CanSeek", true).put("IsPaused", false).put("PositionTicks", positionTicks.coerceAtLeast(0L))
        mediaSourceId?.let { body.put("MediaSourceId", it) }
        playSessionId?.let { body.put("PlaySessionId", it) }
        Http.post(account.serverUrl + "/Sessions/Playing", body.toString(),
            headers = JellyfinAuth.tokenHeaders(account.accessToken, account.useModernAuthorization) +
                ("Accept" to "application/json"))
    }

    fun reportPlayback(context: Context, itemId: String, positionTicks: Long, paused: Boolean = false, playSessionId: String? = null) {
        val account = account(context) ?: return
        val body = JSONObject().put("ItemId", itemId).put("PositionTicks", positionTicks).put("IsPaused", paused)
        playSessionId?.let { body.put("PlaySessionId", it) }
        Http.post(account.serverUrl + "/Sessions/Playing/Progress", body.toString(),
            headers = JellyfinAuth.tokenHeaders(account.accessToken, account.useModernAuthorization) +
                ("Accept" to "application/json"))
    }

    fun reportStopped(context: Context, itemId: String, positionTicks: Long, playSessionId: String? = null) {
        val account = account(context) ?: return
        val body = JSONObject().put("ItemId", itemId).put("PositionTicks", positionTicks)
        playSessionId?.let { body.put("PlaySessionId", it) }
        Http.post(account.serverUrl + "/Sessions/Playing/Stopped", body.toString(),
            headers = JellyfinAuth.tokenHeaders(account.accessToken, account.useModernAuthorization) +
                ("Accept" to "application/json"))
    }
    fun imageUrl(context: Context, itemId: String, tag: String?, maxWidth: Int = 600): String? {
        val account = account(context) ?: return null
        if (tag.isNullOrBlank()) return null
        return account.serverUrl + "/Items/" + encode(itemId) + "/Images/Primary?tag=" + encode(tag) +
            "&maxWidth=" + maxWidth.coerceIn(100, 2000) + "&" + JellyfinAuth.apiKeyParameter(account.useModernAuthorization) + "=" + encode(account.accessToken)
    }

    fun backdropUrl(context: Context, itemId: String, maxWidth: Int = 1600): String? {
        val account = account(context) ?: return null
        return account.serverUrl + "/Items/" + encode(itemId) + "/Images/Backdrop?maxWidth=" +
            maxWidth.coerceIn(400, 2400) + "&" + JellyfinAuth.apiKeyParameter(account.useModernAuthorization) +
            "=" + encode(account.accessToken)
    }

    /** Authorization for Media3 HTTP requests; Jellyfin 12 uses the standard header, not a URL token. */
    fun authorizationHeaders(context: Context): Map<String, String> {
        val account = account(context) ?: return emptyMap()
        // Legacy playback already carries api_key in its URL; don't send the same token twice.
        if (!account.useModernAuthorization) return emptyMap()
        return JellyfinAuth.tokenHeaders(account.accessToken, modern = true)
    }

    private fun withStreamAuthorization(url: String, account: JellyfinAccount): String {
        val fragmentAt = url.indexOf('#')
        val fragment = if (fragmentAt >= 0) url.substring(fragmentAt) else ""
        val withoutFragment = if (fragmentAt >= 0) url.substring(0, fragmentAt) else url
        val base = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', "")
        val retained = query.split('&').filter { part ->
            part.isNotEmpty() && part.substringBefore('=').lowercase() !in setOf("api_key", "apikey")
        }
        val cleanUrl = base + if (retained.isEmpty()) "" else "?" + retained.joinToString("&")
        if (account.useModernAuthorization) return cleanUrl + fragment
        val separator = if ('?' in cleanUrl) "&" else "?"
        return cleanUrl + separator + "api_key=" + encode(account.accessToken) + fragment
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
            officialRating = optString("OfficialRating").takeIf { it.isNotBlank() },
            seasonNumber = optInt("ParentIndexNumber").takeIf { it > 0 },
            episodeNumber = optInt("IndexNumber").takeIf { it > 0 },
            seriesName = optString("SeriesName").takeIf { it.isNotBlank() },
            seriesId = optString("SeriesId").takeIf { it.isNotBlank() }
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
