package com.turbomesh.app.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.turbomesh.app.R
import com.turbomesh.app.databinding.ActivityArticleDetailBinding
import com.turbomesh.app.viewmodel.ArticleViewModel
import kotlinx.coroutines.launch

/**
 * ArticleDetailActivity
 *
 * Shows the full detail of a single BLE mesh article: title, source, date, tags,
 * full summary text, a bookmark toggle, a share button, and an "Open in Browser" button.
 *
 * Receives the article ID via [EXTRA_ARTICLE_ID] and looks it up from the shared repository
 * via [ArticleViewModel].
 */
class ArticleDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArticleDetailBinding
    private val viewModel: ArticleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArticleDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val articleId = intent.getStringExtra(EXTRA_ARTICLE_ID)
        if (articleId == null) {
            finish()
            return
        }

        observeArticle(articleId)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun observeArticle(articleId: String) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.articles.collect { articles ->
                    val article = articles.firstOrNull { it.id == articleId }
                    if (article == null) return@collect

                    supportActionBar?.title = article.sourceName

                    binding.tvDetailTitle.text = article.title
                    binding.tvDetailSource.text = article.sourceName
                    binding.tvDetailDate.text = article.publishedAt.ifBlank {
                        getString(R.string.date_unknown)
                    }
                    binding.tvDetailSummary.text = article.summary

                    if (article.tags.isNotEmpty()) {
                        binding.tvDetailTags.text = article.tags.joinToString("  ·  ")
                        binding.tvDetailTags.visibility = View.VISIBLE
                    } else {
                        binding.tvDetailTags.visibility = View.GONE
                    }

                    // Bookmark icon
                    binding.btnBookmark.text = if (article.isBookmarked) {
                        getString(R.string.bookmarked)
                    } else {
                        getString(R.string.bookmark)
                    }
                    binding.btnBookmark.setOnClickListener {
                        viewModel.toggleBookmark(articleId)
                    }

                    // Share button
                    binding.btnShare.setOnClickListener {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, article.title)
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "${article.title}\n\n${article.summary}\n\n${article.url}"
                            )
                        }
                        startActivity(Intent.createChooser(shareIntent, article.title))
                    }

                    // Open in browser
                    binding.btnOpenBrowser.setOnClickListener {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(article.url))
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_ARTICLE_ID = "extra_article_id"

        fun newIntent(context: Context, articleId: String): Intent =
            Intent(context, ArticleDetailActivity::class.java).apply {
                putExtra(EXTRA_ARTICLE_ID, articleId)
            }
    }
}
