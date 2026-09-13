package com.agentdesk.tools.executor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.core.common.tools.ToolRunResult
import com.agentdesk.core.persistence.dao.FtsSearchDao
import com.agentdesk.core.persistence.dao.NoteDao
import com.agentdesk.core.persistence.entity.NoteEntity
import com.agentdesk.tools.registry.Tool
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches tool requests to registered [Tool] implementations, falling back
 * to built-in Android system Intent handlers and local persistence handlers
 * for tools without a Tool class yet.
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
    private val tools: Map<String, @JvmSuppressWildcards Tool>,
    private val noteDao: NoteDao,
    private val ftsSearchDao: FtsSearchDao
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

    /** Built-in intent / persistence handlers for MVP tools that have no dedicated [Tool] class. */
    private suspend fun legacyRun(request: ToolRequest): ToolResult = when (request.toolId) {
        "set_alarm"             -> setAlarm(request.parameters)
        "set_timer"             -> setTimer(request.parameters)
        "create_note"           -> createNote(request.parameters)
        "create_calendar_event" -> createCalendarEvent(request.parameters)
        "draft_sms"             -> draftSms(request.parameters)
        "open_dialer"           -> openDialer(request.parameters)
        "library_search"        -> librarySearch(request.parameters)
        "web_search"            -> webSearch(request.parameters)
        "navigate_maps"         -> navigateMaps(request.parameters)
        "device_health"         -> ToolResult.Success("device_health", "Navigate to Health screen to view device status.")
        else                    -> ToolResult.Error(request.toolId, "Unknown tool: ${request.toolId}")
    }

    // ---------------------------------------------------------------------------
    // Notes — local persistence (create_note)
    // ---------------------------------------------------------------------------

    private suspend fun createNote(params: Map<String, String>): ToolResult {
        val text = params["text"]?.trim()
        if (text.isNullOrBlank()) {
            return ToolResult.Error("create_note", "No note text provided.")
        }
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val title = (firstLine ?: text).take(MAX_NOTE_TITLE_CHARS)
        noteDao.insert(
            NoteEntity(
                id = UUID.randomUUID().toString(),
                title = title,
                body = text,
                createdAt = System.currentTimeMillis()
            )
        )
        return ToolResult.Success("create_note", "Note saved: '$title'")
    }

    // ---------------------------------------------------------------------------
    // Library search — real FTS lookup (library_search)
    // ---------------------------------------------------------------------------

    private suspend fun librarySearch(params: Map<String, String>): ToolResult {
        val query = params["query"]?.trim()
        if (query.isNullOrBlank()) {
            return ToolResult.Error("library_search", "No search query provided.")
        }
        val results = ftsSearchDao.safeSearch(query, limit = LIBRARY_SEARCH_LIMIT)
        if (results.isEmpty()) {
            return ToolResult.Success("library_search", "No local documents match '$query'.")
        }
        val message = buildString {
            append("Found ${results.size} results:\n")
            results.forEachIndexed { index, chunk ->
                val snippet = chunk.content.trim().replace('\n', ' ').take(SNIPPET_LENGTH)
                append("${index + 1}. $snippet\n")
            }
        }.trim()
        return ToolResult.Success("library_search", message)
    }

    // ---------------------------------------------------------------------------
    // Alarm — time parsing (set_alarm)
    // ---------------------------------------------------------------------------

    private fun setAlarm(params: Map<String, String>): ToolResult {
        val rawTime = params["time"]
        val parsed = rawTime?.let { parseTimeToHourMinute(it) }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            if (parsed != null) {
                putExtra(AlarmClock.EXTRA_HOUR, parsed.first)
                putExtra(AlarmClock.EXTRA_MINUTES, parsed.second)
            }
            putExtra(AlarmClock.EXTRA_MESSAGE, "AgentDesk Alarm")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return if (parsed != null) {
            ToolResult.Success("set_alarm", "Done. Alarm set for ${formatTime(parsed.first, parsed.second)}.")
        } else {
            // Parsing failed — fall back to opening the alarm app for manual input.
            ToolResult.Success("set_alarm", "Opened the alarm app. Please set the time manually.")
        }
    }

    // ---------------------------------------------------------------------------
    // Timers / calendar / SMS / dialer / web / maps
    // ---------------------------------------------------------------------------

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

    companion object {
        private const val MAX_NOTE_TITLE_CHARS = 30
        private const val LIBRARY_SEARCH_LIMIT = 5
        private const val SNIPPET_LENGTH = 120
    }
}

/**
 * Parses a time string into 24-hour (hour, minute).
 *
 * Supported formats: "7", "7am", "7:30 pm", "14:00", "12am", "12pm".
 * Returns null if the string cannot be parsed into a valid time.
 */
internal fun parseTimeToHourMinute(timeStr: String): Pair<Int, Int>? {
    val match = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(timeStr.trim().lowercase())
        ?: return null
    var hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: 0
    when (match.groupValues[3]) {
        "am" -> if (hour == 12) hour = 0
        "pm" -> if (hour < 12) hour += 12
    }
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour to minute
}

/** Formats a 24-hour time as e.g. "7:05 AM". */
private fun formatTime(hour: Int, minute: Int): String {
    val suffix = if (hour < 12) "AM" else "PM"
    val h12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "%d:%02d %s".format(h12, minute, suffix)
}