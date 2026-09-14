package com.agentdesk.tools.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Launches an installed app by its display label using PackageManager.
 *
 * Package visibility is provided by the <queries> MAIN/LAUNCHER entry in the
 * app manifest. Risk: LOW — no user confirmation required.
 */
@Singleton
class AppLaunchTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val definition = ToolDefinition(
        id = "open_app",
        name = "Open App",
        description = "Launch an installed application by name.",
        category = ToolCategory.SYSTEM,
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false
    )

    override suspend fun execute(request: ToolRequest): ToolResult {
        val appName = request.parameters["appName"]?.trim()
        if (appName.isNullOrBlank()) {
            return ToolResult.Error(definition.id, "No app name provided.")
        }

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = pm.queryIntentActivities(launcherIntent, 0)

        val match = installed.firstOrNull {
            it.loadLabel(pm).toString().equals(appName, ignoreCase = true)
        } ?: installed.firstOrNull {
            it.loadLabel(pm).toString().contains(appName, ignoreCase = true)
        }

        if (match == null) {
            // Fuzzy-match fallback: if the user asked for maps/navigation, open
            // Maps home via a geo: URI instead of erroring.
            val lower = appName.lowercase()
            if (lower.contains("map") || lower.contains("navigation")) {
                val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(geo)
                return ToolResult.Success(definition.id, "Opened Maps home for \"$appName\".")
            }
            return ToolResult.Error(
                definition.id,
                "App \"$appName\" not found on this device."
            )
        }

        val label = match.loadLabel(pm).toString()
        val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return ToolResult.Error(definition.id, "Couldn't launch $label.")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return ToolResult.Success(definition.id, "Opening $label.")
    }
}
