package com.turbomesh.app.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.turbomesh.app.R
import com.turbomesh.app.agent.ArticleScannerService
import com.turbomesh.app.agent.ArticleScannerWorker
import com.turbomesh.app.databinding.ActivityMainBinding
import com.turbomesh.app.ui.notifications.NotificationPanelActivity
import com.turbomesh.app.viewmodel.ArticleViewModel
import kotlinx.coroutines.launch

/**
 * MainActivity
 *
 * Landing screen of the TurboMesh app.  Shows:
 *  - BLE Mesh status dashboard (placeholder for future BLE logic)
 *  - An embedded notification panel preview showing latest BLE mesh articles
 *  - Controls to trigger a manual article scan and navigate to the full panel
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ArticleViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Result handled silently; the scan still works without the permission */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        requestNotificationPermissionIfNeeded()

        // Schedule periodic background scans (every 6 hours)
        ArticleScannerWorker.schedule(this)

        setupButtons()
        observeViewModel()
    }

    // ---------------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------------

    private fun setupButtons() {
        binding.btnScanNow.setOnClickListener {
            ArticleScannerService.start(this)
        }

        binding.btnOpenPanel.setOnClickListener {
            startActivity(
                android.content.Intent(this, NotificationPanelActivity::class.java)
            )
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
                        val count = articles.size
                        binding.tvArticleCount.text = resources.getQuantityString(
                            R.plurals.articles_found, count, count
                        )

                        // Show latest 3 article titles in the preview section
                        val preview = articles.take(3)
                            .joinToString("\n\n") { "• ${it.title}\n  ${it.sourceName}" }
                        binding.tvArticlePreview.text =
                            preview.ifBlank { getString(R.string.no_articles_yet) }

                        binding.btnOpenPanel.visibility =
                            if (count > 0) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.isScanning.collect { scanning ->
                        binding.progressBar.visibility =
                            if (scanning) View.VISIBLE else View.GONE
                        binding.btnScanNow.isEnabled = !scanning
                        binding.btnScanNow.text =
                            if (scanning) getString(R.string.scanning)
                            else getString(R.string.scan_now)
                    }
                }

                launch {
                    viewModel.sourceSummary.collect { summaries ->
                        if (summaries.isNotEmpty()) {
                            binding.tvSourceStatus.text = summaries.joinToString("\n") { s ->
                                if (s.error != null) "✗ ${s.sourceName}: ${s.error}"
                                else "✓ ${s.sourceName}: ${s.articleCount} articles"
                            }
                            binding.tvSourceStatus.visibility = View.VISIBLE
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

    // ---------------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------------

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
