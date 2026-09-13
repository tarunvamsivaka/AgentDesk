package com.agentdesk.tools.impl

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
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
 * Sets a device alarm via [AlarmClock.ACTION_SET_ALARM] with hour/minute extras.
 *
 * Risk: LOW — no user confirmation required. The Clock app still shows its
 * own UI so the user sees exactly what was scheduled.
 */
@Singleton
class AlarmTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val definition = ToolDefinition(
        id = "set_alarm",
        name = "Set Alarm",
        description = "Set a device alarm at a specified time.",
        category = ToolCategory.SYSTEM,
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false
    )

    override suspend fun execute(request: ToolRequest): ToolResult {
        val rawTime = request.parameters["time"]
            ?: return ToolResult.Error(id, "No time specified.")
        val (hour, minute) = parseTime(rawTime)
            ?: return ToolResult.Error(id, "Couldn't understand the time \"$rawTime\".")

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "AgentDesk Alarm")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult.Success(id, "Done. Alarm set for ${formatDisplay(hour, minute)}.")
    }

    private val id get() = definition.id

    // ---------------------------------------------------------------------------

    /** Parses "7", "7am", "7:30 pm", "19:45" → (hour24, minute). Null if unparseable. */
    private fun parseTime(raw: String): Pair<Int, Int>? {
        val m = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(raw.trim().lowercase())
            ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        when (m.groupValues[3]) {
            "am" -> if (hour == 12) hour = 0
            "pm" -> if (hour < 12) hour += 12
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    /** Formats 24h time as e.g. "7:05 AM". */
    private fun formatDisplay(hour: Int, minute: Int): String {
        val suffix = if (hour < 12) "AM" else "PM"
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "%d:%02d %s".format(h12, minute, suffix)
    }
}
