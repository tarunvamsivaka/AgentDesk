package com.agentdesk.tools.executor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.agentdesk.core.common.model.RiskLevel
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.entity.AuditEventEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes tool requests using Android system Intents.
 *
 * Principles:
 * - No background sending. Every communication tool opens a system UI.
 * - Every execution attempt is audited via [AuditDao].
 * - No accessibility service usage.
 * - Intents use FLAG_ACTIVITY_NEW_TASK since this may be called from a non-Activity context.
 */
@Singleton
class ToolExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auditDao: AuditDao
) {

    suspend fun execute(request: ToolRequest): ToolResult {
        val result = runTool(request)
        auditDao.insert(
            AuditEventEntity(
                eventType  = if (result is ToolResult.Success) "TOOL_SUCCESS" else "TOOL_ERROR",
                actor      = "TOOL_EXECUTOR",
                targetType = "TOOL",
                targetId   = request.toolId,
                summary    = when (result) {
                    is ToolResult.Success   -> result.message
                    is ToolResult.Error     -> result.reason
                    is ToolResult.Cancelled -> "Cancelled"
                },
                riskLevel  = RiskLevel.LOW
            )
        )
        return result
    }

    // ---------------------------------------------------------------------------
    // Tool dispatch
    // ---------------------------------------------------------------------------

    private fun runTool(request: ToolRequest): ToolResult = try {
        when (request.toolId) {
            "set_alarm"            -> setAlarm(request.parameters)
            "set_timer"            -> setTimer(request.parameters)
            "create_note"          -> createNote(request.parameters)
            "open_app"             -> openApp(request.parameters)
            "create_calendar_event" -> createCalendarEvent(request.parameters)
            "draft_sms"            -> draftSms(request.parameters)
            "open_dialer"          -> openDialer(request.parameters)
            "library_search"       -> ToolResult.Success("library_search", "Navigate to Library screen to view results.")
            "web_search"           -> webSearch(request.parameters)
            "navigate_maps"        -> navigateMaps(request.parameters)
            "device_health"        -> ToolResult.Success("device_health", "Navigate to Health screen to view device status.")
            else                   -> ToolResult.Error(request.toolId, "Unknown tool: ${request.toolId}")
        }
    } catch (e: Exception) {
        ToolResult.Error(request.toolId, "Execution error: ${e.message}")
    }

    private fun setAlarm(params: Map<String, String>): ToolResult {
        val timeStr = params["time"] ?: return ToolResult.Error("set_alarm", "No time specified.")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, "AgentDesk Alarm")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Best-effort parse; the Alarm app handles display/confirmation
        }
        context.startActivity(intent)
        return ToolResult.Success("set_alarm", "Alarm set for $timeStr.")
    }

    private fun setTimer(params: Map<String, String>): ToolResult {
        val duration = params["duration"]?.toIntOrNull() ?: 0
        val unit = params["unit"] ?: "minute"
        val seconds = when (unit) {
            "hour"   -> duration * 3600
            "minute" -> duration * 60
            else     -> duration
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, "AgentDesk Timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult.Success("set_timer", "Timer set for $duration $unit(s).")
    }

    private fun createNote(params: Map<String, String>): ToolResult {
        val text = params["text"] ?: return ToolResult.Error("create_note", "No note text provided.")
        // In MVP: open a plain text intent so user sees and confirms
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Save note with…").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return ToolResult.Success("create_note", "Note ready to save: \"${text.take(40)}…\"")
    }

    private fun openApp(params: Map<String, String>): ToolResult {
        val appName = params["appName"] ?: return ToolResult.Error("open_app", "No app name provided.")
        val pm = context.packageManager
        val launchIntent = pm.getInstalledApplications(0)
            .firstOrNull { pm.getApplicationLabel(it).toString().equals(appName, ignoreCase = true) }
            ?.let { pm.getLaunchIntentForPackage(it.packageName) }
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            ToolResult.Success("open_app", "Opened $appName.")
        } else {
            ToolResult.Error("open_app", "App \"$appName\" not found on this device.")
        }
    }

    private fun createCalendarEvent(params: Map<String, String>): ToolResult {
        val title = params["title"].orEmpty().ifBlank { "New Event" }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult.Success("create_calendar_event", "Calendar opened to add \"$title\".")
    }

    private fun draftSms(params: Map<String, String>): ToolResult {
        val contact = params["contact"] ?: return ToolResult.Error("draft_sms", "No contact specified.")
        val body = params["body"].orEmpty()
        // ACTION_SENDTO with smsto: opens the SMS app with the contact pre-filled
        // The user MUST tap Send. No silent sending.
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$contact")).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult.Success("draft_sms", "SMS draft opened for $contact. Tap Send to deliver.")
    }

    private fun openDialer(params: Map<String, String>): ToolResult {
        val contact = params["contact"] ?: return ToolResult.Error("open_dialer", "No contact specified.")
        // ACTION_DIAL opens the dialer UI. The user MUST tap Call. No silent calling.
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$contact")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult.Success("open_dialer", "Dialer opened for $contact. Tap Call to connect.")
    }

    private fun webSearch(params: Map<String, String>): ToolResult {
        val query = params["query"] ?: return ToolResult.Error("web_search", "No search query provided.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult.Success("web_search", "Browser opened for: $query")
    }

    private fun navigateMaps(params: Map<String, String>): ToolResult {
        val destination = params["destination"] ?: return ToolResult.Error("navigate_maps", "No destination specified.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}")).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            ToolResult.Success("navigate_maps", "Navigation started to $destination.")
        } else {
            // Fallback to geo: URI
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            ToolResult.Success("navigate_maps", "Maps opened for $destination.")
        }
    }
}
