package com.agentdesk.core.common.tools

/**
 * JVM-safe seam between the command pipeline (:agent) and the tool
 * execution layer (:tools).
 *
 * Implemented by [com.agentdesk.tools.executor.ToolExecutor]. Lives in
 * :core:common so neither module gains a new dependency and the gateway
 * can be unit-tested on the JVM with a fake.
 */
interface ToolRunner {

    /** Execute [toolId] with [parameters]. [commandId] links back to the CommandRecord. */
    suspend fun execute(
        toolId: String,
        parameters: Map<String, String>,
        commandId: Long
    ): ToolRunResult
}

/** Minimal, Android-free result of a tool invocation. */
data class ToolRunResult(
    val toolId: String,
    val success: Boolean,
    val message: String
)
