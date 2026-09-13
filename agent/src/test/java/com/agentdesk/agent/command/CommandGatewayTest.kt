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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

private data class StatusUpdate(
    val id: Long,
    val intentName: String,
    val status: String,
    val latencyMs: Long
)

private class FakeCommandDao : CommandDao {
    val inserted = mutableListOf<CommandRecordEntity>()
    val statusUpdates = mutableListOf<StatusUpdate>()

    override fun observeRecent(limit: Int): Flow<List<CommandRecordEntity>> = emptyFlow()
    override suspend fun findByThread(threadId: Long): List<CommandRecordEntity> = emptyList()
    override suspend fun insert(record: CommandRecordEntity): Long {
        inserted.add(record)
        return inserted.size.toLong()
    }
    override suspend fun updateResult(id: Long, intentName: String, status: String, latencyMs: Long) {
        statusUpdates.add(StatusUpdate(id, intentName, status, latencyMs))
    }
    override suspend fun deleteOlderThan(beforeEpoch: Long) {}
}

private class FakeAuditDao : AuditDao {
    val events = mutableListOf<AuditEventEntity>()

    override fun observeRecent(limit: Int): Flow<List<AuditEventEntity>> = emptyFlow()
    override fun observeByType(eventType: String): Flow<List<AuditEventEntity>> = emptyFlow()
    override suspend fun insert(event: AuditEventEntity): Long {
        events.add(event)
        return events.size.toLong()
    }
    override suspend fun deleteOlderThan(beforeEpoch: Long) {}
}

private class FakePolicyDecisionDao : PolicyDecisionDao {
    val decisions = mutableListOf<PolicyDecisionEntity>()

    override suspend fun insert(decision: PolicyDecisionEntity): Long {
        decisions.add(decision)
        return decisions.size.toLong()
    }
    override fun observeRecent(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
    override fun observeByIntent(intent: String, limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
    override fun observeRejected(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
    override suspend fun deleteOlderThan(beforeMs: Long) {}
    override suspend fun count(): Int = decisions.size
}

private class FakeToolRunner : ToolRunner {
    val invoked = mutableListOf<Pair<String, Map<String, String>>>()
    var nextResult: ToolRunResult = ToolRunResult("unknown", true, "ok")

    override suspend fun execute(
        toolId: String,
        parameters: Map<String, String>,
        commandId: Long
    ): ToolRunResult {
        invoked.add(toolId to parameters)
        return nextResult
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class CommandGatewayTest {

    private lateinit var commandDao: FakeCommandDao
    private lateinit var auditDao: FakeAuditDao
    private lateinit var policyDecisionDao: FakePolicyDecisionDao
    private lateinit var toolRunner: FakeToolRunner
    private lateinit var gateway: CommandGateway

    @Before
    fun setUp() {
        commandDao = FakeCommandDao()
        auditDao = FakeAuditDao()
        policyDecisionDao = FakePolicyDecisionDao()
        toolRunner = FakeToolRunner()
        gateway = CommandGateway(
            ruleEngine = RuleEngine(),
            policyEngine = PolicyEngine(auditDao, policyDecisionDao),
            toolRunner = toolRunner,
            commandDao = commandDao,
            auditDao = auditDao,
            dispatcher = Dispatchers.Unconfined
        )
    }

    // ─── Alarm: end to end ────────────────────────────────────────────────────

    @Test
    fun `alarm command runs end to end`() = runBlocking {
        toolRunner.nextResult =
            ToolRunResult("set_alarm", true, "Done. Alarm set for 7:00 AM.")

        val outcome = gateway.submitText("set alarm at 7am")

        assertTrue(outcome is CommandOutcome.Executed)
        assertEquals("Done. Alarm set for 7:00 AM.", (outcome as CommandOutcome.Executed).message)

        // Tool invoked once with the right id and entities
        assertEquals(listOf("set_alarm"), toolRunner.invoked.map { it.first })
        assertEquals("7am", toolRunner.invoked.single().second["time"])

        // CommandRecord lifecycle: RECEIVED insert, then result update
        assertEquals(1, commandDao.inserted.size)
        assertEquals(CommandGateway.STATUS_RECEIVED, commandDao.inserted.single().status)
        val update = commandDao.statusUpdates.single()
        assertEquals("system.set_alarm", update.intentName)
        assertEquals(CommandGateway.STATUS_COMPLETED, update.status)
        assertTrue(update.latencyMs >= 0)

        // PolicyDecision row: allowed, no confirmation required
        val decision = policyDecisionDao.decisions.single()
        assertEquals("system.set_alarm", decision.intentName)
        assertTrue(decision.allowed)
        assertFalse(decision.requiresConfirmation)

        // Audit: policy decision event + one event per tool invocation
        assertEquals(listOf("ACCEPTED", "TOOL_SUCCESS"), auditDao.events.map { it.eventType })
        assertEquals("set_alarm", auditDao.events.last().targetId)
    }

    // ─── Note: end to end ─────────────────────────────────────────────────────

    @Test
    fun `note command runs end to end`() = runBlocking {
        toolRunner.nextResult = ToolRunResult("create_note", true, "Note saved.")

        val outcome = gateway.submitText("note down buy milk")

        assertTrue(outcome is CommandOutcome.Executed)
        assertEquals("Note saved.", (outcome as CommandOutcome.Executed).message)
        assertEquals("create_note", toolRunner.invoked.single().first)
        assertEquals("buy milk", toolRunner.invoked.single().second["text"])
        assertEquals("note.create", commandDao.statusUpdates.single().intentName)
        assertEquals(CommandGateway.STATUS_COMPLETED, commandDao.statusUpdates.single().status)

        val decision = policyDecisionDao.decisions.single()
        assertTrue(decision.allowed)
        assertFalse(decision.requiresConfirmation)
        assertTrue(auditDao.events.any { it.eventType == "TOOL_SUCCESS" })
    }

    // ─── App launch: end to end ───────────────────────────────────────────────

    @Test
    fun `open app command runs end to end`() = runBlocking {
        toolRunner.nextResult = ToolRunResult("open_app", true, "Opening Chrome.")

        val outcome = gateway.submitText("open chrome")

        assertTrue(outcome is CommandOutcome.Executed)
        assertEquals("Opening Chrome.", (outcome as CommandOutcome.Executed).message)
        assertEquals("open_app", toolRunner.invoked.single().first)
        assertEquals("chrome", toolRunner.invoked.single().second["appName"])
        assertEquals("app.open", commandDao.statusUpdates.single().intentName)
        assertEquals(CommandGateway.STATUS_COMPLETED, commandDao.statusUpdates.single().status)
        assertTrue(policyDecisionDao.decisions.single().allowed)
        assertFalse(policyDecisionDao.decisions.single().requiresConfirmation)
    }

    // ─── Error path ───────────────────────────────────────────────────────────

    @Test
    fun `tool failure records FAILED status and TOOL_ERROR audit`() = runBlocking {
        toolRunner.nextResult =
            ToolRunResult("open_app", false, "App \"chrome\" not found on this device.")

        val outcome = gateway.submitText("open chrome")

        assertTrue(outcome is CommandOutcome.Executed)
        assertTrue((outcome as CommandOutcome.Executed).message.contains("not found"))
        assertEquals(CommandGateway.STATUS_FAILED, commandDao.statusUpdates.single().status)
        assertEquals("TOOL_ERROR", auditDao.events.last().eventType)
    }

    // ─── Unknown input ────────────────────────────────────────────────────────

    @Test
    fun `unknown input is never executed`() = runBlocking {
        val outcome = gateway.submitText("zzzblahblah123nonsense")

        assertTrue(outcome is CommandOutcome.Unknown)
        assertTrue(toolRunner.invoked.isEmpty())
        assertEquals("unknown", commandDao.statusUpdates.single().intentName)
        assertEquals(CommandGateway.STATUS_UNKNOWN, commandDao.statusUpdates.single().status)
    }
}
