package com.agentdesk.agent.command

import com.agentdesk.agent.policy.PolicyEngine
import com.agentdesk.agent.rule.Intents
import com.agentdesk.agent.rule.RuleEngine
import com.agentdesk.core.common.model.CommandSource
import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.dao.CommandDao
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.CommandRecordEntity
import com.agentdesk.core.device.ResourceGovernor
import com.agentdesk.core.security.Redactor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point for all incoming commands.
 *
 * Flow (submitText):
 * 1. Insert CommandRecord with status RECEIVED
 * 2. RuleEngine.evaluate() → intent + entities
 * 3. PolicyEngine.evaluate() → allow/deny (PolicyEngine always wins)
 * 4. ToolRunner.execute() for accepted commands
 * 5. One AuditEvent per tool invocation
 * 6. CommandRecord status/latency update
 * 7. Return a user-facing result to the caller
 *
 * All work runs on the injected background dispatcher — no main-thread work.
 * The PolicyEngine always has final authority. If it denies, execution never proceeds.
 */
@Singleton
class CommandGateway @Inject constructor(
    private val ruleEngine: RuleEngine,
    private val policyEngine: PolicyEngine,
    private val toolRunner: ToolRunner,
    private val commandDao: CommandDao,
    private val auditDao: AuditDao,
    private val redactor: Redactor,
    private val dispatcher: CoroutineDispatcher,
    private val llmManager: com.agentdesk.agent.intent.OnDeviceLlmManager = com.agentdesk.agent.intent.DefaultOnDeviceLlmManager(),
    private val resourceGovernor: ResourceGovernor? = null,
    private val habitPredictionEngine: com.agentdesk.agent.habit.HabitPredictionEngine? = null
) {

    suspend fun submitText(
        rawInput: String,
        source: CommandSource = CommandSource.USER_CHAT,
        threadId: Long? = null,
        thermalThrottled: Boolean = false
    ): CommandOutcome = withContext(dispatcher) {
        val startedAt = System.currentTimeMillis()
        val normalized = rawInput.trim().lowercase()

        // Step 1: Persist command record as RECEIVED (rawInput is redacted — no PII at rest)
        val commandId = commandDao.insert(
            CommandRecordEntity(
                rawInput = redactor.redact(rawInput),
                normalizedInput = redactor.redact(normalized),
                source = source,
                threadId = threadId
            )
        )

        if (normalized.isBlank()) {
            finish(commandId, "unknown", STATUS_UNKNOWN, startedAt)
            return@withContext CommandOutcome.Unknown(rawInput)
        }

        // Step 2: 4-Tier Intent Recognition Hierarchy

        // Tier 0: Regex RuleEngine (<5ms)
        var intentName = ""
        var entities = emptyMap<String, String>()
        var confidence = 0f

        val ruleResult = ruleEngine.evaluate(normalized)
        if (ruleResult != null && ruleResult.confidence >= CONFIDENCE_THRESHOLD) {
            intentName = ruleResult.intentName
            entities = ruleResult.entities
            confidence = ruleResult.confidence
        } else {
            // Contextual Habit Boosting (between Tier 0 and Tier 1 SLM)
            val boosted = habitPredictionEngine?.boostIntentIfHabitual(normalized)
            if (boosted != null && boosted.confidence >= CONFIDENCE_THRESHOLD) {
                intentName = boosted.intentName
                entities = boosted.entities
                confidence = boosted.confidence
            } else {
                // Tier 1 & 2: On-device SLM classification (Gemini Nano / LiteRT)
                val throttled = thermalThrottled || (resourceGovernor != null && !resourceGovernor.canRunLlm())
                val slmResult = llmManager.classifyIntent(normalized, thermalThrottled = throttled)
                if (slmResult.intentName == "clarification") {
                    finish(commandId, "clarification", STATUS_UNKNOWN, startedAt)
                    return@withContext CommandOutcome.Clarification(rawInput, slmResult.clarificationChips)
                } else if (slmResult.confidence >= CONFIDENCE_THRESHOLD) {
                    intentName = slmResult.intentName
                    entities = slmResult.entities
                    confidence = slmResult.confidence
                } else {
                    // Tier 3: Unknown with fallback chips
                    finish(commandId, "unknown", STATUS_UNKNOWN, startedAt)
                    return@withContext CommandOutcome.Clarification(
                        rawInput,
                        slmResult.clarificationChips.ifEmpty { listOf("Search Library", "Set Alarm", "Create Note") }
                    )
                }
            }
        }

        val command = Command(
            rawInput = rawInput,
            normalizedInput = normalized,
            intentName = intentName,
            entities = entities,
            confidence = confidence,
            source = source
        )


        // Step 3: Policy check (PolicyEngine always wins)
        when (val policy = policyEngine.evaluate(command, commandId)) {
            is CommandResult.Rejected -> {
                finish(commandId, intentName, STATUS_REJECTED, startedAt)
                CommandOutcome.Rejected(policy.reason)
            }
            is CommandResult.NeedsConfirmation -> {
                finish(commandId, intentName, STATUS_NEEDS_CONFIRMATION, startedAt)
                CommandOutcome.NeedsUserConfirmation(policy.command, policy.prompt)
            }
            is CommandResult.Accepted ->
                executeCommand(policy.command, commandId, startedAt)
            is CommandResult.Unknown ->
                CommandOutcome.Unknown(rawInput)
        }
    }

    /**
     * Executes a policy-approved command after the user confirms it
     * (MEDIUM-risk flows). Persists a new CommandRecord, runs the tool,
     * writes the audit event, and updates status/latency.
     */
    suspend fun executeConfirmed(
        command: Command,
        threadId: Long? = null
    ): CommandOutcome = withContext(dispatcher) {
        val startedAt = System.currentTimeMillis()
        val commandId = commandDao.insert(
            CommandRecordEntity(
                rawInput = redactor.redact(command.rawInput),
                normalizedInput = redactor.redact(command.normalizedInput),
                intentName = command.intentName,
                confidence = command.confidence,
                source = command.source,
                threadId = threadId
            )
        )
        executeCommand(command, commandId, startedAt)
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private suspend fun executeCommand(
        command: Command,
        commandId: Long,
        startedAt: Long
    ): CommandOutcome.Executed {
        val toolId = intentToToolId(command.intentName)
        val result = toolRunner.execute(toolId, command.entities, commandId)

        // One AuditEvent per tool invocation
        auditDao.insert(
            AuditEventEntity(
                eventType  = if (result.success) "TOOL_SUCCESS" else "TOOL_ERROR",
                actor      = command.source.name,
                targetType = "TOOL",
                targetId   = toolId,
                summary    = result.message,
                riskLevel  = command.riskLevel
            )
        )

        finish(
            commandId,
            command.intentName,
            if (result.success) STATUS_COMPLETED else STATUS_FAILED,
            startedAt
        )
        return CommandOutcome.Executed(result.message, command.intentName)
    }

    private suspend fun finish(commandId: Long, intentName: String, status: String, startedAt: Long) {
        commandDao.updateResult(commandId, intentName, status, System.currentTimeMillis() - startedAt)
    }

    /** Maps intent dot-notation to tool IDs in ToolRegistry. */
    private fun intentToToolId(intentName: String): String = when (intentName) {
        Intents.SET_ALARM       -> "set_alarm"
        Intents.SET_TIMER       -> "set_timer"
        Intents.NOTE_CREATE     -> "create_note"
        Intents.APP_OPEN        -> "open_app"
        Intents.CALENDAR_CREATE -> "create_calendar_event"
        Intents.SMS_DRAFT       -> "draft_sms"
        Intents.DIAL_NUMBER     -> "open_dialer"
        Intents.LIBRARY_SEARCH  -> "library_search"
        Intents.WEB_SEARCH      -> "web_search"
        Intents.NAVIGATE_MAP    -> "navigate_maps"
        Intents.DEVICE_HEALTH   -> "device_health"
        else                    -> intentName
    }

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.5f

        /** CommandRecord status vocabulary. */
        const val STATUS_RECEIVED = "RECEIVED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION"
        const val STATUS_UNKNOWN = "UNKNOWN"
    }
}
