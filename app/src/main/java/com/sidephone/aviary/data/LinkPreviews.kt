package com.sidephone.aviary.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Link previews for message bubbles.
 *
 * A preview is fetched from the linked site itself, which means opening a thread reaches out to
 * whatever hosts were linked in it. That is a real departure from the rest of this app, where
 * nothing leaves the phone, so it is kept as narrow as possible: a preview is only fetched for a
 * message you are actually looking at, never in the background as messages arrive, and the result
 * is cached by URL so a link shared repeatedly is fetched once. A failed fetch is cached too,
 * so a dead link isn't retried on every scroll.
 */
object LinkPreviews {

    /**
     * URLs in message text. Deliberately strict about the scheme — matching bare "www." or
     * "example.com" would turn ordinary sentences (and every file path or version number) into
     * network requests.
     */
    private val URL_RE = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** The first link in [body], with trailing sentence punctuation trimmed off. */
    fun firstUrl(body: String): String? =
        URL_RE.find(body)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')
            ?.takeIf { it.length > 10 }

    /** True when the body is nothing but the link, so the bubble can drop the duplicate text. */
    fun isBareLink(body: String, url: String): Boolean = body.trim() == url

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** How much of a page to read. Metadata lives in <head>; pulling whole pages would be waste. */
    private const val MAX_BYTES = 256 * 1024

    data class Fetched(
        val title: String?,
        val description: String?,
        val imageUrl: String?,
        val siteName: String?,
    ) {
        /** Nothing worth showing a card for. */
        val isEmpty: Boolean get() = title.isNullOrBlank() && description.isNullOrBlank() && imageUrl.isNullOrBlank()
    }

    /**
     * Read a page's OpenGraph/Twitter-card metadata. Returns null when the request fails outright;
     * an empty [Fetched] means the page loaded but said nothing useful about itself.
     */
    fun fetch(url: String): Fetched? {
        return runCatching {
            val req = Request.Builder().url(url)
                // Ask as a browser would; many sites serve no card metadata to unknown agents.
                .header("User-Agent", "Mozilla/5.0 (compatible; Relay/1.0; +link-preview)")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val type = resp.header("Content-Type").orEmpty()
                if (!type.contains("html", true)) return null
                val body = resp.body ?: return null
                val html = body.source().let { src ->
                    src.request(MAX_BYTES.toLong())
                    src.buffer.snapshot(minOf(MAX_BYTES.toLong(), src.buffer.size).toInt()).utf8()
                }
                parseHtml(html, url)
            }
        }.onFailure { Log.i(TAG, "preview fetch failed for $url: ${it.message}") }.getOrNull()
    }

    /** Pull card metadata out of a page. Separated from the fetch so it can be tested directly. */
    fun parseHtml(html: String, pageUrl: String): Fetched {
        val head = html.substringBefore("</head>", html)
        return Fetched(
            title = meta(head, "og:title") ?: meta(head, "twitter:title") ?: titleTag(head),
            description = meta(head, "og:description") ?: meta(head, "twitter:description")
                ?: meta(head, "description"),
            imageUrl = (meta(head, "og:image") ?: meta(head, "twitter:image"))
                ?.let { absolute(it, pageUrl) },
            siteName = meta(head, "og:site_name") ?: hostOf(pageUrl),
        )
    }

    /**
     * A `<meta>` value by property or name. Written as a scan rather than one clever pattern
     * because the attribute order varies between sites and the quoting style does too.
     */
    private fun meta(head: String, key: String): String? {
        val re = Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["']${Regex.escape(key)}["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val tag = re.find(head)?.value ?: run {
            // Some pages put content= before property=; try the mirrored shape.
            Regex(
                """<meta[^>]*content\s*=\s*["'][^"']*["'][^>]*(?:property|name)\s*=\s*["']${Regex.escape(key)}["'][^>]*>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(head)?.value
        } ?: return null
        val content = Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1) ?: return null
        return unescape(content).trim().ifBlank { null }
    }

    private fun titleTag(head: String): String? =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(head)?.groupValues?.get(1)?.let { unescape(it).trim() }?.ifBlank { null }

    /** The handful of entities that actually show up in card metadata. */
    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&nbsp;", " ")

    /** Resolve a possibly-relative image URL against the page it came from. */
    private fun absolute(candidate: String, pageUrl: String): String? = runCatching {
        java.net.URI(pageUrl).resolve(candidate).toString()
    }.getOrNull()?.takeIf { it.startsWith("http", true) }

    fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()

    private const val TAG = "LinkPreviews"
}
