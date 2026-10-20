package com.agentdesk.agent.policy

import com.agentdesk.agent.command.Command
import com.agentdesk.agent.command.CommandResult
import com.agentdesk.agent.di.IoDispatcher
import com.agentdesk.agent.rule.Intents
import com.agentdesk.agent.rule.RuleResult
import com.agentdesk.core.common.model.RiskLevel
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.dao.PolicyDecisionDao
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.PolicyDecisionEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PolicyEngine — the final authority on whether a command is allowed to execute.
 *
 * Rules:
 * - FORBIDDEN_INTENTS are always denied, no exceptions.
 * - HIGH & CRITICAL risk intents require user confirmation before execution.
 * - MEDIUM & LOW risk intents (e.g. calendar draft, notes, settings) open system UI without pre-launch blocking.
 * - Every evaluation writes an AuditEvent and a PolicyDecision row.
 *
 * The PolicyEngine is intentionally decoupled from any AI model and is purely
 * deterministic. All DAO writes are suspend calls — no main-thread work.
 */
@Singleton
class PolicyEngine @Inject constructor(
    private val auditDao: AuditDao,
    private val policyDecisionDao: PolicyDecisionDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    suspend fun evaluate(
        command: Command,
        commandId: Long = 0L,
        ruleResult: RuleResult? = null
    ): CommandResult {
        val intent = command.intentName

        // 1. Hard block — forbidden intents can never be overridden by any config or AI model
        if (intent in FORBIDDEN_INTENTS) {
            record(command, commandId, allowed = false, risk = RiskLevel.HIGH,
                requiresConfirmation = false, reason = "Forbidden intent: $intent")
            writeAudit(command, "DENIED", RiskLevel.HIGH, "Forbidden intent: $intent")
            return CommandResult.Rejected(
                reason = "This action is not permitted by the AgentDesk policy engine.",
                command = command
            )
        }

        // 2. Determine risk level for this intent (hardcoded rules)
        val risk = when (intent) {
            Intents.SMS_DRAFT        -> RiskLevel.HIGH
            Intents.DIAL_NUMBER      -> RiskLevel.HIGH
            Intents.EMAIL_DRAFT      -> RiskLevel.HIGH
            Intents.CALENDAR_CREATE  -> RiskLevel.MEDIUM
            Intents.SET_ALARM        -> if (command.entities["skip_ui"] == "true" || command.entities["skipUi"] == "true") RiskLevel.MEDIUM else RiskLevel.LOW
            Intents.SET_TIMER        -> if (command.entities["skip_ui"] == "true" || command.entities["skipUi"] == "true") RiskLevel.MEDIUM else RiskLevel.LOW
            Intents.NOTE_CREATE      -> RiskLevel.LOW
            Intents.NOTE_APPEND      -> RiskLevel.LOW
            Intents.NOTE_SEARCH      -> RiskLevel.LOW
            Intents.APP_OPEN         -> RiskLevel.LOW
            Intents.SETTINGS_OPEN    -> RiskLevel.LOW
            Intents.LIBRARY_SEARCH   -> RiskLevel.LOW
            Intents.WEB_SEARCH       -> RiskLevel.LOW
            Intents.NAVIGATE_MAP     -> RiskLevel.LOW
            Intents.DEVICE_HEALTH    -> RiskLevel.LOW
            else                     -> RiskLevel.HIGH
        }

        // 3. HIGH and CRITICAL risk always requires explicit confirmation
        if (risk == RiskLevel.HIGH || risk == RiskLevel.CRITICAL) {
            record(command, commandId, allowed = true, risk = risk,
                requiresConfirmation = true, reason = "High-risk intent: $intent")
            writeAudit(command, "NEEDS_CONFIRMATION", risk, "High-risk intent: $intent")
            return CommandResult.NeedsConfirmation(
                command = command.copy(riskLevel = risk, requiresConfirmation = true),
                prompt = confirmationPromptFor(intent, command)
            )
        }

        // 4. MEDIUM & LOW — accepted, transparent system UI execution without pre-launch blocking
        val decision = if (risk == RiskLevel.MEDIUM) "ACCEPTED_MEDIUM" else "ACCEPTED"
        record(command, commandId, allowed = true, risk = risk,
            requiresConfirmation = false, reason = "${risk.name}-risk intent: $intent")
        writeAudit(command, decision, risk, "${risk.name}-risk intent: $intent")
        return CommandResult.Accepted(command.copy(riskLevel = risk))
    }

    private fun confirmationPromptFor(intent: String, command: Command): String = when (intent) {
        Intents.SMS_DRAFT       -> "Open SMS draft to ${command.entities["contact"].orEmpty()}?"
        Intents.DIAL_NUMBER     -> "Open dialer for ${command.entities["contact"].orEmpty()}?"
        Intents.EMAIL_DRAFT     -> "Open email draft to ${command.entities["recipient"].orEmpty().ifEmpty { command.entities["contact"].orEmpty() }}?"
        Intents.CALENDAR_CREATE -> "Add calendar event '${command.entities["title"].orEmpty()}'?"
        Intents.SET_ALARM       -> "Set alarm for ${command.entities["time"].orEmpty()}?"
        else                    -> "Proceed with this action?"
    }

    /** Writes one PolicyDecision row per evaluation. */
    private suspend fun record(
        command: Command,
        commandId: Long,
        allowed: Boolean,
        risk: RiskLevel,
        requiresConfirmation: Boolean,
        reason: String
    ) {
        withContext(ioDispatcher) {
            policyDecisionDao.insert(
                PolicyDecisionEntity(
                    commandId = commandId,
                    intentName = command.intentName,
                    riskLevel = risk,
                    allowed = allowed,
                    reason = reason,
                    requiresConfirmation = requiresConfirmation
                )
            )
        }
    }

    private suspend fun writeAudit(command: Command, decision: String, risk: RiskLevel, reason: String) {
        withContext(ioDispatcher) {
            auditDao.insert(
                AuditEventEntity(
                    eventType      = decision,
                    actor          = command.source.name,
                    targetType     = "INTENT",
                    targetId       = command.intentName,
                    summary        = reason,
                    redactedDetails = "",
                    riskLevel      = risk
                )
            )
        }
    }

    companion object {
        /**
         * Hardcoded set of intents that are ALWAYS denied.
         * Cannot be overridden by configuration, feature flags, or AI model responses.
         */
        val FORBIDDEN_INTENTS: Set<String> = setOf(
            "message.send_sms_silent",
            "message.send_whatsapp_silent",
            "call.place_silent",
            "app.install_silent",
            "file.delete_silent",
            "accessibility.automate",
            "sms.read_background",
            "call_log.read",
            "scrape.third_party_app",
            "cloud.sync_silent"
        )
    }
}
