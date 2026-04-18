package com.turbomesh.app.agent

import com.turbomesh.app.data.model.Article
import com.turbomesh.app.data.model.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * ArticleScannerAgent
 *
 * An intelligent agent that scans five authoritative BLE / Bluetooth Mesh websites to collect
 * articles, news, and advancement information.  For each source it:
 *   1. Downloads the page HTML via OkHttp.
 *   2. Parses candidate article links with Jsoup.
 *   3. Fetches each article page and extracts a concise summary.
 *   4. Returns the aggregated results as [ScanResult] objects.
 *
 * Scanning is performed on [Dispatchers.IO] and is therefore safe to call from a coroutine scope.
 */
class ArticleScannerAgent {

    // ---------------------------------------------------------------------------
    // Configuration – five BLE Mesh authoritative sources
    // ---------------------------------------------------------------------------

    /** Descriptor for a website the agent knows how to scrape. */
    data class WebSource(
        /** Human-readable label shown in the UI. */
        val name: String,
        /** URL of the listing / index page to scan for article links. */
        val listingUrl: String,
        /** CSS selector that matches individual article link elements on the listing page. */
        val articleLinkSelector: String,
        /** CSS selector for the main article body text on article pages. */
        val bodySelector: String,
        /** CSS selector for the article published-date element, or empty if unavailable. */
        val dateSelector: String = "",
        /** Maximum number of articles to fetch per scan to keep runtime reasonable. */
        val maxArticles: Int = 8
    )

    private val sources: List<WebSource> = listOf(
        WebSource(
            name = "Bluetooth SIG",
            listingUrl = "https://www.bluetooth.com/blog/",
            articleLinkSelector = "h2.entry-title a, h3.entry-title a, article a.entry-title",
            bodySelector = "div.entry-content, article .post-content",
            dateSelector = "time.entry-date"
        ),
        WebSource(
            name = "Nordic Semiconductor Blog",
            listingUrl = "https://blog.nordicsemi.com/",
            articleLinkSelector = "h2.blog-listing__post-title a, h3.blog-listing__post-title a, .post-listing a.post-title",
            bodySelector = "div.blog-post__body, div.post-body, article .entry-content",
            dateSelector = ".blog-post__date, .post-date"
        ),
        WebSource(
            name = "Silicon Labs BLE",
            listingUrl = "https://www.silabs.com/wireless/bluetooth/resources",
            articleLinkSelector = "a.resource-card__link, .resource-list a[href*='/blog'], .resource-item a",
            bodySelector = "div.blog-detail__body, .article-body, main article p",
            dateSelector = ".published-date, time"
        ),
        WebSource(
            name = "Hackster.io BLE Mesh",
            listingUrl = "https://www.hackster.io/search?q=ble+mesh&i=projects",
            articleLinkSelector = "a.card-title, h2.title a, .project-card a[href*='/']",
            bodySelector = "div.description, .project-description, article p",
            dateSelector = "time, .date-published"
        ),
        WebSource(
            name = "Embedded.com Wireless",
            listingUrl = "https://www.embedded.com/category/wireless/",
            articleLinkSelector = "h2.entry-title a, h3.article-title a, .article-list a.headline",
            bodySelector = "div.entry-content, .article-body, article .content",
            dateSelector = "time.entry-date, .published"
        )
    )

    // ---------------------------------------------------------------------------
    // HTTP client
    // ---------------------------------------------------------------------------

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            // Mimic a real browser User-Agent so sites don't block the agent.
            val request = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/$CHROME_VERSION Mobile Safari/537.36"
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            chain.proceed(request)
        }
        .build()

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Scans all five configured BLE mesh websites and returns one [ScanResult] per source.
     * Safe to call from any coroutine – work is dispatched to [Dispatchers.IO].
     */
    suspend fun scanAll(): List<ScanResult> = coroutineScope {
        sources.map { source -> async(Dispatchers.IO) { scanSource(source) } }.awaitAll()
    }

    /**
     * Scans a single source by [name]. Returns null if the name is not recognised.
     */
    suspend fun scanSource(name: String): ScanResult? = withContext(Dispatchers.IO) {
        sources.firstOrNull { it.name == name }?.let { scanSource(it) }
    }

    // ---------------------------------------------------------------------------
    // Internal scanning logic
    // ---------------------------------------------------------------------------

    private suspend fun scanSource(source: WebSource): ScanResult {
        return try {
            val listingHtml = fetchHtml(source.listingUrl)
                ?: return ScanResult.Error(source.name, "Failed to fetch listing page")

            val listingDoc = Jsoup.parse(listingHtml, source.listingUrl)
            val articleLinks = extractArticleLinks(listingDoc, source)

            val semaphore = Semaphore(MAX_CONCURRENT_ARTICLES)
            val articles = coroutineScope {
                articleLinks
                    .take(source.maxArticles)
                    .map { url ->
                        async(Dispatchers.IO) { semaphore.withPermit { fetchArticle(url, source) } }
                    }
                    .awaitAll()
                    .filterNotNull()
            }

            ScanResult.Success(source.name, articles)
        } catch (e: IOException) {
            ScanResult.Error(source.name, "Network error: ${e.message}", e)
        } catch (e: Exception) {
            ScanResult.Error(source.name, "Unexpected error: ${e.message}", e)
        }
    }

    /**
     * Returns a deduplicated list of absolute article URLs from a listing page.
     * Only same-host links that contain BLE / mesh relevant keywords in their href or
     * visible text are retained, to avoid pulling unrelated or off-site content.
     */
    private fun extractArticleLinks(doc: Document, source: WebSource): List<String> {
        val links = mutableSetOf<String>()
        val sourceDomain = extractDomain(source.listingUrl)

        // Primary selector – restrict to same host
        doc.select(source.articleLinkSelector).forEach { el ->
            val href = el.absUrl("href")
            if (href.isNotBlank() &&
                extractDomain(href) == sourceDomain &&
                isRelevantLink(href, el.text())
            ) {
                links.add(href)
            }
        }

        // Fallback: if primary selector found nothing, try generic article/a scanning
        if (links.isEmpty()) {
            doc.select("a[href]").forEach { el ->
                val href = el.absUrl("href")
                if (extractDomain(href) == sourceDomain && isRelevantLink(href, el.text())) {
                    links.add(href)
                }
            }
        }

        return links.toList()
    }

    /**
     * Returns true when the URL is likely an individual article page containing BLE mesh topics.
     * Rejects navigation pages (tag/category/search/login/privacy) and file downloads.
     * Requires a BLE keyword match – the overly-permissive "any long link" fallback is removed.
     */
    private fun isRelevantLink(href: String, text: String): Boolean {
        val path = try {
            URI(href).path.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            href.lowercase(Locale.ROOT)
        }

        // Reject non-article page types
        val rejectedSegments = listOf(
            "/tag/", "/tags/", "/category/", "/categories/", "/page/",
            "/search", "/author/", "/login", "/signin", "/signup",
            "/privacy", "/terms", "/cookie", "/legal", "/about",
            "/contact", "/feed", "/rss", "/sitemap", "/wp-admin"
        )
        if (rejectedSegments.any { path.contains(it) }) return false

        // Reject file downloads
        val rejectedExtensions = listOf(
            ".pdf", ".png", ".jpg", ".jpeg", ".gif", ".zip", ".svg", ".mp4", ".mp3"
        )
        if (rejectedExtensions.any { path.endsWith(it) }) return false

        // Accept only links where href or visible text matches a BLE/mesh keyword
        val combined = (href + " " + text).lowercase(Locale.ROOT)
        return BLE_KEYWORDS.any { keyword -> combined.contains(keyword) }
    }

    /**
     * Fetches an article page and extracts its title, summary, date, and tags.
     * Prefers the canonical URL (`<link rel="canonical">`) for deduplication and uses the
     * normalized (tracking-param-stripped) URL for both [Article.url] and [Article.id].
     */
    private suspend fun fetchArticle(url: String, source: WebSource): Article? {
        return try {
            val html = fetchHtml(url) ?: return null
            val doc = Jsoup.parse(html, url)

            // Prefer canonical URL to avoid duplicates; fall back to the requested URL
            val canonical = doc.select("link[rel=canonical]").attr("abs:href").trim()
            val articleUrl = normalizeUrl(if (canonical.isNotBlank()) canonical else url)

            val title = extractTitle(doc)
            if (title.isBlank()) return null

            val summary = extractSummary(doc, source.bodySelector)
            val date = extractDate(doc, source.dateSelector)
            val tags = extractTags(doc)

            Article(
                id = sha256(articleUrl),
                title = title,
                summary = summary,
                url = articleUrl,
                sourceName = source.name,
                sourceUrl = source.listingUrl,
                publishedAt = date,
                tags = tags
            )
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Extraction helpers
    // ---------------------------------------------------------------------------

    private fun extractTitle(doc: Document): String {
        // Try OG title first, then <title>, then first <h1>
        return doc.select("meta[property=og:title]").attr("content").trim()
            .ifBlank { doc.select("title").text().trim() }
            .ifBlank { doc.select("h1").firstOrNull()?.text()?.trim() ?: "" }
            .let { cleanTitle(it) }
    }

    private fun extractSummary(doc: Document, bodySelector: String): String {
        // 1. Use OG / meta description if present
        val metaDesc = doc.select("meta[name=description], meta[property=og:description]")
            .attr("content").trim()
        if (metaDesc.isNotBlank()) return metaDesc.take(MAX_SUMMARY_CHARS)

        // 2. Extract first meaningful paragraph from the body selector
        val bodyEl = doc.select(bodySelector).firstOrNull()
            ?: doc.select("article, main, .content").firstOrNull()
            ?: doc.body()

        val paragraphs = bodyEl.select("p")
            .map { it.text().trim() }
            .filter { it.length > 40 }

        return paragraphs.take(3).joinToString(" ").take(MAX_SUMMARY_CHARS)
    }

    private fun extractDate(doc: Document, dateSelector: String): String {
        if (dateSelector.isNotBlank()) {
            val el = doc.select(dateSelector).firstOrNull()
            if (el != null) {
                return el.attr("datetime").ifBlank { el.text() }.trim()
            }
        }
        // Fallback: common date meta tags
        return doc.select("meta[property=article:published_time], meta[name=date]")
            .attr("content").trim()
    }

    private fun extractTags(doc: Document): List<String> {
        val keywords = doc.select("meta[name=keywords]").attr("content")
        if (keywords.isNotBlank()) {
            return keywords.split(",").map { it.trim() }.filter { it.isNotBlank() }.take(6)
        }
        // Article tags / categories
        return doc.select(".tags a, .tag a, .category a, a[rel=tag]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .take(6)
    }

    private fun cleanTitle(raw: String): String {
        // Remove common site name suffixes like " | Site Name" or " - Site Name"
        return raw.replace(Regex("""\s*[|\-–—]\s*[^|\-–—]+$"""), "").trim()
    }

    // ---------------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------------

    /**
     * Fetches the HTML at [url] with automatic retry and exponential backoff.
     * Retries on [IOException] and on HTTP status codes: 429, 500, 502, 503, 504.
     * Returns null after exhausting all attempts or on a non-retryable HTTP error.
     */
    private suspend fun fetchHtml(url: String): String? {
        val retryableStatuses = setOf(429, 500, 502, 503, 504)
        repeat(MAX_RETRIES) { attempt ->
            try {
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return response.body?.string()
                    if (response.code !in retryableStatuses) return null
                    // Retryable HTTP status – fall through to backoff
                }
            } catch (e: IOException) {
                if (attempt == MAX_RETRIES - 1) return null
            }
            if (attempt < MAX_RETRIES - 1) {
                val backoffMs = BASE_BACKOFF_MS * (1 shl attempt) +
                    Random.nextLong(0, JITTER_MS)
                delay(backoffMs)
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------------

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    // ---------------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------------

    companion object {
        private const val MAX_SUMMARY_CHARS = 400

        /** Chrome version used in the mobile User-Agent string. Update periodically. */
        private const val CHROME_VERSION = "124.0"

        /** Maximum parallel article fetches per source (avoids rate-limit spikes). */
        private const val MAX_CONCURRENT_ARTICLES = 4

        /** Retry attempts for transient network/HTTP failures in [fetchHtml]. */
        private const val MAX_RETRIES = 3

        /** Base delay (ms) for exponential backoff: 500 → 1000 → 2000 ms. */
        private const val BASE_BACKOFF_MS = 500L

        /** Random jitter ceiling (ms) added to backoff to spread retries. */
        private const val JITTER_MS = 250L

        /** Keywords used to filter article links for BLE mesh relevance. */
        private val BLE_KEYWORDS = listOf(
            "ble", "bluetooth", "mesh", "bluetooth mesh", "ble mesh",
            "low energy", "provisioning", "gatt", "profile", "beacon",
            "ibeacon", "eddystone", "zigbee", "thread", "matter", "lorawan",
            "wireless", "iot", "sensor", "gateway", "topology", "relay",
            "proxy", "publication", "subscription", "friend node", "lpn"
        )

        /**
         * Strips common tracking query parameters (utm_*, gclid, fbclid, msclkid, etc.)
         * from [url] to produce a canonical form suitable for deduplication.
         * Non-tracking parameters are preserved; the fragment is dropped.
         */
        fun normalizeUrl(url: String): String {
            return try {
                val uri = URI(url)
                val rawQuery = uri.rawQuery
                if (rawQuery.isNullOrBlank()) {
                    URI(uri.scheme, uri.authority, uri.path, null, null).toString()
                } else {
                    val filtered = rawQuery.split("&").filter { param ->
                        val key = param.substringBefore("=").lowercase(Locale.ROOT)
                        !key.startsWith("utm_") &&
                            key != "gclid" && key != "fbclid" &&
                            key != "msclkid" && key != "mc_eid" && key != "yclid"
                    }
                    val newQuery = if (filtered.isEmpty()) null else filtered.joinToString("&")
                    URI(uri.scheme, uri.authority, uri.path, newQuery, null).toString()
                }
            } catch (e: Exception) {
                url
            }
        }

        /**
         * Extracts the (lower-cased) host from [url], or an empty string on failure.
         * Used to restrict scraped links to the same domain as the source listing page.
         */
        fun extractDomain(url: String): String {
            return try {
                URI(url).host?.lowercase(Locale.ROOT) ?: ""
            } catch (e: Exception) {
                ""
            }
        }
    }
}
