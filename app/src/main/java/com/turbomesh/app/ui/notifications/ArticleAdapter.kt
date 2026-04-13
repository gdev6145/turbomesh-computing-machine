package com.turbomesh.app.ui.notifications

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.turbomesh.app.data.model.Article
import com.turbomesh.app.databinding.ItemArticleBinding

/**
 * RecyclerView adapter that displays [Article] cards in the notification panel.
 */
class ArticleAdapter : ListAdapter<Article, ArticleAdapter.ArticleViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleViewHolder {
        val binding = ItemArticleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ArticleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ArticleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ArticleViewHolder(
        private val binding: ItemArticleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(article: Article) {
            binding.tvTitle.text = article.title
            binding.tvSummary.text = article.summary
            binding.tvSource.text = article.sourceName
            binding.tvDate.text = article.publishedAt.ifBlank { "" }

            // Show tags as a comma-separated string when present
            if (article.tags.isNotEmpty()) {
                binding.tvTags.text = article.tags.joinToString(" · ")
                binding.tvTags.visibility = android.view.View.VISIBLE
            } else {
                binding.tvTags.visibility = android.view.View.GONE
            }

            // Open article URL in browser when card is tapped
            binding.root.setOnClickListener { view ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(article.url))
                view.context.startActivity(intent)
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
