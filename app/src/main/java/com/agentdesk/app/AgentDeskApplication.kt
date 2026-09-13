package com.agentdesk.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.agentdesk.core.persistence.worker.RetentionCleanupWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * AgentDesk Application class.
 *
 * Responsibilities:
 * - Initialize Hilt dependency injection
 * - Configure WorkManager with Hilt worker factory
 * - Schedule RetentionCleanupWorker (24h periodic, battery-safe)
 * - No cloud services initialized here
 * - No analytics or crash reporting by default
 */
@HiltAndroidApp
class AgentDeskApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        scheduleRetentionCleanup()
    }

    /**
     * Schedules the periodic retention cleanup worker.
     * Uses KEEP policy: if a job already exists with this name, it is not replaced.
     * This ensures the cleanup runs once every 24 hours on battery-safe conditions.
     */
    private fun scheduleRetentionCleanup() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RetentionCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            RetentionCleanupWorker.buildPeriodicRequest()
        )
    }
}
