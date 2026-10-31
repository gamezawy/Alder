package dev.alder

import java.security.MessageDigest

/**
 * Authorization formats supported by Jellyfin.
 * Jellyfin 12 disables deprecated X-Emby headers by default; keep them for older servers.
 */
internal object JellyfinAuth {
    private const val LEGACY_DEVICE_ID = "alder-android-tv"
    fun loginModes(version: String?): List<Boolean> {
        val major = Regex("""^\s*(\d+)""").find(version.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when {
            major == null -> listOf(true, false)
            major >= 12 -> listOf(true)
            else -> listOf(false)
        }
    }

    fun usesModernAuthorization(version: String?): Boolean = loginModes(version).first()

    fun loginHeaders(username: String, modern: Boolean): Map<String, String> {
        // One token per device ID on Jellyfin. Keep each Alder profile's device ID stable but distinct.
        val deviceHash = MessageDigest.getInstance("SHA-256")
            .digest(username.trim().lowercase().toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
        val deviceId = if (modern) "alder-android-tv-$deviceHash" else LEGACY_DEVICE_ID
        val value = "MediaBrowser Client=\"Alder\", Device=\"Android TV\", DeviceId=\"$deviceId\", Version=\"1\""
        return if (modern) mapOf("Authorization" to value) else mapOf("X-Emby-Authorization" to value)
    }

    fun tokenHeaders(token: String, modern: Boolean): Map<String, String> =
        if (modern) mapOf("Authorization" to "MediaBrowser Token=\"$token\"")
        else mapOf("X-Emby-Token" to token)

    fun apiKeyParameter(modern: Boolean): String = if (modern) "ApiKey" else "api_key"
}
