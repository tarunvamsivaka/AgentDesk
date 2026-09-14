package com.agentdesk.agent.command

import com.agentdesk.agent.policy.PolicyEngine
import com.agentdesk.agent.rule.RuleEngine
import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.core.common.tools.ToolRunResult
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.dao.CommandDao
import com.agentdesk.core.persistence.dao.PolicyDecisionDao
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.CommandRecordEntity
import com.agentdesk.core.persistence.entity.PolicyDecisionEntity
import com.agentdesk.core.security.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ---------------------------------------------------------------------------
// Fakes (same pattern as CommandGatewayTest)
// ---------------------------------------------------------------------------

private class RedactedFakeCommandDao : CommandDao {
    val inserted = mutableListOf<CommandRecordEntity>()

    override fun observeRecent(limit: Int): Flow<List<CommandRecordEntity>> = emptyFlow()
    override suspend fun findByThread(threadId: Long): List<CommandRecordEntity> = emptyList()
    override suspend fun insert(record: CommandRecordEntity): Long {
        inserted.add(record)
        return inserted.size.toLong()
    }
    override suspend fun updateResult(id: Long, intentName: String, status: String, latencyMs: Long) {}
    override suspend fun deleteOlderThan(beforeEpoch: Long) {}
}

private class RedactedFakeAuditDao : AuditDao {
    override fun observeRecent(limit: Int): Flow<List<AuditEventEntity>> = emptyFlow()
    override fun observeByType(eventType: String): Flow<List<AuditEventEntity>> = emptyFlow()
    override suspend fun insert(event: AuditEventEntity): Long = 1L
    override suspend fun deleteOlderThan(beforeEpoch: Long) {}
    override suspend fun deleteAll() {}
}

private class RedactedFakePolicyDecisionDao : PolicyDecisionDao {
    override suspend fun insert(decision: PolicyDecisionEntity): Long = 1L
    override fun observeRecent(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
    override fun observeByIntent(intent: String, limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
    override fun observeRejected(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
    override suspend fun deleteOlderThan(beforeMs: Long) {}
    override suspend fun count(): Int = 0
}

private class RedactedFakeToolRunner : ToolRunner {
    override suspend fun execute(
        toolId: String,
        parameters: Map<String, String>,
        commandId: Long
    ): ToolRunResult = ToolRunResult(toolId, true, "ok")
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

/**
 * Verifies the gateway stores a REDACTED rawInput in the command_record table
 * (BUG-007): PII like phone numbers, emails, and card numbers must never be
 * persisted in plaintext.
 */
class RedactedCommandRecordTest {

    private lateinit var commandDao: RedactedFakeCommandDao
    private lateinit var gateway: CommandGateway

    @Before
    fun setUp() {
        commandDao = RedactedFakeCommandDao()
        gateway = CommandGateway(
            ruleEngine = RuleEngine(),
            policyEngine = PolicyEngine(
                RedactedFakeAuditDao(),
                RedactedFakePolicyDecisionDao(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            toolRunner = RedactedFakeToolRunner(),
            commandDao = commandDao,
            auditDao = RedactedFakeAuditDao(),
            redactor = Redactor(),
            dispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun `gateway stores redacted rawInput - phone number is not persisted`() = runBlocking {
        gateway.submitText("note down call 555-123-4567")

        val record = commandDao.inserted.single()
        assertFalse(
            "rawInput must not contain the raw phone number, got: '${record.rawInput}'",
            record.rawInput.contains("555-123-4567")
        )
        assertTrue(
            "rawInput must contain the redaction placeholder, got: '${record.rawInput}'",
            record.rawInput.contains("[PHONE]")
        )
    }

    @Test
    fun `gateway stores redacted normalizedInput - email is not persisted`() = runBlocking {
        gateway.submitText("Note down email john.doe@example.com")

        val record = commandDao.inserted.single()
        assertFalse(
            "normalizedInput must not contain the raw email, got: '${record.normalizedInput}'",
            record.normalizedInput.contains("john.doe@example.com")
        )
        assertTrue(record.normalizedInput.contains("[EMAIL]"))
    }

    @Test
    fun `non-PII input is stored unchanged`() = runBlocking {
        gateway.submitText("note down buy milk")

        val record = commandDao.inserted.single()
        assertTrue(record.rawInput.contains("buy milk"))
        assertFalse(record.rawInput.contains("[PHONE]"))
    }
}
