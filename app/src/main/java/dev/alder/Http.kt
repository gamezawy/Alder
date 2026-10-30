package dev.alder

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Plain HTTP for Stremio's API, addons and images. Every call blocks, so run them off the main thread. */
object Http {
    /**
     * Answers kept on disk (title details, catalogs, the library, the addon list), so a screen can draw from last time
     * at once and refresh behind it, as Netflix and NuvioTV do. Set once at start; null keeps nothing.
     */
    var saved: File? = null
        set(dir) {
            field = dir
            dir?.mkdirs()
            trim()
        }

    /** Set once at start with the app's version. */
    var userAgent = "Alder"

    /** [strict]: an error answer throws [HttpError] even when its body is JSON (add-ons, not Stremio's API, read so). */
    fun get(url: String, keep: Boolean = false, timeoutMs: Int = 20_000, strict: Boolean = false, headers: Map<String, String> = emptyMap()): String =
        text(url, null, keep, timeoutMs, strict, headers)

    fun post(url: String, json: String, keep: Boolean = false, timeoutMs: Int = 20_000, headers: Map<String, String> = emptyMap()): String =
        text(url, json, keep, timeoutMs, false, headers)

    fun bytes(url: String): ByteArray = request(url, null, cache = true).second

    private fun text(url: String, json: String?, keep: Boolean, timeoutMs: Int = 20_000, strict: Boolean = false, headers: Map<String, String> = emptyMap()): String {
        val (code, body) = request(url, json, cache = false, timeoutMs, headers)
        val text = String(body, Charsets.UTF_8)
        // Stremio's API answers errors as JSON (read by the caller); anything else failed is an error page: say the code.
        if (code >= 400 && (strict || !text.trimStart().startsWith("{"))) throw HttpError(code, text.take(1000))
        return text.also { if (keep && code < 400) keep(url, json, it) }
    }

    /** The last answer kept for this request, or null. Never touches the network. */
    fun savedAnswer(url: String, json: String? = null): String? =
        file(url, json)?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    /** Keeps [body] as the answer to this request, for [savedAnswer] next time. */
    fun keep(url: String, json: String?, body: String) {
        val f = file(url, json) ?: return
        runCatching { File(f.path + ".tmp").apply { writeText(body) }.renameTo(f) }
    }

    /**
     * Forgets every kept answer. Used when a profile is removed: its library, addon list and personal addons' answers
     * (their URLs carry tokens) aren't told apart by name, so all go, and the other profiles fetch theirs again once.
     */
    fun forget() {
        saved?.listFiles()?.forEach { it.delete() }
    }

    // Named by a hash of the request: urls carry addon settings and the body an auth key, so neither is a file name.
    private fun file(url: String, json: String?): File? {
        val dir = saved ?: return null
        val hash = MessageDigest.getInstance("SHA-1").digest((url + "\n" + (json ?: "")).toByteArray())
        return File(dir, hash.joinToString("") { "%02x".format(it) })
    }

    // ponytail: one pass at start, oldest first, down to 60 MB; an index would only matter for far bigger caches.
    private fun trim() {
        val files = saved?.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= 60L * 1024 * 1024) break
            total -= f.length()
            f.delete()
        }
    }

    // No disconnect(): a response read to the end and closed hands its connection back for the next request to the same
    // host. Disconnecting threw it away, so every picture paid a fresh TCP and TLS handshake first.
    // [cache]: pictures only. The response cache kept an addon's 404 and, revalidated with a 304, served it forever
    // (AIOMetadata's movie search); answers have their own copy in [saved].
    private fun request(url: String, json: String?, cache: Boolean, timeoutMs: Int = 20_000, headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.useCaches = cache
        // Not Android's "Dalvik/2.1.0": some add-ons behind Cloudflare answer that with a challenge page.
        c.setRequestProperty("User-Agent", userAgent)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.connectTimeout = minOf(10_000, timeoutMs)
        c.readTimeout = timeoutMs
        if (json != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray()) }
        }
        // Stremio's API puts its JSON error in the body of a failed response too, so read it either way.
        val code = c.responseCode
        val stream = (if (code < 400) c.inputStream else c.errorStream) ?: throw HttpError(code)
        return code to stream.use { it.readBytes() }
    }
}

/** A failed answer, with its status code: 401 and 403 from an add-on usually mean an expired key or account. */
class HttpError(val code: Int, val responseBody: String? = null) : IOException(
    "HTTP $code" + responseBody.orEmpty().takeIf { it.isNotBlank() }?.let { ": ${it.take(300)}" }.orEmpty()
)
