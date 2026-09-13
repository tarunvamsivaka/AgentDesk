package com.agentdesk.tools.registry

import com.agentdesk.core.common.model.RiskLevel

/** Broad category grouping for tool classification and UI display. */
enum class ToolCategory {
    SYSTEM,
    CONTENT,
    COMMUNICATION,
    NAVIGATION,
    DEVICE
}

/**
 * Describes a single tool that the AgentDesk agent can invoke.
 *
 * @param id                  Unique snake_case identifier, matches intent constants.
 * @param name                Human-readable display name.
 * @param description         Short description shown in the UI.
 * @param category            Broad grouping for this tool.
 * @param riskLevel           Inherent risk — drives confirmation requirements.
 * @param requiresConfirmation Whether the user must explicitly approve before execution.
 */
data class ToolDefinition(
    val id: String,
    val name: String,
    val description: String,
    val category: ToolCategory,
    val riskLevel: RiskLevel,
    val requiresConfirmation: Boolean
)

/** Central registry of all tools available in the MVP. */
object ToolRegistry {
    val ALL: List<ToolDefinition> = listOf(
        ToolDefinition(
            id = "set_alarm",
            name = "Set Alarm",
            description = "Set a device alarm at a specified time.",
            category = ToolCategory.SYSTEM,
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false
        ),
        ToolDefinition(
            id = "set_timer",
            name = "Set Timer",
            description = "Start a countdown timer for a specified duration.",
            category = ToolCategory.SYSTEM,
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false
        ),
        ToolDefinition(
            id = "create_note",
            name = "Create Note",
            description = "Save a text note to local storage.",
            category = ToolCategory.CONTENT,
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false
        ),
        ToolDefinition(
            id = "open_app",
            name = "Open App",
            description = "Launch an installed application by name.",
            category = ToolCategory.SYSTEM,
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false
        ),
        ToolDefinition(
            id = "create_calendar_event",
            name = "Create Calendar Event",
            description = "Add an event to the system calendar (opens Calendar app).",
            category = ToolCategory.CONTENT,
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false
        ),
        ToolDefinition(
            id = "draft_sms",
            name = "Draft SMS",
            description = "Open the SMS app with a pre-filled draft. User must tap Send.",
            category = ToolCategory.COMMUNICATION,
            riskLevel = RiskLevel.MEDIUM,
            requiresConfirmation = true
        ),
        ToolDefinition(
            id = "open_dialer",
            name = "Open Dialer",
            description = "Open the phone dialer with a number pre-filled. User must tap Call.",
            category = ToolCategory.COMMUNICATION,
            riskLevel = RiskLevel.MEDIUM,
            requiresConfirmation = true
        ),
        ToolDefinition(
            id = "library_search",
            name = "Library Search",
            description = "Search local files and documents indexed in the knowledge library.",
            category = ToolCategory.CONTENT,
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false
        ),
        ToolDefinition(
            id = "web_search",
            name = "Web Search",
            description = "Open the browser with a search query (no background data access).",
            category = ToolCategory.NAVIGATION,
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false
        ),
        ToolDefinition(
            id = "navigate_maps",
            name = "Navigate",
            description = "Open Google Maps with a destination for turn-by-turn navigation.",
            category = ToolCategory.NAVIGATION,
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false
        ),
        ToolDefinition(
            id = "device_health",
            name = "Device Health",
            description = "Show battery, storage, and memory summary in the Health screen.",
            category = ToolCategory.DEVICE,
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false
        )
    )

    fun findById(id: String): ToolDefinition? = ALL.firstOrNull { it.id == id }
}
