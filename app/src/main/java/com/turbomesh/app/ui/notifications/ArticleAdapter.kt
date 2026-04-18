package com.turbomesh.app.ui.notifications

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.turbomesh.app.data.model.Article
import com.turbomesh.app.databinding.ItemArticleBinding

/**
 * RecyclerView adapter that displays [Article] cards in the notification panel.
 *
 * @param onBookmarkToggle Called with the article ID when the bookmark icon is tapped.
 * @param onArticleClick   Called with the article when the card body is tapped.
 */
class ArticleAdapter(
    private val onBookmarkToggle: (articleId: String) -> Unit = {},
    private val onArticleClick: (article: Article) -> Unit = {}
) : ListAdapter<Article, ArticleAdapter.ArticleViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleViewHolder {
        val binding = ItemArticleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ArticleViewHolder(binding, onBookmarkToggle, onArticleClick)
    }

    override fun onBindViewHolder(holder: ArticleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ArticleViewHolder(
        private val binding: ItemArticleBinding,
        private val onBookmarkToggle: (articleId: String) -> Unit,
        private val onArticleClick: (article: Article) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(article: Article) {
            binding.tvTitle.text = article.title
            binding.tvSummary.text = article.summary
            binding.tvSource.text = article.sourceName
            binding.tvDate.text = article.publishedAt.ifBlank { "" }

            if (article.tags.isNotEmpty()) {
                binding.tvTags.text = article.tags.joinToString(" · ")
                binding.tvTags.visibility = View.VISIBLE
            } else {
                binding.tvTags.visibility = View.GONE
            }

            // Bookmark icon state
            binding.ibBookmark.setImageResource(
                if (article.isBookmarked) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            binding.ibBookmark.setOnClickListener {
                onBookmarkToggle(article.id)
            }

            // Share button
            binding.ibShare.setOnClickListener { view ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, article.title)
                    putExtra(Intent.EXTRA_TEXT, "${article.title}\n\n${article.url}")
                }
                view.context.startActivity(Intent.createChooser(shareIntent, article.title))
            }

            // Tap card body → open detail screen
            binding.root.setOnClickListener {
                onArticleClick(article)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Article>() {
            override fun areItemsTheSame(old: Article, new: Article) = old.id == new.id
            override fun areContentsTheSame(old: Article, new: Article) = old == new
        }
    }
}
