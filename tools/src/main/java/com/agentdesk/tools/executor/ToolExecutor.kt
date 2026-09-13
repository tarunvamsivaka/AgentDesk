package com.agentdesk.tools.executor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.core.common.tools.ToolRunResult
import com.agentdesk.tools.registry.Tool
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches tool requests to registered [Tool] implementations, falling back
 * to built-in Android system Intent handlers for tools without a Tool class yet.
 *
 * Principles:
 * - No background sending. Every communication tool opens a system UI.
 * - Auditing of tool invocations is owned by CommandGateway (:agent).
 * - No accessibility service usage.
 * - Intents use FLAG_ACTIVITY_NEW_TASK since this may be called from a non-Activity context.
 */
@Singleton
class ToolExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tools: Map<String, @JvmSuppressWildcards Tool>
) : ToolRunner {

    override suspend fun execute(
        toolId: String,
        parameters: Map<String, String>,
        commandId: Long
    ): ToolRunResult {
        val result = runTool(ToolRequest(toolId, parameters, commandId))
        return when (result) {
            is ToolResult.Success   -> ToolRunResult(result.toolId, true, result.message)
            is ToolResult.Error     -> ToolRunResult(result.toolId, false, result.reason)
            is ToolResult.Cancelled -> ToolRunResult(result.toolId, false, "Cancelled")
        }
    }

    // ---------------------------------------------------------------------------
    // Tool dispatch
    // ---------------------------------------------------------------------------

    private suspend fun runTool(request: ToolRequest): ToolResult = try {
        tools[request.toolId]?.execute(request) ?: legacyRun(request)
    } catch (e: Exception) {
        ToolResult.Error(request.toolId, "Execution error: ${e.message}")
    }

    /** Built-in intent handlers for MVP tools that have no dedicated [Tool] class yet. */
    private fun legacyRun(request: ToolRequest): ToolResult = when (request.toolId) {
        "set_timer"             -> setTimer(request.parameters)
        "create_calendar_event" -> createCalendarEvent(request.parameters)
        "draft_sms"             -> draftSms(request.parameters)
        "open_dialer"           -> openDialer(request.parameters)
        "library_search"        -> ToolResult.Success("library_search", "Navigate to Library screen to view results.")
        "web_search"            -> webSearch(request.parameters)
        "navigate_maps"         -> navigateMaps(request.parameters)
        "device_health"         -> ToolResult.Success("device_health", "Navigate to Health screen to view device status.")
        else                    -> ToolResult.Error(request.toolId, "Unknown tool: ${request.toolId}")
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
