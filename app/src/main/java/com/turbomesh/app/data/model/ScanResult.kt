package com.turbomesh.app.data.model

/**
 * Encapsulates the result of a single website scan attempt by the ArticleScannerAgent.
 */
sealed class ScanResult {
    /** Scan completed successfully with a list of articles. */
    data class Success(
        val sourceName: String,
        val articles: List<Article>
    ) : ScanResult()

    /** Scan failed; carries the source name and the reason. */
    data class Error(
        val sourceName: String,
        val reason: String,
        val throwable: Throwable? = null
    ) : ScanResult()
}
