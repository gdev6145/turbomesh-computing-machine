package com.turbomesh.app.agent

import com.turbomesh.app.data.model.Article
import com.turbomesh.app.data.model.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
                        "(KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"
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
    suspend fun scanAll(): List<ScanResult> = withContext(Dispatchers.IO) {
        sources.map { source -> scanSource(source) }
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

    private fun scanSource(source: WebSource): ScanResult {
        return try {
            val listingHtml = fetchHtml(source.listingUrl)
                ?: return ScanResult.Error(source.name, "Failed to fetch listing page")

            val listingDoc = Jsoup.parse(listingHtml, source.listingUrl)
            val articleLinks = extractArticleLinks(listingDoc, source)

            val articles = articleLinks
                .take(source.maxArticles)
                .mapNotNull { url -> fetchArticle(url, source) }

            ScanResult.Success(source.name, articles)
        } catch (e: IOException) {
            ScanResult.Error(source.name, "Network error: ${e.message}", e)
        } catch (e: Exception) {
            ScanResult.Error(source.name, "Unexpected error: ${e.message}", e)
        }
    }

    /**
     * Returns a deduplicated list of absolute article URLs from a listing page.
     * Only links that contain BLE / mesh relevant keywords in their href or visible text
     * are retained, to avoid pulling unrelated content.
     */
    private fun extractArticleLinks(doc: Document, source: WebSource): List<String> {
        val links = mutableSetOf<String>()

        // Primary selector
        doc.select(source.articleLinkSelector).forEach { el ->
            val href = el.absUrl("href")
            if (href.isNotBlank() && isRelevantLink(href, el.text())) {
                links.add(href)
            }
        }

        // Fallback: if primary selector found nothing, try generic article/a scanning
        if (links.isEmpty()) {
            val sourceDomain = source.listingUrl
                .substringAfter("://")
                .substringBefore("/")
            doc.select("a[href]").forEach { el ->
                val href = el.absUrl("href")
                if (href.contains(sourceDomain) && isRelevantLink(href, el.text())) {
                    links.add(href)
                }
            }
        }

        return links.toList()
    }

    /**
     * Returns true when the URL or link text contains keywords related to BLE mesh topics.
     */
    private fun isRelevantLink(href: String, text: String): Boolean {
        val combined = (href + " " + text).lowercase()
        return BLE_KEYWORDS.any { keyword -> combined.contains(keyword) } ||
            // Accept any article link from the source even without keyword match,
            // as listing pages are already filtered to BLE / wireless topics.
            (href.length > 20 && !href.endsWith("/") && !combined.contains("privacy") &&
                !combined.contains("cookie") && !combined.contains("login"))
    }

    /**
     * Fetches an article page and extracts its title, summary, date, and tags.
     */
    private fun fetchArticle(url: String, source: WebSource): Article? {
        return try {
            val html = fetchHtml(url) ?: return null
            val doc = Jsoup.parse(html, url)

            val title = extractTitle(doc)
            if (title.isBlank()) return null

            val summary = extractSummary(doc, source.bodySelector)
            val date = extractDate(doc, source.dateSelector)
            val tags = extractTags(doc)

            Article(
                id = sha256(url),
                title = title,
                summary = summary,
                url = url,
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
        return doc.select(".tags a, .tag a, .category a, rel[tag]")
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

    private fun fetchHtml(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
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

        /** Keywords used to filter article links for BLE mesh relevance. */
        private val BLE_KEYWORDS = listOf(
            "ble", "bluetooth", "mesh", "bluetooth mesh", "ble mesh",
            "low energy", "provisioning", "gatt", "profile", "beacon",
            "ibeacon", "eddystone", "zigbee", "thread", "matter", "lorawan",
            "wireless", "iot", "sensor", "gateway", "topology", "relay",
            "proxy", "publication", "subscription", "friend node", "lpn"
        )
    }
}
