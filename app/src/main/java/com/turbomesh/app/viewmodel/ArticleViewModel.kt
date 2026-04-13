package com.turbomesh.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.app.data.model.Article
import com.turbomesh.app.data.model.ScanResult
import com.turbomesh.app.data.repository.ArticleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * ArticleViewModel
 *
 * Exposes [ArticleRepository] state to the UI layer and provides convenience derived flows.
 * Supports live search, source filtering, bookmark-only filtering, and sort order.
 */
class ArticleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ArticleRepository = ArticleRepository.getInstance(application)

    // ---------------------------------------------------------------------------
    // Raw repository state
    // ---------------------------------------------------------------------------

    /** True while a scan is in progress. */
    val isScanning: StateFlow<Boolean> = repository.isScanning

    /** Raw scan results from the most recent run. */
    val lastScanResults: StateFlow<List<ScanResult>> = repository.lastScanResults

    /** Error message if any source failed; null otherwise. */
    val errorMessage: StateFlow<String?> = repository.errorMessage

    /** Set of bookmarked article IDs. */
    val bookmarkedIds: StateFlow<Set<String>> = repository.bookmarkedIds

    // ---------------------------------------------------------------------------
    // Filter / sort state (UI-driven)
    // ---------------------------------------------------------------------------

    private val _searchQuery = MutableStateFlow("")
    /** Current search query string (empty = no filter). */
    val searchQuery: StateFlow<String> = _searchQuery

    private val _sourceFilter = MutableStateFlow<String?>(null)
    /** Active source filter; null means "All sources". */
    val sourceFilter: StateFlow<String?> = _sourceFilter

    private val _showBookmarksOnly = MutableStateFlow(false)
    /** When true only bookmarked articles are shown. */
    val showBookmarksOnly: StateFlow<Boolean> = _showBookmarksOnly

    private val _sortOrder = MutableStateFlow(SortOrder.NEWEST_FIRST)
    /** Active sort order for the article list. */
    val sortOrder: StateFlow<SortOrder> = _sortOrder

    // ---------------------------------------------------------------------------
    // Derived flows
    // ---------------------------------------------------------------------------

    /**
     * Article list with [Article.isBookmarked] populated, filtered and sorted
     * according to the current filter / sort state.
     */
    val filteredArticles: StateFlow<List<Article>> = combine(
        repository.articles,
        repository.bookmarkedIds,
        _searchQuery,
        _sourceFilter,
        _showBookmarksOnly
    ) { articles, bookmarkedIds, query, source, bookmarksOnly ->
        articles
            .map { it.copy(isBookmarked = it.id in bookmarkedIds) }
            .filter { article ->
                val matchesQuery = query.isBlank() ||
                    article.title.contains(query, ignoreCase = true) ||
                    article.summary.contains(query, ignoreCase = true) ||
                    article.tags.any { it.contains(query, ignoreCase = true) }
                val matchesSource = source == null || article.sourceName == source
                val matchesBookmarks = !bookmarksOnly || article.isBookmarked
                matchesQuery && matchesSource && matchesBookmarks
            }
    }.combine(_sortOrder) { articles, sort ->
        when (sort) {
            SortOrder.NEWEST_FIRST -> articles.sortedByDescending { it.fetchedAt }
            SortOrder.OLDEST_FIRST -> articles.sortedBy { it.fetchedAt }
            SortOrder.BY_SOURCE -> articles.sortedBy { it.sourceName }
            SortOrder.BY_TITLE -> articles.sortedBy { it.title }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Full unfiltered article list with [Article.isBookmarked] populated.
     * Used by [MainActivity] for the preview snippet.
     */
    val articles: StateFlow<List<Article>> = combine(
        repository.articles,
        repository.bookmarkedIds
    ) { articles, bookmarkedIds ->
        articles.map { it.copy(isBookmarked = it.id in bookmarkedIds) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Distinct source names extracted from the current article list; for filter chips. */
    val allSourceNames: StateFlow<List<String>> = repository.articles
        .map { articles -> articles.map { it.sourceName }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-source article counts derived from the latest scan results. */
    val sourceSummary: StateFlow<List<SourceSummary>> = repository.lastScanResults
        .map { results ->
            results.map { result ->
                when (result) {
                    is ScanResult.Success -> SourceSummary(
                        result.sourceName, result.articles.size, null
                    )
                    is ScanResult.Error -> SourceSummary(
                        result.sourceName, 0, result.reason
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setSourceFilter(source: String?) { _sourceFilter.value = source }
    fun setShowBookmarksOnly(enabled: Boolean) { _showBookmarksOnly.value = enabled }
    fun setSortOrder(order: SortOrder) { _sortOrder.value = order }

    fun toggleBookmark(articleId: String) = repository.toggleBookmark(articleId)
    fun clearArticles() = repository.clearArticles()

    // ---------------------------------------------------------------------------
    // Types
    // ---------------------------------------------------------------------------

    /** Summary row shown in the scan-status section of the notification panel. */
    data class SourceSummary(
        val sourceName: String,
        val articleCount: Int,
        val error: String?
    )

    enum class SortOrder(val label: String) {
        NEWEST_FIRST("Newest first"),
        OLDEST_FIRST("Oldest first"),
        BY_SOURCE("By source"),
        BY_TITLE("By title")
    }
}
