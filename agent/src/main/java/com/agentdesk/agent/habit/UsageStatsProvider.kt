package com.agentdesk.agent.habit

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides on-device foreground app interaction events using Android's [UsageStatsManager].
 *
 * Privacy Invariant:
 * Strictly on-device only. Query results are aggregated locally into [UserHabitEntity]
 * and never leave the device (zero cloud egress).
 *
 * Permission Guard:
 * PACKAGE_USAGE_STATS is a protected AppOp. Access is verified via [AppOpsManager]
 * before querying. If permission is ungranted or revoked, gracefully returns empty lists.
 */
@Singleton
open class UsageStatsProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Checks whether PACKAGE_USAGE_STATS AppOp is granted to this application.
     */
    open fun hasPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Queries recent foreground application transitions within the specified duration window (default 24h).
     */
    open fun queryRecentForegroundEvents(durationMs: Long = 24 * 60 * 60 * 1000L): List<ForegroundAppEvent> {
        if (!hasPermission()) return emptyList()

        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyList()
            val endTime = System.currentTimeMillis()
            val startTime = endTime - durationMs
            val events = usageStatsManager.queryEvents(startTime, endTime) ?: return emptyList()

            val result = mutableListOf<ForegroundAppEvent>()
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    val cal = Calendar.getInstance().apply { timeInMillis = event.timeStamp }
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    result.add(ForegroundAppEvent(event.packageName, hour, event.timeStamp))
                }
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }
}

/**
 * Represents a local foreground app transition event.
 */
data class ForegroundAppEvent(
    val packageName: String,
    val hourOfDay: Int,
    val timestamp: Long
)
