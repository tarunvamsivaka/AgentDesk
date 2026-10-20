package com.agentdesk.tools.impl

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
 * Handles safe navigation to device system settings via an explicit allowlist.
 *
 * Security: Rejects any arbitrary or unallowlisted action string to prevent Intent redirection
 * or opening hidden/privileged settings panels.
 */
@Singleton
class SystemSettingsTool private constructor(
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") marker: Any?
) : Tool {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context, null)

    internal constructor() : this(null, null)

    override val definition = ToolDefinition(
        id = "system_settings",
        name = "System Settings",
        description = "Open standard device settings panels (WiFi, Bluetooth, Sound, Display, etc.).",
        category = ToolCategory.SYSTEM,
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false
    )

    companion object {
        val ALLOWLISTED_SETTINGS: Map<String, String> = mapOf(
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "wireless" to Settings.ACTION_WIFI_SETTINGS,
            "internet" to Settings.ACTION_WIRELESS_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "volume" to Settings.ACTION_SOUND_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "brightness" to Settings.ACTION_DISPLAY_SETTINGS,
            "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
            "power" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
            "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "date" to Settings.ACTION_DATE_SETTINGS,
            "locale" to Settings.ACTION_LOCALE_SETTINGS,
            "language" to Settings.ACTION_LOCALE_SETTINGS,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            "privacy" to Settings.ACTION_PRIVACY_SETTINGS,
            "settings" to Settings.ACTION_SETTINGS
        )
    }

    override suspend fun execute(request: ToolRequest): ToolResult {
        val settingKey = request.parameters["setting"]
            ?: request.parameters["panel"]
            ?: request.parameters["target"]
            ?: "settings"

        val normalized = settingKey.trim().lowercase()
        val action = ALLOWLISTED_SETTINGS[normalized]
            ?: if (ALLOWLISTED_SETTINGS.values.contains(settingKey)) settingKey else null

        if (action == null) {
            return ToolResult.Error(
                "system_settings",
                "Setting '$settingKey' is not in the permitted allowlist. Permitted: ${ALLOWLISTED_SETTINGS.keys.joinToString(", ")}."
            )
        }

        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context?.startActivity(intent)
            ToolResult.Success("system_settings", "Opened $normalized settings.")
        } catch (e: Exception) {
            ToolResult.Error("system_settings", "Failed to open settings: ${e.message}")
        }
    }
}
