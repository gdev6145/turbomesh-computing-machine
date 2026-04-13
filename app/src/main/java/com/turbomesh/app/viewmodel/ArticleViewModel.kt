package com.turbomesh.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.app.data.model.Article
import com.turbomesh.app.data.model.ScanResult
import com.turbomesh.app.data.repository.ArticleRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * ArticleViewModel
 *
 * Exposes [ArticleRepository] state to the UI layer and provides convenience derived flows.
 */
class ArticleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ArticleRepository = ArticleRepository.getInstance(application)

    /** Full article list, newest-first. */
    val articles: StateFlow<List<Article>> = repository.articles

    /** True while a scan is in progress. */
    val isScanning: StateFlow<Boolean> = repository.isScanning

    /** Raw scan results from the most recent run. */
    val lastScanResults: StateFlow<List<ScanResult>> = repository.lastScanResults

    /** Error message if any source failed; null otherwise. */
    val errorMessage: StateFlow<String?> = repository.errorMessage

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

    fun clearArticles() = repository.clearArticles()

    /** Summary row shown in the scan-status section of the notification panel. */
    data class SourceSummary(
        val sourceName: String,
        val articleCount: Int,
        val error: String?
    )
}
