package com.agentdesk.agent.habit

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.agentdesk.core.device.ResourceGovernor
import com.agentdesk.core.persistence.dao.UserHabitDao
import com.agentdesk.core.persistence.entity.UserHabitEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * AmbientHabitWorker runs in the background to aggregate foreground app usage stats
 * into local [UserHabitEntity] records in Room DB.
 *
 * Privacy Invariant:
 * Strictly zero cloud egress. Processing is 100% on-device.
 *
 * Battery Governance:
 * Must run only when device is charging, idle, and battery/storage are not low.
 * Never causes Doze wakeups.
 */
@HiltWorker
class AmbientHabitWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val usageStatsProvider: UsageStatsProvider,
    private val habitDao: UserHabitDao,
    private val resourceGovernor: ResourceGovernor
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return syncHabits(usageStatsProvider, habitDao, resourceGovernor)
    }

    companion object {
        const val TAG = "AmbientHabitWorker"
        const val WORK_NAME = "agentdesk_ambient_habit_sync"

        /**
         * Executes the habit synchronization logic aggregating foreground usage events into Room DB.
         */
        suspend fun syncHabits(
            usageStatsProvider: UsageStatsProvider,
            habitDao: UserHabitDao,
            resourceGovernor: ResourceGovernor
        ): Result {
            // Battery and thermal governance check
            if (!resourceGovernor.canIndex()) {
                return Result.retry()
            }

            val events = usageStatsProvider.queryRecentForegroundEvents()
            if (events.isEmpty()) {
                return Result.success()
            }

            val grouped = events.groupBy { it.packageName to it.hourOfDay }
            for ((key, eventList) in grouped) {
                val (pkg, hour) = key
                val existing = habitDao.getHabit(pkg, hour)
                val updated = existing?.copy(
                    interactionCount = existing.interactionCount + eventList.size,
                    lastObservedAt = System.currentTimeMillis()
                ) ?: UserHabitEntity(
                    packageName = pkg,
                    appName = pkg.substringAfterLast('.'),
                    hourOfDay = hour,
                    interactionCount = eventList.size,
                    associatedIntent = mapPackageToIntent(pkg),
                    lastObservedAt = System.currentTimeMillis()
                )
                habitDao.insertOrUpdate(updated)
            }

            return Result.success()
        }

        fun mapPackageToIntent(pkg: String): String? = when {
            pkg.contains("calendar", ignoreCase = true) -> "calendar.create_event"
            pkg.contains("clock", ignoreCase = true)    -> "system.set_alarm"
            pkg.contains("maps", ignoreCase = true)     -> "maps.navigate"
            else                                        -> null
        }

        /**
         * Builds a battery-neutral periodic work request with strict idle/charging constraints.
         */
        fun buildWorkRequest(): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

            return PeriodicWorkRequestBuilder<AmbientHabitWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag(TAG)
                .build()
        }
    }
}
