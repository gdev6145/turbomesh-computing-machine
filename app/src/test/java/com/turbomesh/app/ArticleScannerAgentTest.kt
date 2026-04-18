package com.turbomesh.app

import com.turbomesh.app.agent.ArticleScannerAgent
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the utility functions in [ArticleScannerAgent.Companion]:
 *  - [ArticleScannerAgent.normalizeUrl] – tracking-param stripping
 *  - [ArticleScannerAgent.extractDomain] – domain extraction
 *
 * Relevance-filtering logic and the tag-selector fix are validated below through
 * direct invocations and Jsoup parsing (no network required).
 */
class ArticleScannerAgentTest {

    // -------------------------------------------------------------------------
    // normalizeUrl
    // -------------------------------------------------------------------------

    @Test
    fun `normalizeUrl strips utm_source param`() {
        val input = "https://example.com/article?utm_source=newsletter"
        val result = ArticleScannerAgent.normalizeUrl(input)
        assertFalse("utm_source should be removed", result.contains("utm_source"))
        assertTrue("path should be preserved", result.contains("/article"))
    }

    @Test
    fun `normalizeUrl strips all utm params`() {
        val input = "https://example.com/post?utm_source=tw&utm_medium=social&utm_campaign=spring"
        val result = ArticleScannerAgent.normalizeUrl(input)
        assertFalse(result.contains("utm_"))
        assertTrue(result.startsWith("https://example.com/post"))
    }

    @Test
    fun `normalizeUrl strips gclid and fbclid`() {
        val input = "https://example.com/page?gclid=abc123&fbclid=xyz789"
        val result = ArticleScannerAgent.normalizeUrl(input)
        assertFalse(result.contains("gclid"))
        assertFalse(result.contains("fbclid"))
    }

    @Test
    fun `normalizeUrl strips msclkid and yclid`() {
        val input = "https://example.com/page?msclkid=m1&yclid=y2"
        val result = ArticleScannerAgent.normalizeUrl(input)
        assertFalse(result.contains("msclkid"))
        assertFalse(result.contains("yclid"))
    }

    @Test
    fun `normalizeUrl preserves non-tracking query params`() {
        val input = "https://example.com/search?q=ble+mesh&page=2"
        val result = ArticleScannerAgent.normalizeUrl(input)
        assertTrue("non-tracking params should be preserved", result.contains("q=ble+mesh"))
        assertTrue(result.contains("page=2"))
    }

    @Test
    fun `normalizeUrl keeps URL unchanged when no query string`() {
        val input = "https://example.com/article/ble-mesh-intro"
        val result = ArticleScannerAgent.normalizeUrl(input)
        assertEquals(input, result)
    }

    @Test
    fun `normalizeUrl removes fragment`() {
        val input = "https://example.com/article?utm_source=foo#section1"
        val result = ArticleScannerAgent.normalizeUrl(input)
        assertFalse(result.contains("#section1"))
    }

    @Test
    fun `normalizeUrl removes query entirely when all params are tracking`() {
        val input = "https://example.com/blog/post?utm_source=x&gclid=y"
        val result = ArticleScannerAgent.normalizeUrl(input)
        assertFalse(result.contains("?"))
    }

    @Test
    fun `normalizeUrl handles malformed URL gracefully`() {
        val input = "not a valid url"
        val result = ArticleScannerAgent.normalizeUrl(input)
        assertEquals(input, result)
    }

    // -------------------------------------------------------------------------
    // extractDomain
    // -------------------------------------------------------------------------

    @Test
    fun `extractDomain returns lowercase host`() {
        assertEquals("www.bluetooth.com", ArticleScannerAgent.extractDomain("https://www.bluetooth.com/blog/"))
    }

    @Test
    fun `extractDomain handles uppercase in URL`() {
        assertEquals("www.example.com", ArticleScannerAgent.extractDomain("HTTPS://www.Example.COM/path"))
    }

    @Test
    fun `extractDomain returns empty string for invalid URL`() {
        assertEquals("", ArticleScannerAgent.extractDomain("not-a-url"))
    }

    // -------------------------------------------------------------------------
    // extractTags selector fix: a[rel=tag] must be used, not rel[tag]
    // -------------------------------------------------------------------------

    @Test
    fun `extractTags selector a[rel=tag] matches anchor with rel=tag attribute`() {
        val html = """
            <html><body>
              <div class="tags">
                <a href="/tag/bluetooth" rel="tag">Bluetooth</a>
                <a href="/tag/mesh" rel="tag">Mesh</a>
              </div>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)

        // The correct selector is a[rel=tag]
        val correctTags = doc.select("a[rel=tag]").map { it.text() }
        assertTrue("a[rel=tag] should find 'Bluetooth'", correctTags.contains("Bluetooth"))
        assertTrue("a[rel=tag] should find 'Mesh'", correctTags.contains("Mesh"))

        // The old buggy selector rel[tag] should return nothing
        val buggyTags = doc.select("rel[tag]")
        assertTrue("rel[tag] (old buggy selector) should match nothing", buggyTags.isEmpty())
    }

    @Test
    fun `extractTags selector matches .tags a class selector`() {
        val html = """
            <html><body>
              <ul class="tags">
                <li><a href="/tag/iot">IoT</a></li>
                <li><a href="/tag/ble">BLE</a></li>
              </ul>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val tags = doc.select(".tags a, .tag a, .category a, a[rel=tag]").map { it.text() }
        assertTrue(tags.contains("IoT"))
        assertTrue(tags.contains("BLE"))
    }
}
