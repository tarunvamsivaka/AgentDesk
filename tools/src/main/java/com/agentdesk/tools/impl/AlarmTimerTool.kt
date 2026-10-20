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
 * Handles alarm and countdown timer intents using standard Android AlarmClock actions.
 *
 * Security: Never executes silent alarms without system UI unless explicit skipUi
 * parameter is validated. All intents include FLAG_ACTIVITY_NEW_TASK.
 */
@Singleton
class AlarmTimerTool private constructor(
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") marker: Any?
) : Tool {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context, null)

    internal constructor() : this(null, null)

    override val definition = ToolDefinition(
        id = "alarm_timer",
        name = "Alarm & Timer",
        description = "Set alarms and countdown timers using standard Android intents.",
        category = ToolCategory.SYSTEM,
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false
    )

    override suspend fun execute(request: ToolRequest): ToolResult {
        return when (request.toolId) {
            "set_alarm" -> setAlarm(request.parameters)
            "set_timer" -> setTimer(request.parameters)
            else -> {
                if (request.parameters.containsKey("length") || request.parameters.containsKey("duration")) {
                    setTimer(request.parameters)
                } else {
                    setAlarm(request.parameters)
                }
            }
        }
    }

    private fun setAlarm(params: Map<String, String>): ToolResult {
        val hourStr = params["hour"]
        val minuteStr = params["minute"] ?: "0"
        val message = params["message"] ?: params["label"] ?: "Alarm"
        val skipUi = params["skip_ui"] == "true" || params["skipUi"] == "true"

        if (hourStr.isNullOrBlank()) {
            return ToolResult.Error("set_alarm", "Missing 'hour' parameter for set_alarm.")
        }

        val hour = hourStr.toIntOrNull()
        if (hour == null || hour !in 0..23) {
            return ToolResult.Error("set_alarm", "Hour must be between 0 and 23. Received: '$hourStr'.")
        }

        val minute = minuteStr.toIntOrNull()
        if (minute == null || minute !in 0..59) {
            return ToolResult.Error("set_alarm", "Minute must be between 0 and 59. Received: '$minuteStr'.")
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context?.startActivity(intent)
            ToolResult.Success("set_alarm", "Alarm set for %02d:%02d (%s).".format(hour, minute, message))
        } catch (e: Exception) {
            ToolResult.Error("set_alarm", "Failed to launch alarm intent: ${e.message}")
        }
    }

    private fun setTimer(params: Map<String, String>): ToolResult {
        val lengthStr = params["length"] ?: params["lengthSeconds"] ?: params["duration"]
        val message = params["message"] ?: params["label"] ?: "Timer"
        val skipUi = params["skip_ui"] == "true" || params["skipUi"] == "true"

        if (lengthStr.isNullOrBlank()) {
            return ToolResult.Error("set_timer", "Missing 'length' parameter (duration in seconds) for set_timer.")
        }

        val lengthSeconds = lengthStr.toIntOrNull()
        if (lengthSeconds == null || lengthSeconds !in 1..86400) {
            return ToolResult.Error(
                "set_timer",
                "Timer length must be between 1 and 86400 seconds (24h). Received: '$lengthStr'."
            )
        }

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, lengthSeconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context?.startActivity(intent)
            ToolResult.Success("set_timer", "Timer set for %d seconds (%s).".format(lengthSeconds, message))
        } catch (e: Exception) {
            ToolResult.Error("set_timer", "Failed to launch timer intent: ${e.message}")
        }
    }
}
