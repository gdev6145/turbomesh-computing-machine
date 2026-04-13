package com.turbomesh.app.data.model

/**
 * Represents a single BLE mesh article retrieved from an online source.
 *
 * @param id           Unique identifier (URL-based hash).
 * @param title        Article headline.
 * @param summary      Short automatically-extracted summary (first paragraph / meta description).
 * @param url          Canonical URL of the article.
 * @param sourceName   Human-readable name of the website source.
 * @param sourceUrl    Base URL of the website.
 * @param publishedAt  ISO-8601 date string if parseable, otherwise empty.
 * @param tags         Keywords / tags associated with the article.
 * @param fetchedAt    Unix epoch milliseconds when the article was fetched.
 */
data class Article(
    val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val sourceName: String,
    val sourceUrl: String,
    val publishedAt: String = "",
    val tags: List<String> = emptyList(),
    val fetchedAt: Long = System.currentTimeMillis()
)
