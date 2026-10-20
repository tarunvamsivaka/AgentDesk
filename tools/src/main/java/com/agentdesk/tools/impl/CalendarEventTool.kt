package com.agentdesk.tools.impl

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.agentdesk.core.common.model.RiskLevel
import com.agentdesk.tools.executor.ToolRequest
import com.agentdesk.tools.executor.ToolResult
import com.agentdesk.tools.registry.Tool
import com.agentdesk.tools.registry.ToolCategory
import com.agentdesk.tools.registry.ToolDefinition
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inserts calendar events into the device calendar via standard CalendarContract intents.
 *
 * Security: Uses Intent.ACTION_INSERT so the user reviews the event in their native calendar
 * app before saving. Gated by RiskLevel.MEDIUM in PolicyEngine.
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
        description = "Create calendar events via the system calendar application.",
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
            context?.startActivity(intent)
            ToolResult.Success("create_calendar_event", "Calendar event '$title' drafted. Tap Save to schedule.")
        } catch (e: Exception) {
            ToolResult.Error("create_calendar_event", "Failed to launch calendar intent: ${e.message}")
        }
    }
}
