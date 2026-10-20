package com.agentdesk.agent.command

/**
 * Final outcome of a command after full pipeline execution
 * (RuleEngine → PolicyEngine → ToolExecutor).
 */
sealed class CommandOutcome {
    /** Command finished; [message] is the user-facing result string. */
    data class Executed(
        val message: String,
        val intentName: String,
        val stepResults: List<Pair<String, String>> = emptyList()
    ) : CommandOutcome()

    /** Policy requires explicit user confirmation before execution. */
    data class NeedsUserConfirmation(
        val command: Command,
        val prompt: String,
        val taskId: Long? = null,
        val stepIndex: Int = 0
    ) : CommandOutcome()

    /** Blocked by the PolicyEngine — never executed. */
    data class Rejected(val reason: String) : CommandOutcome()

    /** Input did not match any known intent. */
    data class Unknown(val rawInput: String) : CommandOutcome()

    /** Tier 3 fallback: ambiguous, out-of-domain, or throttled query offering guided chips. */
    data class Clarification(val rawInput: String, val chips: List<String>) : CommandOutcome()
}
