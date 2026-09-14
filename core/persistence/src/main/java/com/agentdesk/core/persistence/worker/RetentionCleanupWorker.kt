package com.agentdesk.core.persistence.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.dao.ConfirmationDao
import com.agentdesk.core.persistence.dao.FtsSearchDao
import com.agentdesk.core.persistence.dao.KnowledgeDao
import com.agentdesk.core.persistence.dao.PolicyDecisionDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * RetentionCleanupWorker runs periodically (every 24 hours) to:
 * 1. Prune AuditEventEntity records older than 30 days.
 * 2. Prune PolicyDecisionEntity records older than 30 days.
 * 3. Prune ConfirmationRecord records older than 30 days.
 * 4. Prune SharedItemEntity records older than 7 days.
 * 5. Prune ExtractedEntityEntity rows where expires_at < now OR created_at
 *    is older than 7 days.
 * 6. Log current FTS index chunk count (index budget summary).
 *
 * Privacy: no data leaves the device during cleanup.
 */
@HiltWorker
class RetentionCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val auditDao: AuditDao,
    private val policyDecisionDao: PolicyDecisionDao,
    private val confirmationDao: ConfirmationDao,
    private val knowledgeDao: KnowledgeDao,
    private val ftsSearchDao: FtsSearchDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val now = System.currentTimeMillis()
            val thirtyDaysAgoMs = now - RETENTION_MS
            val sevenDaysAgoMs = now - SHORT_RETENTION_MS
            auditDao.deleteOlderThan(thirtyDaysAgoMs)
            policyDecisionDao.deleteOlderThan(thirtyDaysAgoMs)
            confirmationDao.deleteOlderThan(thirtyDaysAgoMs)
            knowledgeDao.deleteSharedItemsOlderThan(sevenDaysAgoMs)
            knowledgeDao.deleteExpiredEntities(nowMs = now, cutoffMs = sevenDaysAgoMs)
            val chunkCount = ftsSearchDao.countChunks()
            Log.i(TAG, "Retention cleanup done. FTS chunks: $chunkCount")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Retention cleanup failed", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val TAG = "RetentionCleanupWorker"
        const val WORK_NAME = "agentdesk_retention_cleanup"
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000L
        private const val SHORT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000L
        private const val MAX_RETRIES = 3

        fun buildPeriodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<RetentionCleanupWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiresBatteryNotLow(true).build()
                )
                .addTag(TAG)
                .build()
    }
}
