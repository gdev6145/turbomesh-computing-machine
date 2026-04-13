package com.turbomesh.app

import com.turbomesh.app.data.model.Article
import com.turbomesh.app.data.model.ScanResult
import com.turbomesh.app.data.repository.ArticleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ArticleRepository] in-memory state management.
 */
class ArticleRepositoryTest {

    // We test the singleton via reflection to create a fresh instance per test.
    private lateinit var repository: ArticleRepository

    @Before
    fun setUp() {
        // Reset singleton for isolation
        val field = ArticleRepository::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, null)

        // Create a fresh instance by calling getInstance with a null-safe stub
        repository = ArticleRepository::class.java
            .getDeclaredConstructor()
            .also { it.isAccessible = true }
            .newInstance()
    }

    @Test
    fun `updateResults stores articles and deduplicates by id`() {
        val article1 = makeArticle("a1", "Title 1", "Source A")
        val article2 = makeArticle("a2", "Title 2", "Source B")

        val results = listOf(
            ScanResult.Success("Source A", listOf(article1)),
            ScanResult.Success("Source B", listOf(article2))
        )

        repository.updateResults(results)

        val stored = repository.articles.value
        assertEquals(2, stored.size)
        assertTrue(stored.any { it.id == "a1" })
        assertTrue(stored.any { it.id == "a2" })
    }

    @Test
    fun `updateResults deduplicates on second call`() {
        val article = makeArticle("a1", "Title 1", "Source A")

        repository.updateResults(listOf(ScanResult.Success("Source A", listOf(article))))
        repository.updateResults(listOf(ScanResult.Success("Source A", listOf(article))))

        assertEquals(1, repository.articles.value.size)
    }

    @Test
    fun `updateResults sets errorMessage when source fails`() {
        repository.updateResults(
            listOf(ScanResult.Error("Source X", "Connection refused"))
        )

        val error = repository.errorMessage.value
        assertTrue(error != null && error.contains("Source X"))
    }

    @Test
    fun `clearArticles empties the list`() {
        val article = makeArticle("a1", "Title 1", "Source A")
        repository.updateResults(listOf(ScanResult.Success("Source A", listOf(article))))

        repository.clearArticles()

        assertTrue(repository.articles.value.isEmpty())
        assertNull(repository.errorMessage.value)
    }

    @Test
    fun `setScanning toggles isScanning state`() {
        repository.setScanning(true)
        assertTrue(repository.isScanning.value)

        repository.setScanning(false)
        assertTrue(!repository.isScanning.value)
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
