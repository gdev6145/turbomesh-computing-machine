package com.turbomesh.app.ui.notifications

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.turbomesh.app.R
import com.turbomesh.app.agent.ArticleScannerService
import com.turbomesh.app.databinding.ActivityNotificationPanelBinding
import com.turbomesh.app.ui.detail.ArticleDetailActivity
import com.turbomesh.app.viewmodel.ArticleViewModel
import kotlinx.coroutines.launch

/**
 * NotificationPanelActivity
 *
 * Full-screen notification panel displaying BLE mesh articles with search, source filter
 * chips, bookmarks filter, and sort options.
 */
class NotificationPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationPanelBinding
    private val viewModel: ArticleViewModel by viewModels()

    private val adapter = ArticleAdapter(
        onBookmarkToggle = { id -> viewModel.toggleBookmark(id) },
        onArticleClick = { article ->
            val intent = ArticleDetailActivity.newIntent(this, article.id)
            startActivity(intent)
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.notification_panel_title)
            setDisplayHomeAsUpEnabled(true)
        }

        setupRecyclerView()
        setupRefresh()
        setupSearch()
        observeViewModel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_notification_panel, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort -> {
                showSortDialog()
                true
            }
            R.id.action_clear -> {
                viewModel.clearArticles()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // ---------------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------------

    private fun setupRecyclerView() {
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@NotificationPanelActivity)
            adapter = this@NotificationPanelActivity.adapter
            setHasFixedSize(false)
        }
    }

    private fun setupRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            ArticleScannerService.start(this)
        }
        binding.fabRefresh.setOnClickListener {
            ArticleScannerService.start(this)
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    // ---------------------------------------------------------------------------
    // Observation
    // ---------------------------------------------------------------------------

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.filteredArticles.collect { articles ->
                        adapter.submitList(articles)
                        binding.tvEmpty.visibility =
                            if (articles.isEmpty()) View.VISIBLE else View.GONE
                        binding.recyclerView.visibility =
                            if (articles.isEmpty()) View.GONE else View.VISIBLE
                    }
                }

                launch {
                    viewModel.isScanning.collect { scanning ->
                        binding.swipeRefreshLayout.isRefreshing = scanning
                        binding.progressBar.visibility =
                            if (scanning) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.lastScanResults.collect { results ->
                        if (results.isNotEmpty()) {
                            val successCount = results.count {
                                it is com.turbomesh.app.data.model.ScanResult.Success
                            }
                            val hasErrors = successCount < results.size
                            binding.tvScanStatus.text = if (hasErrors) {
                                getString(
                                    R.string.scan_status_with_errors,
                                    successCount,
                                    results.size
                                )
                            } else {
                                getString(R.string.scan_status, successCount, results.size)
                            }
                            binding.tvScanStatus.visibility = View.VISIBLE
                            binding.tvScanStatus.isClickable = hasErrors
                            binding.tvScanStatus.setOnClickListener(
                                if (hasErrors) View.OnClickListener { showSourceDetailsDialog() }
                                else null
                            )
                        }
                    }
                }

                launch {
                    viewModel.errorMessage.collect { error ->
                        error?.let {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.scan_error_partial, it),
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                // Populate source filter chips whenever the source list changes
                launch {
                    viewModel.allSourceNames.collect { sources ->
                        rebuildFilterChips(sources)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Filter chips
    // ---------------------------------------------------------------------------

    private fun rebuildFilterChips(sources: List<String>) {
        val chipGroup = binding.chipGroupFilter
        chipGroup.removeAllViews()

        // "All" chip
        chipGroup.addView(makeChip(getString(R.string.filter_all), null))

        // "★ Saved" chip
        chipGroup.addView(makeChip(getString(R.string.filter_saved), BOOKMARK_FILTER_TAG))

        // Per-source chips
        sources.forEach { source ->
            chipGroup.addView(makeChip(source, source))
        }

        // Restore selection state
        val currentSource = viewModel.sourceFilter.value
        val showBookmarks = viewModel.showBookmarksOnly.value
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            chip.isChecked = when {
                showBookmarks && chip.tag == BOOKMARK_FILTER_TAG -> true
                !showBookmarks && chip.tag == currentSource -> true
                else -> false
            }
        }
    }

    private fun makeChip(label: String, filterTag: Any?): Chip {
        return Chip(this).apply {
            text = label
            tag = filterTag
            isCheckable = true
            isCheckedIconVisible = true
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) applyChipFilter(filterTag)
            }
        }
    }

    private fun applyChipFilter(tag: Any?) {
        when {
            tag == BOOKMARK_FILTER_TAG -> {
                viewModel.setShowBookmarksOnly(true)
                viewModel.setSourceFilter(null)
            }
            tag is String -> {
                viewModel.setShowBookmarksOnly(false)
                viewModel.setSourceFilter(tag)
            }
            else -> {
                // "All"
                viewModel.setShowBookmarksOnly(false)
                viewModel.setSourceFilter(null)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Sort dialog
    // ---------------------------------------------------------------------------

    private fun showSortDialog() {
        val options = ArticleViewModel.SortOrder.entries.map { it.label }.toTypedArray()
        val current = viewModel.sortOrder.value.ordinal
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(options, current) { dialog, which ->
                viewModel.setSortOrder(ArticleViewModel.SortOrder.entries[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------------
    // Source details dialog
    // ---------------------------------------------------------------------------

    private fun showSourceDetailsDialog() {
        val summaries = viewModel.sourceSummary.value
        if (summaries.isEmpty()) return
        val message = summaries.joinToString("\n") { s ->
            if (s.error != null) "✗ ${s.sourceName}:\n    ${s.error}"
            else "✓ ${s.sourceName}: ${s.articleCount} article(s)"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.scan_source_details_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private const val BOOKMARK_FILTER_TAG = "BOOKMARKS"
    }
}
