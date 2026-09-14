package com.agentdesk.feature.chat

import com.agentdesk.agent.command.CommandGateway
import com.agentdesk.agent.policy.PolicyEngine
import com.agentdesk.agent.rule.RuleEngine
import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.core.common.tools.ToolRunResult
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.dao.CommandDao
import com.agentdesk.core.persistence.dao.ConfirmationDao
import com.agentdesk.core.persistence.dao.PolicyDecisionDao
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.CommandRecordEntity
import com.agentdesk.core.persistence.entity.ConfirmationRecordEntity
import com.agentdesk.core.persistence.entity.PolicyDecisionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for confirmation persistence in [ChatViewModel]:
 * - NeedsConfirmation -> ConfirmationRecord inserted (PROMPTED: user_confirmed = null)
 * - onConfirmAction   -> record updated (APPROVED: user_confirmed = true)
 * - onDismissConfirmation -> record updated (REJECTED: user_confirmed = false)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmationFlowTest {

    // ─── Fakes (same pattern as CommandGatewayTest) ───────────────────────────

    private class FakeCommandDao : CommandDao {
        override fun observeRecent(limit: Int): Flow<List<CommandRecordEntity>> = emptyFlow()
        override suspend fun findByThread(threadId: Long): List<CommandRecordEntity> = emptyList()
        override suspend fun insert(record: CommandRecordEntity): Long = 1L
        override suspend fun updateResult(id: Long, intentName: String, status: String, latencyMs: Long) {}
        override suspend fun deleteOlderThan(beforeEpoch: Long) {}
    }

    private class FakeAuditDao : AuditDao {
        override fun observeRecent(limit: Int): Flow<List<AuditEventEntity>> = emptyFlow()
        override fun observeByType(eventType: String): Flow<List<AuditEventEntity>> = emptyFlow()
        override suspend fun insert(event: AuditEventEntity): Long = 1L
        override suspend fun deleteOlderThan(beforeEpoch: Long) {}
        override suspend fun deleteAll() {}
    }

    private class FakePolicyDecisionDao : PolicyDecisionDao {
        override suspend fun insert(decision: PolicyDecisionEntity): Long = 1L
        override fun observeRecent(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
        override fun observeByIntent(intent: String, limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
        override fun observeRejected(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
        override suspend fun deleteOlderThan(beforeMs: Long) {}
        override suspend fun count(): Int = 0
    }

    private class FakeToolRunner : ToolRunner {
        var nextResult: ToolRunResult = ToolRunResult("unknown", true, "ok")
        override suspend fun execute(
            toolId: String,
            parameters: Map<String, String>,
            commandId: Long
        ): ToolRunResult = nextResult
    }

    private class FakeConfirmationDao : ConfirmationDao {
        val records = mutableListOf<ConfirmationRecordEntity>()
        private var nextId = 1L

        override suspend fun insert(confirmation: ConfirmationRecordEntity): Long {
            val record = confirmation.copy(id = nextId)
            records.add(record)
            return nextId++
        }

        override suspend fun getForTask(taskId: Long): ConfirmationRecordEntity? =
            records.lastOrNull { it.taskId == taskId }

        override fun observeRecent(limit: Int): Flow<List<ConfirmationRecordEntity>> = emptyFlow()

        override suspend fun deleteOlderThan(beforeMs: Long) {}

        override suspend fun recordResponse(
            id: Long,
            confirmed: Boolean,
            respondedAt: Long
        ) {
            val index = records.indexOfFirst { it.id == id }
            if (index >= 0) {
                records[index] = records[index].copy(
                    userConfirmed = confirmed,
                    respondedAt = respondedAt
                )
            }
        }
    }

    // ─── Harness ──────────────────────────────────────────────────────────────

    private lateinit var confirmationDao: FakeConfirmationDao
    private lateinit var toolRunner: FakeToolRunner
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): ChatViewModel {
        val auditDao = FakeAuditDao()
        val policyDecisionDao = FakePolicyDecisionDao()
        val gateway = CommandGateway(
            ruleEngine = RuleEngine(),
            policyEngine = PolicyEngine(auditDao, policyDecisionDao),
            toolRunner = toolRunner,
            commandDao = FakeCommandDao(),
            auditDao = auditDao,
            dispatcher = Dispatchers.Unconfined
        )
        return ChatViewModel(gateway, confirmationDao)
    }

    private fun submitDraftSms(text: String) {
        viewModel.onInputChanged(text)
        viewModel.onSendClicked()
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `needs confirmation inserts PROMPTED record`() = runTest {
        confirmationDao = FakeConfirmationDao()
        toolRunner = FakeToolRunner().apply {
            nextResult = ToolRunResult("draft_sms", true, "SMS draft opened for john.")
        }
        viewModel = buildViewModel()

        submitDraftSms("text john saying i am on my way")
        advanceUntilIdle()

        assertEquals(1, confirmationDao.records.size)
        val record = confirmationDao.records.single()
        assertNull("PROMPTED record must have user_confirmed = null", record.userConfirmed)
        assertNull(record.respondedAt)
        assertTrue(record.promptShown.isNotBlank())
        assertNotNull(viewModel.uiState.value.pendingConfirmation)
    }

    @Test
    fun `onConfirmAction records APPROVED`() = runTest {
        confirmationDao = FakeConfirmationDao()
        toolRunner = FakeToolRunner().apply {
            nextResult = ToolRunResult("draft_sms", true, "SMS draft opened for john.")
        }
        viewModel = buildViewModel()

        submitDraftSms("text john saying i am on my way")
        advanceUntilIdle()
        viewModel.onConfirmAction()
        advanceUntilIdle()

        val record = confirmationDao.records.single()
        assertTrue("APPROVED record must have user_confirmed = true", record.userConfirmed == true)
        assertNotNull(record.respondedAt)
        assertNull(viewModel.uiState.value.pendingConfirmation)
    }

    @Test
    fun `onDismissConfirmation records REJECTED`() = runTest {
        confirmationDao = FakeConfirmationDao()
        toolRunner = FakeToolRunner().apply {
            nextResult = ToolRunResult("draft_sms", true, "SMS draft opened for john.")
        }
        viewModel = buildViewModel()

        submitDraftSms("text john saying i am on my way")
        advanceUntilIdle()
        viewModel.onDismissConfirmation()
        advanceUntilIdle()

        assertEquals(1, confirmationDao.records.size)
        val record = confirmationDao.records.single()
        assertTrue("REJECTED record must have user_confirmed = false", record.userConfirmed == false)
        assertNotNull(record.respondedAt)
        assertNull(viewModel.uiState.value.pendingConfirmation)
    }

    @Test
    fun `second prompt gets its own record`() = runTest {
        confirmationDao = FakeConfirmationDao()
        toolRunner = FakeToolRunner().apply {
            nextResult = ToolRunResult("draft_sms", true, "SMS draft opened.")
        }
        viewModel = buildViewModel()

        submitDraftSms("text john saying i am on my way")
        advanceUntilIdle()
        viewModel.onConfirmAction()
        advanceUntilIdle()

        submitDraftSms("text mom that dinner is ready")
        advanceUntilIdle()
        viewModel.onDismissConfirmation()
        advanceUntilIdle()

        assertEquals(2, confirmationDao.records.size)
        assertTrue(confirmationDao.records[0].userConfirmed == true)
        assertTrue(confirmationDao.records[1].userConfirmed == false)
    }
}