package com.turbomesh.app.data.repository

import android.content.Context
import com.turbomesh.app.data.model.Article
import com.turbomesh.app.data.model.ScanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ArticleRepository
 *
 * Single source of truth for BLE mesh articles.  Uses in-memory [StateFlow]s that the UI
 * observes.  Articles are deduped by [Article.id] and sorted newest-first.
 *
 * Also owns the set of bookmarked article IDs so that multiple ViewModels share the same state.
 *
 * A singleton is used so both the foreground service and the ViewModel share the same data.
 */
class ArticleRepository private constructor() {

    // ---------------------------------------------------------------------------
    // State – articles
    // ---------------------------------------------------------------------------

    private val _articles = MutableStateFlow<List<Article>>(emptyList())
    /** Observable list of all collected articles, newest-first. */
    val articles: StateFlow<List<Article>> = _articles.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    /** True while the ArticleScannerAgent / Service is actively fetching data. */
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _lastScanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    /** The raw [ScanResult] list from the most recent scan run. */
    val lastScanResults: StateFlow<List<ScanResult>> = _lastScanResults.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    /** Non-null when the last scan encountered at least one source error. */
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ---------------------------------------------------------------------------
    // State – bookmarks
    // ---------------------------------------------------------------------------

    private val _bookmarkedIds = MutableStateFlow<Set<String>>(emptySet())
    /** IDs of bookmarked articles. */
    val bookmarkedIds: StateFlow<Set<String>> = _bookmarkedIds.asStateFlow()

    /** Number of currently bookmarked articles. */
    val bookmarkedCount: Int get() = _bookmarkedIds.value.size

    // ---------------------------------------------------------------------------
    // Mutations – articles
    // ---------------------------------------------------------------------------

    fun setScanning(value: Boolean) {
        _isScanning.value = value
    }

    /**
     * Merges [results] into the current article list, deduplicating by [Article.id].
     * Errors are collected into [errorMessage].
     */
    fun updateResults(results: List<ScanResult>) {
        _lastScanResults.value = results

        val newArticles = results.filterIsInstance<ScanResult.Success>()
            .flatMap { it.articles }

        val existingIds = _articles.value.map { it.id }.toHashSet()
        val combined = (_articles.value + newArticles.filter { it.id !in existingIds })
            .sortedByDescending { it.fetchedAt }

        _articles.value = combined

        val errors = results.filterIsInstance<ScanResult.Error>()
        _errorMessage.value = if (errors.isNotEmpty()) {
            errors.joinToString("\n") { "• ${it.sourceName}: ${it.reason}" }
        } else {
            null
        }
    }

    /** Clears all stored articles and scan state. Bookmarks are preserved. */
    fun clearArticles() {
        _articles.value = emptyList()
        _errorMessage.value = null
        _lastScanResults.value = emptyList()
    }

    // ---------------------------------------------------------------------------
    // Mutations – bookmarks
    // ---------------------------------------------------------------------------

    /**
     * Toggles the bookmark state for the article with the given [articleId].
     * Returns true if the article is now bookmarked, false if it was un-bookmarked.
     */
    fun toggleBookmark(articleId: String): Boolean {
        val current = _bookmarkedIds.value.toMutableSet()
        val added = if (articleId in current) {
            current.remove(articleId)
            false
        } else {
            current.add(articleId)
            true
        }
        _bookmarkedIds.value = current
        return added
    }

    /** Returns true if [articleId] is currently bookmarked. */
    fun isBookmarked(articleId: String): Boolean = articleId in _bookmarkedIds.value

    // ---------------------------------------------------------------------------
    // Singleton
    // ---------------------------------------------------------------------------

    companion object {
        @Volatile
        private var INSTANCE: ArticleRepository? = null

        @Suppress("UNUSED_PARAMETER")
        fun getInstance(context: Context): ArticleRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ArticleRepository().also { INSTANCE = it }
            }
    }
}
