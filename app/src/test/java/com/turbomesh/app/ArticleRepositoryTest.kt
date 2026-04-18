package com.turbomesh.app

import com.turbomesh.app.data.model.Article
import com.turbomesh.app.data.model.BleDevice
import com.turbomesh.app.data.model.ScanResult
import com.turbomesh.app.data.repository.ArticleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ArticleRepository] in-memory state management,
 * including articles, bookmarks, and scan results.
 */
class ArticleRepositoryTest {

    private lateinit var repository: ArticleRepository

    @Before
    fun setUp() {
        // Reset singleton for test isolation
        val field = ArticleRepository::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, null)

        repository = ArticleRepository::class.java
            .getDeclaredConstructor()
            .also { it.isAccessible = true }
            .newInstance()
    }

    // ---------------------------------------------------------------------------
    // Article storage
    // ---------------------------------------------------------------------------

    @Test
    fun `updateResults stores articles from all successful sources`() {
        val a1 = makeArticle("a1", "Title 1", "Source A")
        val a2 = makeArticle("a2", "Title 2", "Source B")

        repository.updateResults(listOf(
            ScanResult.Success("Source A", listOf(a1)),
            ScanResult.Success("Source B", listOf(a2))
        ))

        assertEquals(2, repository.articles.value.size)
        assertTrue(repository.articles.value.any { it.id == "a1" })
        assertTrue(repository.articles.value.any { it.id == "a2" })
    }

    @Test
    fun `updateResults deduplicates articles across multiple calls`() {
        val article = makeArticle("a1", "Title 1", "Source A")
        repository.updateResults(listOf(ScanResult.Success("Source A", listOf(article))))
        repository.updateResults(listOf(ScanResult.Success("Source A", listOf(article))))

        assertEquals(1, repository.articles.value.size)
    }

    @Test
    fun `updateResults sets errorMessage when a source fails`() {
        repository.updateResults(listOf(ScanResult.Error("Source X", "Timeout")))

        val error = repository.errorMessage.value
        assertTrue(error != null && error.contains("Source X"))
    }

    @Test
    fun `updateResults clears errorMessage when all sources succeed`() {
        repository.updateResults(listOf(ScanResult.Error("Source X", "Timeout")))
        repository.updateResults(listOf(
            ScanResult.Success("Source A", listOf(makeArticle("a1", "T1", "Source A")))
        ))

        assertNull(repository.errorMessage.value)
    }

    @Test
    fun `clearArticles empties the list and clears scan state`() {
        val article = makeArticle("a1", "Title 1", "Source A")
        repository.updateResults(listOf(ScanResult.Success("Source A", listOf(article))))

        repository.clearArticles()

        assertTrue(repository.articles.value.isEmpty())
        assertNull(repository.errorMessage.value)
        assertTrue(repository.lastScanResults.value.isEmpty())
    }

    @Test
    fun `setScanning toggles isScanning state`() {
        repository.setScanning(true)
        assertTrue(repository.isScanning.value)

        repository.setScanning(false)
        assertFalse(repository.isScanning.value)
    }

    // ---------------------------------------------------------------------------
    // Bookmarks
    // ---------------------------------------------------------------------------

    @Test
    fun `toggleBookmark adds an article to bookmarkedIds`() {
        val added = repository.toggleBookmark("a1")

        assertTrue(added)
        assertTrue(repository.bookmarkedIds.value.contains("a1"))
        assertTrue(repository.isBookmarked("a1"))
    }

    @Test
    fun `toggleBookmark removes an already-bookmarked article`() {
        repository.toggleBookmark("a1")
        val removed = repository.toggleBookmark("a1")

        assertFalse(removed)
        assertFalse(repository.bookmarkedIds.value.contains("a1"))
        assertFalse(repository.isBookmarked("a1"))
    }

    @Test
    fun `bookmarks are preserved after clearArticles`() {
        repository.toggleBookmark("a1")
        repository.clearArticles()

        assertTrue(repository.bookmarkedIds.value.contains("a1"))
    }

    @Test
    fun `bookmarkedCount reflects the number of bookmarked articles`() {
        assertEquals(0, repository.bookmarkedCount)
        repository.toggleBookmark("a1")
        repository.toggleBookmark("a2")
        assertEquals(2, repository.bookmarkedCount)
        repository.toggleBookmark("a1")
        assertEquals(1, repository.bookmarkedCount)
    }

    // ---------------------------------------------------------------------------
    // BleDevice companion helpers
    // ---------------------------------------------------------------------------

    @Test
    fun `BleDevice isMeshServiceUuid recognises provisioning UUID`() {
        assertTrue(BleDevice.isMeshServiceUuid("00001827-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun `BleDevice isMeshServiceUuid recognises proxy UUID`() {
        assertTrue(BleDevice.isMeshServiceUuid("00001828-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun `BleDevice isMeshServiceUuid is case-insensitive`() {
        assertTrue(BleDevice.isMeshServiceUuid("00001827-0000-1000-8000-00805F9B34FB"))
    }

    @Test
    fun `BleDevice isMeshServiceUuid rejects unknown UUID`() {
        assertFalse(BleDevice.isMeshServiceUuid("00001800-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun `BleDevice signalStrengthLabel returns correct tier`() {
        assertEquals("Strong (-55 dBm)", BleDevice("", "AA:BB:CC:DD:EE:FF", -55, false).signalStrengthLabel)
        assertEquals("Good (-70 dBm)", BleDevice("", "AA:BB:CC:DD:EE:FF", -70, false).signalStrengthLabel)
        assertEquals("Weak (-80 dBm)", BleDevice("", "AA:BB:CC:DD:EE:FF", -80, false).signalStrengthLabel)
        assertEquals("Very Weak (-95 dBm)", BleDevice("", "AA:BB:CC:DD:EE:FF", -95, false).signalStrengthLabel)
    }

    @Test
    fun `BleDevice displayName falls back to address when name is blank`() {
        val device = BleDevice("", "AA:BB:CC:DD:EE:FF", -65, false)
        assertEquals("AA:BB:CC:DD:EE:FF", device.displayName)
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun makeArticle(id: String, title: String, source: String) = Article(
        id = id,
        title = title,
        summary = "Summary for $title",
        url = "https://example.com/$id",
        sourceName = source,
        sourceUrl = "https://example.com"
    )
}
