package com.turbomesh.app.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.turbomesh.app.data.repository.ArticleRepository
import java.util.concurrent.TimeUnit

/**
 * ArticleScannerWorker
 *
 * A [CoroutineWorker] that is scheduled by WorkManager to run the [ArticleScannerAgent]
 * periodically (every 6 hours by default).  On each run it fetches articles from all five
 * BLE mesh sources and stores them in [ArticleRepository].
 *
 * Use [ArticleScannerWorker.schedule] to register the periodic task.
 */
class ArticleScannerWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val agent = ArticleScannerAgent()
        val repository = ArticleRepository.getInstance(applicationContext)

        return try {
            repository.setScanning(true)
            val results = agent.scanAll()
            repository.updateResults(results)
            repository.setScanning(false)
            Result.success()
        } catch (e: Exception) {
            repository.setScanning(false)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "ble_mesh_article_scan"

        /**
         * Enqueues a periodic scan every [intervalHours] hours.
         * Calls from MainActivity / Application onCreate are safe.
         */
        fun schedule(context: Context, intervalHours: Long = 6) {
            val request = PeriodicWorkRequestBuilder<ArticleScannerWorker>(
                intervalHours, TimeUnit.HOURS,
                30, TimeUnit.MINUTES  // flex period
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Cancel the periodic scan. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
