package com.turbomesh.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.turbomesh.app.R
import com.turbomesh.app.data.repository.ArticleRepository
import com.turbomesh.app.ui.notifications.NotificationPanelActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ArticleScannerService
 *
 * A foreground [Service] that runs the [ArticleScannerAgent] in the background, stores the
 * results in [ArticleRepository], and posts an Android notification summarising what was found.
 *
 * Start via [ArticleScannerService.start] and stop via [ArticleScannerService.stop].
 */
class ArticleScannerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val agent = ArticleScannerAgent()
    private val repository: ArticleRepository by lazy { ArticleRepository.getInstance(this) }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startScanning()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------------------

    private fun startScanning() {
        startForeground(NOTIFICATION_ID_SCANNING, buildScanningNotification())

        serviceScope.launch {
            repository.setScanning(true)

            val results = agent.scanAll()
            repository.updateResults(results)

            val totalArticles = results.sumOf { result ->
                when (result) {
                    is com.turbomesh.app.data.model.ScanResult.Success -> result.articles.size
                    else -> 0
                }
            }

            postCompletionNotification(totalArticles)
            repository.setScanning(false)
            stopSelf()
        }
    }

    // ---------------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------------

    private fun buildScanningNotification(): Notification {
        ensureChannel()
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, NotificationPanelActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ble_mesh)
            .setContentTitle(getString(R.string.scanning_title))
            .setContentText(getString(R.string.scanning_text))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun postCompletionNotification(articleCount: Int) {
        ensureChannel()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tapIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, NotificationPanelActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ble_mesh)
            .setContentTitle(getString(R.string.scan_complete_title))
            .setContentText(getString(R.string.scan_complete_text, articleCount))
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()
        nm.notify(NOTIFICATION_ID_COMPLETE, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Mesh News",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for BLE mesh article scan results"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    // ---------------------------------------------------------------------------
    // Companion / static helpers
    // ---------------------------------------------------------------------------

    companion object {
        const val ACTION_START = "com.turbomesh.app.ACTION_START_SCAN"
        const val ACTION_STOP = "com.turbomesh.app.ACTION_STOP_SCAN"

        private const val CHANNEL_ID = "ble_mesh_scanner"
        private const val NOTIFICATION_ID_SCANNING = 1001
        private const val NOTIFICATION_ID_COMPLETE = 1002

        fun start(context: Context) {
            val intent = Intent(context, ArticleScannerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ArticleScannerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
