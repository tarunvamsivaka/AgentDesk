package com.agentdesk.agent.command

import com.agentdesk.core.common.model.CommandSource
import com.agentdesk.core.common.model.RiskLevel

/**
 * A parsed, validated command ready for execution.
 *
 * @param rawInput The original text as entered by the user.
 * @param normalizedInput Lowercased, trimmed version for matching.
 * @param intentName Dot-separated intent identifier (e.g., "system.set_alarm").
 * @param entities Extracted parameters (e.g., {"time": "7:00", "label": "Meeting"}).
 * @param confidence Confidence score from the rule engine [0.0..1.0].
 * @param source Where this command originated.
 * @param riskLevel Risk classification from the PolicyEngine.
 * @param requiresConfirmation Whether the user must confirm before execution.
 */
data class Command(
    val rawInput: String,
    val normalizedInput: String,
    val intentName: String,
    val entities: Map<String, String> = emptyMap(),
    val confidence: Float = 0f,
    val source: CommandSource = CommandSource.USER_CHAT,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val requiresConfirmation: Boolean = false
)

/**
 * Result of processing a command through the gateway.
 */
sealed class CommandResult {
    data class Accepted(val command: Command) : CommandResult()
    data class NeedsConfirmation(val command: Command, val prompt: String) : CommandResult()
    data class Rejected(val reason: String, val command: Command) : CommandResult()
    data class Unknown(val rawInput: String) : CommandResult()
}
