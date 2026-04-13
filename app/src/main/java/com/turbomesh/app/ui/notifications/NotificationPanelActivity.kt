package com.turbomesh.app.ui.notifications

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.turbomesh.app.R
import com.turbomesh.app.agent.ArticleScannerService
import com.turbomesh.app.databinding.ActivityNotificationPanelBinding
import com.turbomesh.app.viewmodel.ArticleViewModel
import kotlinx.coroutines.launch

/**
 * NotificationPanelActivity
 *
 * Full-screen notification panel that displays all fetched BLE mesh articles.
 * Users can trigger a manual refresh via the FAB or the toolbar refresh action.
 */
class NotificationPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationPanelBinding
    private val viewModel: ArticleViewModel by viewModels()
    private val adapter = ArticleAdapter()

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
        observeViewModel()
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

    // ---------------------------------------------------------------------------
    // Observation
    // ---------------------------------------------------------------------------

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.articles.collect { articles ->
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
                            binding.tvScanStatus.text = getString(
                                R.string.scan_status,
                                successCount,
                                results.size
                            )
                            binding.tvScanStatus.visibility = View.VISIBLE
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
            }
        }
    }
}
