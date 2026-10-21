package com.agentdesk.tools.impl

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.agentdesk.core.common.model.RiskLevel
import com.agentdesk.tools.executor.ToolRequest
import com.agentdesk.tools.executor.ToolResult
import com.agentdesk.tools.registry.Tool
import com.agentdesk.tools.registry.ToolCategory
import com.agentdesk.tools.registry.ToolDefinition
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inserts calendar events into the device calendar.
 *
 * Execution Paths:
 * 1. Direct ContentResolver fast-path: When [Manifest.permission.WRITE_CALENDAR] is granted and
 *    a writable calendar exists, inserts directly into [CalendarContract.Events.CONTENT_URI].
 * 2. Intent fallback: When permission is not granted or ContentResolver insertion fails,
 *    launches [Intent.ACTION_INSERT] so the user reviews the event in their native calendar app.
 */
@Singleton
class CalendarEventTool private constructor(
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") marker: Any?
) : Tool {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context, null)

    internal constructor() : this(null, null)

    override val definition = ToolDefinition(
        id = "calendar_event",
        name = "Calendar Event",
        description = "Create calendar events via direct calendar insertion or system calendar application.",
        category = ToolCategory.SYSTEM,
        riskLevel = RiskLevel.MEDIUM,
        requiresConfirmation = true
    )

    override suspend fun execute(request: ToolRequest): ToolResult {
        val params = request.parameters
        val title = params["title"]?.trim()
        if (title.isNullOrBlank()) {
            return ToolResult.Error("create_calendar_event", "Event title must not be blank.")
        }
        if (title.length > 256) {
            return ToolResult.Error("create_calendar_event", "Event title exceeds maximum allowed length of 256 chars.")
        }

        val description = params["description"] ?: params["notes"] ?: ""
        val location = params["location"] ?: ""
        val beginTimeMillis = params["beginTimeMillis"]?.toLongOrNull() ?: params["start_time"]?.toLongOrNull()
        val endTimeMillis = params["endTimeMillis"]?.toLongOrNull() ?: params["end_time"]?.toLongOrNull()

        if (beginTimeMillis != null && endTimeMillis != null && endTimeMillis < beginTimeMillis) {
            return ToolResult.Error(
                "create_calendar_event",
                "End time ($endTimeMillis) cannot be before begin time ($beginTimeMillis)."
            )
        }

        val ctx = context
        if (ctx != null) {
            // Direct ContentResolver insertion fast-path if WRITE_CALENDAR is granted
            val hasWritePermission = try {
                ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.WRITE_CALENDAR
                ) == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }

            if (hasWritePermission) {
                val directResult = insertDirectly(ctx, title, description, location, beginTimeMillis, endTimeMillis)
                if (directResult != null) {
                    return directResult
                }
            }
        }

        // Fallback: System UI Intent.ACTION_INSERT
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            if (description.isNotBlank()) {
                putExtra(CalendarContract.Events.DESCRIPTION, description)
            }
            if (location.isNotBlank()) {
                putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            }
            if (beginTimeMillis != null) {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTimeMillis)
            }
            if (endTimeMillis != null) {
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTimeMillis)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            ctx?.startActivity(intent)
            ToolResult.Success("create_calendar_event", "Calendar event '$title' drafted. Tap Save to schedule.")
        } catch (e: Exception) {
            ToolResult.Error("create_calendar_event", "Failed to launch calendar intent: ${e.message}")
        }
    }

    private fun insertDirectly(
        ctx: Context,
        title: String,
        description: String,
        location: String,
        beginTimeMillis: Long?,
        endTimeMillis: Long?
    ): ToolResult? {
        return try {
            val calendarId = getWritableCalendarId(ctx) ?: return null
            val start = beginTimeMillis ?: System.currentTimeMillis()
            val end = endTimeMillis ?: (start + 3600_000L) // Default 1 hour duration

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                if (description.isNotBlank()) {
                    put(CalendarContract.Events.DESCRIPTION, description)
                }
                if (location.isNotBlank()) {
                    put(CalendarContract.Events.EVENT_LOCATION, location)
                }
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                ToolResult.Success("create_calendar_event", "Calendar event '$title' created directly in calendar.")
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun getWritableCalendarId(ctx: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        return try {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                var fallbackId: Long? = null
                val idIdx = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val primaryIdx = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)

                while (cursor.moveToNext()) {
                    if (idIdx >= 0) {
                        val id = cursor.getLong(idIdx)
                        val isPrimary = if (primaryIdx >= 0) cursor.getInt(primaryIdx) == 1 else false
                        if (isPrimary) return id
                        if (fallbackId == null) fallbackId = id
                    }
                }
                fallbackId
            }
        } catch (_: Throwable) {
            null
        }
    }
}
