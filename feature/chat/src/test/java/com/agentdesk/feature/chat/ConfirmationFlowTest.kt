package com.agentdesk.feature.chat

import com.agentdesk.agent.command.CommandGateway
import com.agentdesk.agent.policy.PolicyEngine
import com.agentdesk.agent.rule.RuleEngine
import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.core.common.tools.ToolRunResult
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.dao.CommandDao
import com.agentdesk.core.persistence.dao.ConfirmationDao
import com.agentdesk.core.persistence.dao.ConversationDao
import com.agentdesk.core.persistence.dao.PolicyDecisionDao
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.CommandRecordEntity
import com.agentdesk.core.persistence.entity.ConfirmationRecordEntity
import com.agentdesk.core.persistence.entity.ConversationMessageEntity
import com.agentdesk.core.persistence.entity.ConversationThreadEntity
import com.agentdesk.core.persistence.entity.PolicyDecisionEntity
import com.agentdesk.core.security.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Unit tests for confirmation persistence and Room conversation memory in [ChatViewModel]:
 * - NeedsConfirmation -> ConfirmationRecord inserted (PROMPTED: user_confirmed = null)
 * - onConfirmAction   -> record updated (APPROVED: user_confirmed = true)
 * - onDismissConfirmation -> record updated (REJECTED: user_confirmed = false) & USER_CANCELLED audit event logged
 * - Multi-turn conversation persistence with monotonic IDs surviving recreation
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmationFlowTest {

    // ─── Fakes ────────────────────────────────────────────────────────────────

    private class FakeCommandDao : CommandDao {
        override fun observeRecent(limit: Int): Flow<List<CommandRecordEntity>> = emptyFlow()
        override suspend fun findByThread(threadId: Long): List<CommandRecordEntity> = emptyList()
        override suspend fun insert(record: CommandRecordEntity): Long = 1L
        override suspend fun updateResult(id: Long, intentName: String, status: String, latencyMs: Long) {}
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
        override suspend fun getLatestEvent(): AuditEventEntity? = events.lastOrNull()
        override suspend fun getEventsBySequenceRange(startSeq: Long, endSeq: Long): List<AuditEventEntity> = emptyList()
        override suspend fun getEventCount(): Long = events.size.toLong()
        override suspend fun deleteAll() { events.clear() }
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

    private class FakeConversationDao : ConversationDao {
        val threads = mutableListOf<ConversationThreadEntity>()
        val messages = mutableListOf<ConversationMessageEntity>()
        val messagesFlow = MutableStateFlow<List<ConversationMessageEntity>>(emptyList())
        private var nextThreadId = 1L
        private var nextMessageId = 1L

        override suspend fun insertThread(thread: ConversationThreadEntity): Long {
            val id = if (thread.id == 0L) nextThreadId++ else thread.id
            val saved = thread.copy(id = id)
            threads.removeAll { it.id == id }
            threads.add(saved)
            return id
        }

        override suspend fun updateThread(thread: ConversationThreadEntity) {
            val idx = threads.indexOfFirst { it.id == thread.id }
            if (idx >= 0) threads[idx] = thread
        }

        override suspend fun getThread(threadId: Long): ConversationThreadEntity? =
            threads.firstOrNull { it.id == threadId }

        override suspend fun getLatestActiveThread(): ConversationThreadEntity? =
            threads.filter { !it.isArchived }.maxByOrNull { it.updatedAt }

        override suspend fun touchThread(threadId: Long, updatedAt: Long) {
            val idx = threads.indexOfFirst { it.id == threadId }
            if (idx >= 0) {
                threads[idx] = threads[idx].copy(updatedAt = updatedAt)
            }
        }

        override suspend fun insertMessage(message: ConversationMessageEntity): Long {
            val id = if (message.id == 0L) nextMessageId++ else message.id
            val saved = message.copy(id = id)
            messages.add(saved)
            messagesFlow.value = messages.toList()
            return id
        }

        override fun observeMessages(threadId: Long): Flow<List<ConversationMessageEntity>> = messagesFlow

        override suspend fun getRecentMessages(threadId: Long, limit: Int): List<ConversationMessageEntity> =
            messages.filter { it.threadId == threadId }.takeLast(limit)

        override suspend fun clearThread(threadId: Long) {
            messages.removeAll { it.threadId == threadId }
            messagesFlow.value = messages.toList()
        }

        override suspend fun deleteArchivedOlderThan(cutoffMs: Long) {
            val toRemove = threads.filter { it.isArchived && it.updatedAt < cutoffMs }.map { it.id }
            threads.removeAll { it.id in toRemove }
            messages.removeAll { it.threadId in toRemove }
        }

        override suspend fun deleteThreadsOlderThan(cutoffMs: Long) {
            val toRemove = threads.filter { it.updatedAt < cutoffMs }.map { it.id }
            threads.removeAll { it.id in toRemove }
            messages.removeAll { it.threadId in toRemove }
        }

        override suspend fun countMessages(threadId: Long): Int =
            messages.count { it.threadId == threadId }
    }

    // ─── Harness ──────────────────────────────────────────────────────────────

    private lateinit var confirmationDao: FakeConfirmationDao
    private lateinit var conversationDao: FakeConversationDao
    private lateinit var auditDao: FakeAuditDao
    private lateinit var toolRunner: FakeToolRunner
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        confirmationDao = FakeConfirmationDao()
        conversationDao = FakeConversationDao()
        auditDao = FakeAuditDao()
        toolRunner = FakeToolRunner().apply {
            nextResult = ToolRunResult("draft_sms", true, "SMS draft opened for john.")
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): ChatViewModel {
        val policyDecisionDao = FakePolicyDecisionDao()
        val gateway = CommandGateway(
            ruleEngine = RuleEngine(),
            policyEngine = PolicyEngine(auditDao, policyDecisionDao, ioDispatcher = Dispatchers.Unconfined),
            toolRunner = toolRunner,
            commandDao = FakeCommandDao(),
            auditDao = auditDao,
            redactor = Redactor(),
            dispatcher = Dispatchers.Unconfined
        )
        return ChatViewModel(
            commandGateway = gateway,
            confirmationDao = confirmationDao,
            conversationDao = conversationDao,
            auditDao = auditDao,
            redactor = Redactor(),
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    private fun submitDraftSms(text: String) {
        viewModel.onInputChanged(text)
        viewModel.onSendClicked()
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `needs confirmation inserts PROMPTED record`() = runTest {
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
    fun `onDismissConfirmation records REJECTED and logs USER_CANCELLED audit event`() = runTest {
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

        // Mandatory audit logging check
        val cancelEvent = auditDao.events.firstOrNull { it.eventType == "USER_CANCELLED" }
        assertNotNull("USER_CANCELLED audit event must be recorded in AuditDao", cancelEvent)
        assertEquals("message.draft_sms", cancelEvent?.targetId)
    }

    @Test
    fun `second prompt gets its own record`() = runTest {
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

    @Test
    fun `conversation messages persist to Room with monotonic IDs`() = runTest {
        viewModel = buildViewModel()

        submitDraftSms("text john saying i am on my way")
        advanceUntilIdle()
        viewModel.onConfirmAction()
        advanceUntilIdle()

        // Verify messages in ConversationDao
        assertTrue(conversationDao.messages.size >= 2)
        val userMsg = conversationDao.messages[0]
        val assistantMsg = conversationDao.messages[1]

        assertEquals("user", userMsg.role)
        assertEquals("assistant", assistantMsg.role)
        assertTrue("Room message ID must be positive and monotonic", userMsg.id > 0)
        assertTrue("Second message ID must be strictly greater", assistantMsg.id > userMsg.id)

        // Verify UI state messages are updated with monotonic IDs
        val uiMessages = viewModel.uiState.value.messages
        assertTrue(uiMessages.size >= 2)
        assertEquals(userMsg.id, uiMessages[0].id)
        assertEquals(assistantMsg.id, uiMessages[1].id)
    }

    @Test
    fun `conversation messages survive ViewModel recreation`() = runTest {
        viewModel = buildViewModel()

        submitDraftSms("text john saying i am on my way")
        advanceUntilIdle()
        viewModel.onConfirmAction()
        advanceUntilIdle()

        val messageCountBefore = conversationDao.messages.size

        // Recreate ViewModel simulating process recreation / navigation return
        val newViewModel = buildViewModel()
        advanceUntilIdle()

        val restoredMessages = newViewModel.uiState.value.messages
        assertEquals(messageCountBefore, restoredMessages.size)
        assertEquals(conversationDao.messages.first().id, restoredMessages.first().id)
        assertEquals(conversationDao.messages.last().id, restoredMessages.last().id)
    }
}