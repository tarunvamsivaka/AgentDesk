package com.agentdesk.agent.command

import com.agentdesk.agent.policy.PolicyEngine
import com.agentdesk.agent.rule.RuleEngine
import com.agentdesk.core.common.model.CommandSource
import com.agentdesk.core.persistence.dao.CommandDao
import com.agentdesk.core.persistence.entity.CommandRecordEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point for all incoming commands.
 *
 * Flow:
 * 1. Normalize raw input
 * 2. Pass through RuleEngine to get intent + entities
 * 3. Pass through PolicyEngine to get risk + allow/deny decision
 * 4. Persist command record
 * 5. Return CommandResult
 *
 * The PolicyEngine always has final authority. If it denies, execution never proceeds.
 */
@Singleton
class CommandGateway @Inject constructor(
    private val ruleEngine: RuleEngine,
    private val policyEngine: PolicyEngine,
    private val commandDao: CommandDao
) {

    suspend fun process(
        rawInput: String,
        source: CommandSource = CommandSource.USER_CHAT,
        threadId: Long? = null
    ): CommandResult {
        val normalized = rawInput.trim().lowercase()

        // Step 1: Intent recognition
        val ruleResult = ruleEngine.evaluate(normalized)

        if (ruleResult == null || ruleResult.confidence < CONFIDENCE_THRESHOLD) {
            // Persist as unknown command
            commandDao.insert(
                CommandRecordEntity(
                    rawInput = rawInput,
                    normalizedInput = normalized,
                    intentName = "unknown",
                    confidence = ruleResult?.confidence ?: 0f,
                    source = source,
                    threadId = threadId
                )
            )
            return CommandResult.Unknown(rawInput)
        }

        // Step 2: Build command
        val command = Command(
            rawInput = rawInput,
            normalizedInput = normalized,
            intentName = ruleResult.intentName,
            entities = ruleResult.entities,
            confidence = ruleResult.confidence,
            source = source
        )

        // Step 3: Policy check (PolicyEngine always wins)
        val policyResult = policyEngine.evaluate(command)

        // Step 4: Persist command record
        commandDao.insert(
            CommandRecordEntity(
                rawInput = rawInput,
                normalizedInput = normalized,
                intentName = ruleResult.intentName,
                confidence = ruleResult.confidence,
                source = source,
                threadId = threadId
            )
        )

        return policyResult
    }

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.5f
    }
}
