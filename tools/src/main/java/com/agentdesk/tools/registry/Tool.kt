package com.agentdesk.tools.registry

import com.agentdesk.tools.executor.ToolRequest
import com.agentdesk.tools.executor.ToolResult

/**
 * Contract implemented by every concrete tool.
 *
 * A tool maps a [ToolRequest] onto an Android Intent or a local Room write
 * and returns a [ToolResult]. Tools never self-authorize — PolicyEngine
 * always runs before a tool is invoked.
 */
interface Tool {
    /** Registry metadata: id, risk level, confirmation requirement. */
    val definition: ToolDefinition

    suspend fun execute(request: ToolRequest): ToolResult
}
