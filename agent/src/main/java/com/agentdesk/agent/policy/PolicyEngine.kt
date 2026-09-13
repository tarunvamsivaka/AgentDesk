package com.agentdesk.agent.policy

import com.agentdesk.agent.command.Command
import com.agentdesk.agent.command.CommandResult
import com.agentdesk.agent.rule.Intents
import com.agentdesk.agent.rule.RuleResult
import com.agentdesk.core.common.model.RiskLevel
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.entity.AuditEventEntity
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PolicyEngine — the final authority on whether a command is allowed to execute.
 *
 * Rules:
 * - FORBIDDEN_INTENTS are always denied, no exceptions.
 * - HIGH risk intents require user confirmation before execution.
 * - MEDIUM risk intents (e.g. SMS draft, dialer) open system UI with user approval.
 * - LOW risk intents are accepted immediately.
 * - Every evaluation is written to the AuditLog for full transparency.
 *
 * The PolicyEngine is intentionally decoupled from any AI model and is purely deterministic.
 */
@Singleton
class PolicyEngine @Inject constructor(
    private val auditDao: AuditDao
) {

    fun evaluate(command: Command, ruleResult: RuleResult? = null): CommandResult {
        val intent = command.intentName

        // 1. Hard block — forbidden intents can never be overridden by any config or AI model
        if (intent in FORBIDDEN_INTENTS) {
            writeAudit(command, "DENIED", RiskLevel.HIGH, "Forbidden intent: $intent")
            return CommandResult.Rejected(
                reason = "This action is not permitted by the AgentDesk policy engine.",
                command = command
            )
        }

        // 2. Determine risk level for this intent
        val risk = when (intent) {
            Intents.SMS_DRAFT        -> RiskLevel.MEDIUM
            Intents.DIAL_NUMBER      -> RiskLevel.MEDIUM
            Intents.CALENDAR_CREATE  -> RiskLevel.LOW
            Intents.SET_ALARM        -> RiskLevel.LOW
            Intents.SET_TIMER        -> RiskLevel.LOW
            Intents.NOTE_CREATE      -> RiskLevel.LOW
            Intents.APP_OPEN         -> RiskLevel.LOW
            Intents.LIBRARY_SEARCH   -> RiskLevel.LOW
            Intents.WEB_SEARCH       -> RiskLevel.LOW
            Intents.NAVIGATE_MAP     -> RiskLevel.LOW
            Intents.DEVICE_HEALTH    -> RiskLevel.LOW
            else                     -> RiskLevel.HIGH
        }

        // 3. HIGH risk (unknown intents) always require explicit confirmation
        if (risk == RiskLevel.HIGH) {
            writeAudit(command, "NEEDS_CONFIRMATION", risk, "High-risk/unknown intent: $intent")
            return CommandResult.NeedsConfirmation(
                command = command.copy(riskLevel = risk, requiresConfirmation = true),
                prompt = "This action requires your confirmation. Do you want to proceed?"
            )
        }

        // 4. MEDIUM — open system UI (draft/dialer), but user must still tap Send/Call themselves
        if (risk == RiskLevel.MEDIUM) {
            writeAudit(command, "ACCEPTED_WITH_CONFIRMATION", risk, "Medium-risk intent: $intent")
            return CommandResult.NeedsConfirmation(
                command = command.copy(riskLevel = risk, requiresConfirmation = true),
                prompt = confirmationPromptFor(intent, command)
            )
        }

        // 5. LOW — accepted, no confirmation required
        writeAudit(command, "ACCEPTED", risk, "Low-risk intent: $intent")
        return CommandResult.Accepted(command.copy(riskLevel = risk))
    }

    private fun confirmationPromptFor(intent: String, command: Command): String = when (intent) {
        Intents.SMS_DRAFT   -> "Open SMS draft to ${command.entities["contact"].orEmpty()}?"
        Intents.DIAL_NUMBER -> "Open dialer for ${command.entities["contact"].orEmpty()}?"
        else                -> "Proceed with this action?"
    }

    private fun writeAudit(command: Command, decision: String, risk: RiskLevel, reason: String) {
        runBlocking {
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
