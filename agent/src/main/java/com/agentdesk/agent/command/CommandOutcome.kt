package com.agentdesk.agent.command

/**
 * Final outcome of a command after full pipeline execution
 * (RuleEngine → PolicyEngine → ToolExecutor).
 */
sealed class CommandOutcome {
    /** Command finished; [message] is the user-facing result string. */
    data class Executed(val message: String, val intentName: String) : CommandOutcome()

    /** Policy requires explicit user confirmation before execution. */
    data class NeedsUserConfirmation(val command: Command, val prompt: String) : CommandOutcome()

    /** Blocked by the PolicyEngine — never executed. */
    data class Rejected(val reason: String) : CommandOutcome()

    /** Input did not match any known intent. */
    data class Unknown(val rawInput: String) : CommandOutcome()
}
