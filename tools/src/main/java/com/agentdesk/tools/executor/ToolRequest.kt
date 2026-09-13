package com.agentdesk.tools.executor

/**
 * Request to execute a specific tool.
 *
 * @param toolId    Matches [com.agentdesk.tools.registry.ToolDefinition.id].
 * @param parameters Key-value pairs of resolved entities from the command.
 * @param commandId  Opaque ID linking back to the originating [CommandRecordEntity].
 */
data class ToolRequest(
    val toolId: String,
    val parameters: Map<String, String> = emptyMap(),
    val commandId: Long = 0L
)

/** Result of executing a tool. */
sealed class ToolResult {
    /** Tool executed successfully. [message] is shown to the user. */
    data class Success(
        val toolId: String,
        val message: String,
        val data: Map<String, String> = emptyMap()
    ) : ToolResult()

    /** Tool execution failed or was blocked. */
    data class Error(
        val toolId: String,
        val reason: String
    ) : ToolResult()

    /** User cancelled the action before execution. */
    data class Cancelled(val toolId: String) : ToolResult()
}
