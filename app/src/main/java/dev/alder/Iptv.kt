package dev.alder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class IptvChannel(
    val name: String,
    val streamUrl: String,
    val group: String = "Other",
    val tvgId: String? = null,
    val logoUrl: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val catchup: String? = null
)

data class IptvPlaylist(val channels: List<IptvChannel>) {
    fun groups(): List<String> = channels.map { it.group }.distinct()
    fun inGroup(name: String): List<IptvChannel> = channels.filter { it.group == name }
}

object Iptv {
    private const val PREFS = "iptv"
    private const val CACHE_TTL_MS = 15 * 60_000L
    private data class CachedPlaylist(val playlist: IptvPlaylist, val loadedAt: Long)
    private val cache = ConcurrentHashMap<String, CachedPlaylist>()
    private val refreshing = ConcurrentHashMap<String, Boolean>()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(context: Context, name: String) = name + "@" + (Profiles.active(context)?.id ?: "none")

    fun isConfigured(context: Context): Boolean = when (prefs(context).getString(key(context, "playlistType"), "m3u")) {
        "xtream" -> xtreamServer(context).isNotBlank()
        "m3u_file" -> prefs(context).getString(key(context, "playlistFileUri"), null).orEmpty().isNotBlank()
        else -> playlistUrl(context).isNotBlank()
    }

    fun stableId(channel: IptvChannel): String = MessageDigest.getInstance("SHA-256")
        .digest(channel.streamUrl.toByteArray(Charsets.UTF_8))
        .take(10).joinToString("") { "%02x".format(it) }

    private fun cacheKey(context: Context): String {
        val p = prefs(context)
        val type = p.getString(key(context, "playlistType"), "m3u").orEmpty()
        val material = when (type) {
            "xtream" -> listOf(xtreamServer(context), p.getString(key(context, "xtreamUsername"), "").orEmpty(),
                p.getString(key(context, "xtreamPassword"), "").orEmpty().let { stored ->
                    SecureStorage.decrypt(stored) ?: stored
                }).joinToString("|")
            "m3u_file" -> p.getString(key(context, "playlistFileUri"), "").orEmpty()
            else -> playlistUrl(context)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return (Profiles.active(context)?.id ?: "none") + "|" + type + "|" + digest
    }

    fun configurationKey(context: Context): String = cacheKey(context)

    fun invalidateCache(context: Context) {
        val profileId = Profiles.active(context)?.id ?: "none"
        cache.keys.toList().filter { it.startsWith(profileId + "|") }.forEach(cache::remove)
    }

    fun playlistUrl(context: Context): String {
        if (prefs(context).getString(key(context, "playlistType"), "m3u") == "m3u_file") return ""
        val stored = prefs(context).getString(key(context, "playlistUrl"), null).orEmpty()
        SecureStorage.decrypt(stored)?.let { return it }
        if (stored.startsWith("http", true)) {
            runCatching { prefs(context).edit().putString(key(context, "playlistUrl"), SecureStorage.encrypt(stored)).apply() }
            return stored
        }
        return ""
    }
    fun xtreamServer(context: Context) = prefs(context).getString(key(context, "xtreamServer"), "").orEmpty()
    fun sourceSummary(context: Context): String {
        val p = prefs(context)
        return when (p.getString(key(context, "playlistType"), "m3u")) {
            "xtream" -> p.getString(key(context, "xtreamServer"), "").orEmpty()
            "m3u_file" -> p.getString(key(context, "playlistFileName"), "").orEmpty()
                .ifBlank { "Local M3U playlist" }
            else -> playlistUrl(context)
        }
    }

    fun setPlaylistUrl(context: Context, value: String) {
        prefs(context).edit().apply {
            val url = value.trim()
            if (url.isBlank()) remove(key(context, "playlistUrl")) else putString(key(context, "playlistUrl"), SecureStorage.encrypt(url))
            putString(key(context, "playlistType"), "m3u")
            remove(key(context, "xtreamServer"))
            remove(key(context, "xtreamUsername"))
            remove(key(context, "xtreamPassword"))
            remove(key(context, "playlistFileUri"))
            remove(key(context, "playlistFileName"))
        }.apply()
        invalidateCache(context)
    }

    fun setPlaylistFile(context: Context, uri: Uri) {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty().ifBlank { "Local M3U playlist" }
        prefs(context).edit().putString(key(context, "playlistType"), "m3u_file")
            .putString(key(context, "playlistFileUri"), uri.toString())
            .putString(key(context, "playlistFileName"), name)
            .remove(key(context, "playlistUrl"))
            .remove(key(context, "xtreamServer"))
            .remove(key(context, "xtreamUsername"))
            .remove(key(context, "xtreamPassword"))
            .apply()
        invalidateCache(context)
    }

    fun playlistPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
            "application/vnd.apple.mpegurl", "application/x-mpegURL", "audio/mpegurl",
            "audio/x-mpegurl", "application/octet-stream", "text/plain"
        ))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    fun setXtream(context: Context, server: String, username: String, password: String) {
        require(username.isNotBlank() && password.isNotBlank()) { "Enter your Xtream username and password." }
        prefs(context).edit().putString(key(context, "playlistType"), "xtream")
            .putString(key(context, "xtreamServer"), Jellyfin.normalizeServerUrl(server))
            .putString(key(context, "xtreamUsername"), username)
            .putString(key(context, "xtreamPassword"), SecureStorage.encrypt(password))
            .remove(key(context, "playlistFileUri")).remove(key(context, "playlistFileName"))
            .remove(key(context, "playlistUrl")).apply()
        invalidateCache(context)
    }

    fun forgetProfile(context: Context, profileId: String) {
        prefs(context).edit().apply {
            listOf("playlistUrl", "playlistType", "playlistFileUri", "playlistFileName", "xtreamServer", "xtreamUsername", "xtreamPassword")
                .forEach { remove(it + "@" + profileId) }
        }.apply()
        cache.keys.toList().filter { it.startsWith(profileId + "|") }.forEach(cache::remove)
    }

    fun parseM3u(content: String): IptvPlaylist {
        val channels = mutableListOf<IptvChannel>()
        var pending: Pending? = null
        var userAgent: String? = null
        var referrer: String? = null
        content.lineSequence().map { it.trim().removePrefix("\uFEFF") }.forEach { line ->
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:", true) -> pending = parseExtInf(line)
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val option = line.substringAfter(':')
                    when {
                        option.startsWith("http-user-agent=", true) -> userAgent = option.substringAfter('=')
                        option.startsWith("http-referrer=", true) -> referrer = option.substringAfter('=')
                    }
                }
                line.startsWith("#EXTHTTP:", true) -> {
                    val json = runCatching { JSONObject(line.substringAfter(':')) }.getOrNull()
                    userAgent = json?.optString("User-Agent")?.takeIf { it.isNotBlank() } ?: userAgent
                    referrer = json?.optString("Referer")?.takeIf { it.isNotBlank() } ?: referrer
                }
                line.startsWith("#") -> Unit
                pending != null -> {
                    val entry = pending!!
                    if (validStreamUrl(line)) channels += IptvChannel(
                        name = entry.name.ifBlank { "Channel " + (channels.size + 1) },
                        streamUrl = line,
                        group = entry.group.ifBlank { "Other" },
                        tvgId = entry.tvgId,
                        logoUrl = entry.logo,
                        userAgent = userAgent,
                        referrer = referrer,
                        catchup = entry.catchup
                    )
                    pending = null
                    userAgent = null
                    referrer = null
                }
            }
        }
        return IptvPlaylist(channels.distinctBy { it.streamUrl })
    }

    fun load(context: Context, forceRefresh: Boolean = false): IptvPlaylist {
        val app = context.applicationContext
        val cacheKey = cacheKey(app)
        val cached = cache[cacheKey]
        val now = android.os.SystemClock.elapsedRealtime()
        if (!forceRefresh && cached != null) {
            if (now - cached.loadedAt < CACHE_TTL_MS) return cached.playlist
            if (refreshing.putIfAbsent(cacheKey, true) == null) Screen.pool.execute {
                try {
                    val fresh = loadUncached(app)
                    cache[cacheKey] = CachedPlaylist(fresh, android.os.SystemClock.elapsedRealtime())
                } catch (_: Throwable) {
                    // Keep the last good channel list; a later visit can retry the refresh.
                } finally {
                    refreshing.remove(cacheKey)
                }
            }
            return cached.playlist
        }
        val fresh = loadUncached(app)
        cache[cacheKey] = CachedPlaylist(fresh, android.os.SystemClock.elapsedRealtime())
        return fresh
    }

    private fun loadUncached(context: Context): IptvPlaylist {
        val p = prefs(context)
        if (p.getString(key(context, "playlistType"), "m3u") == "xtream") {
            val server = p.getString(key(context, "xtreamServer"), "").orEmpty()
            val username = p.getString(key(context, "xtreamUsername"), "").orEmpty()
            val storedPassword = p.getString(key(context, "xtreamPassword"), null).orEmpty()
            val password = SecureStorage.decrypt(storedPassword) ?: storedPassword.takeIf { it.isNotBlank() }.orEmpty().also { legacy ->
                if (legacy.isNotBlank()) runCatching {
                    p.edit().putString(key(context, "xtreamPassword"), SecureStorage.encrypt(legacy)).apply()
                }
            }
            if (server.isBlank() || username.isBlank() || password.isBlank()) return IptvPlaylist(emptyList())
            return IptvPlaylist(parseXtream(server, username, password))
        }
        if (p.getString(key(context, "playlistType"), "m3u") == "m3u_file") {
            val rawUri = p.getString(key(context, "playlistFileUri"), null)
                ?: throw java.io.IOException("The selected playlist file is no longer available.")
            val content = context.contentResolver.openInputStream(Uri.parse(rawUri))
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw java.io.IOException("Could not open the selected playlist file.")
            return parseM3u(content)
        }
        val url = playlistUrl(context)
        if (url.isBlank()) return IptvPlaylist(emptyList())
        return parseM3u(Http.get(url, timeoutMs = 30_000, headers = mapOf("Accept" to "*/*")))
    }

    fun parseXtream(baseUrl: String, username: String, password: String, categoryId: String? = null): List<IptvChannel> {
        val base = Jellyfin.normalizeServerUrl(baseUrl)
        val credentials = "username=" + encode(username) + "&password=" + encode(password)
        val category = categoryId?.let { "&category_id=" + encode(it) }.orEmpty()
        val categories = runCatching {
            val values = JSONArray(Http.get(base + "/player_api.php?" + credentials + "&action=get_live_categories", timeoutMs = 30_000))
            (0 until values.length()).mapNotNull { index ->
                values.optJSONObject(index)?.let { it.optString("category_id") to it.optString("category_name") }
            }.toMap()
        }.getOrDefault(emptyMap())
        val array = JSONArray(Http.get(base + "/player_api.php?" + credentials + "&action=get_live_streams" + category, timeoutMs = 30_000))
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("stream_id")
            if (id.isBlank()) return@mapNotNull null
            IptvChannel(
                name = item.optString("name").ifBlank { "Channel " + id },
                streamUrl = base + "/live/" + encode(username) + "/" + encode(password) + "/" + id + ".ts",
                group = item.optString("category_name").ifBlank { categories[item.optString("category_id")].orEmpty().ifBlank { "Other" } },
                tvgId = item.optString("epg_channel_id").takeIf { it.isNotBlank() },
                logoUrl = item.optString("stream_icon").takeIf { it.startsWith("http", true) }
            )
        }
    }

    private data class Pending(val name: String, val group: String, val tvgId: String?, val logo: String?, val catchup: String?)

    private fun parseExtInf(line: String): Pending {
        val (metadata, displayName) = splitExtInf(line)
        val attrs = Regex("""([A-Za-z0-9_-]+)\s*=\s*("(?:[^"]*)"|'(?:[^']*)'|[^,\s]*)""")
            .findAll(metadata)
            .associate { match ->
                match.groupValues[1].lowercase(Locale.ROOT) to match.groupValues[2].removeSurrounding("\"").removeSurrounding("'")
            }
        return Pending(
            name = displayName.ifBlank { attrs["tvg-name"].orEmpty() },
            group = attrs["group-title"].orEmpty(),
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            logo = attrs["tvg-logo"]?.takeIf { validStreamUrl(it) },
            catchup = attrs["catchup"]?.takeIf { it.isNotBlank() }
        )
    }

    private fun splitExtInf(line: String): Pair<String, String> {
        val value = line.substringAfter("#EXTINF:")
        var quote: Char? = null
        for (index in value.indices) {
            val char = value[index]
            if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '"' || char == '\'') {
                quote = char
            } else if (char == ',') {
                return value.substring(0, index) to value.substring(index + 1).trim()
            }
        }
        return value to ""
    }

    private fun validStreamUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https", "rtmp", "rtsp", "udp") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}
