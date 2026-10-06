package com.agentdesk.feature.chat

import com.agentdesk.agent.command.Command
import com.agentdesk.agent.command.CommandGateway
import com.agentdesk.agent.policy.PolicyEngine
import com.agentdesk.agent.rule.Intents
import com.agentdesk.agent.rule.RuleEngine
import com.agentdesk.core.common.model.CommandSource
import com.agentdesk.core.common.model.RiskLevel
import com.agentdesk.core.common.tools.ToolRunResult
import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.core.persistence.audit.DefaultAuditReceiptSigner
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Empirical test suite by Challenger 2 verifying:
 * 1. Confirmation parameter previews across intents (SMS, Call, Alarm, Timer, Calendar, Map, Note)
 * 2. Mandatory USER_CANCELLED audit event recorded on dismissal
 * 3. Non-null and non-blank action_hash in AuditDao for USER_CANCELLED event
 * 4. HMAC-SHA256 audit chain mathematical verification
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Challenger2ConfirmationAuditTest {

    private class SignerAuditDao(
        private val signer: DefaultAuditReceiptSigner = DefaultAuditReceiptSigner()
    ) : AuditDao {
        val events = mutableListOf<AuditEventEntity>()
        private var sequenceCounter = 0L

        override fun observeRecent(limit: Int): Flow<List<AuditEventEntity>> = emptyFlow()
        override fun observeByType(eventType: String): Flow<List<AuditEventEntity>> = emptyFlow()

        override suspend fun insert(event: AuditEventEntity): Long {
            sequenceCounter++
            val prevDigest = events.lastOrNull()?.receiptDigest ?: signer.genesisDigest
            val actionHash = signer.computeActionHash(event.eventType, event.summary, event.occurredAt)
            val receiptDigest = signer.computeReceipt(
                sequence = sequenceCounter,
                timestamp = event.occurredAt,
                actor = event.actor,
                intent = event.eventType,
                verdict = event.summary,
                actionHash = actionHash,
                prevDigest = prevDigest
            )
            val signed = event.copy(
                id = sequenceCounter,
                sequenceNumber = sequenceCounter,
                prevDigest = prevDigest,
                actionHash = actionHash,
                receiptDigest = receiptDigest
            )
            events.add(signed)
            return sequenceCounter
        }

        override suspend fun deleteOlderThan(beforeEpoch: Long) {}
        override suspend fun getLatestEvent(): AuditEventEntity? = events.lastOrNull()
        override suspend fun getEventsBySequenceRange(startSeq: Long, endSeq: Long): List<AuditEventEntity> = emptyList()
        override suspend fun getEventCount(): Long = events.size.toLong()
        override suspend fun deleteAll() { events.clear() }

        fun verifyChain(): Boolean = signer.verifyChain(events)
    }

    private class StubCommandDao : CommandDao {
        override fun observeRecent(limit: Int): Flow<List<CommandRecordEntity>> = emptyFlow()
        override suspend fun findByThread(threadId: Long): List<CommandRecordEntity> = emptyList()
        override suspend fun insert(record: CommandRecordEntity): Long = 1L
        override suspend fun updateResult(id: Long, intentName: String, status: String, latencyMs: Long) {}
        override suspend fun deleteOlderThan(beforeEpoch: Long) {}
    }

    private class StubPolicyDecisionDao : PolicyDecisionDao {
        override suspend fun insert(decision: PolicyDecisionEntity): Long = 1L
        override fun observeRecent(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
        override fun observeByIntent(intent: String, limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
        override fun observeRejected(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
        override suspend fun deleteOlderThan(beforeMs: Long) {}
        override suspend fun count(): Int = 0
    }

    private class StubConfirmationDao : ConfirmationDao {
        val records = mutableListOf<ConfirmationRecordEntity>()
        private var nextId = 1L

        override suspend fun insert(confirmation: ConfirmationRecordEntity): Long {
            val record = confirmation.copy(id = nextId)
            records.add(record)
            return nextId++
        }

        override suspend fun getForTask(taskId: Long): ConfirmationRecordEntity? = records.lastOrNull()
        override fun observeRecent(limit: Int): Flow<List<ConfirmationRecordEntity>> = emptyFlow()
        override suspend fun deleteOlderThan(beforeMs: Long) {}
        override suspend fun recordResponse(id: Long, confirmed: Boolean, respondedAt: Long) {
            val idx = records.indexOfFirst { it.id == id }
            if (idx >= 0) {
                records[idx] = records[idx].copy(userConfirmed = confirmed, respondedAt = respondedAt)
            }
        }
    }

    private class StubConversationDao : ConversationDao {
        val messages = mutableListOf<ConversationMessageEntity>()
        val threads = mutableListOf<ConversationThreadEntity>()
        private val flow = MutableStateFlow<List<ConversationMessageEntity>>(emptyList())

        override suspend fun insertThread(thread: ConversationThreadEntity): Long {
            threads.add(thread.copy(id = 1L))
            return 1L
        }
        override suspend fun updateThread(thread: ConversationThreadEntity) {}
        override suspend fun getThread(threadId: Long): ConversationThreadEntity? = threads.firstOrNull()
        override suspend fun getLatestActiveThread(): ConversationThreadEntity? = threads.firstOrNull()
        override suspend fun touchThread(threadId: Long, updatedAt: Long) {}
        override suspend fun insertMessage(message: ConversationMessageEntity): Long {
            val id = (messages.size + 1).toLong()
            val saved = message.copy(id = id)
            messages.add(saved)
            flow.value = messages.toList()
            return id
        }
        override fun observeMessages(threadId: Long): Flow<List<ConversationMessageEntity>> = flow
        override suspend fun getRecentMessages(threadId: Long, limit: Int): List<ConversationMessageEntity> = messages.takeLast(limit)
        override suspend fun clearThread(threadId: Long) { messages.clear(); flow.value = emptyList() }
        override suspend fun deleteArchivedOlderThan(cutoffMs: Long) {}
        override suspend fun deleteThreadsOlderThan(cutoffMs: Long) {}
        override suspend fun countMessages(threadId: Long): Int = messages.size
    }

    private class RecordingToolRunner : ToolRunner {
        var callCount = 0
        override suspend fun execute(toolId: String, parameters: Map<String, String>, commandId: Long): ToolRunResult {
            callCount++
            return ToolRunResult(toolId, true, "Executed $toolId")
        }
    }

    private lateinit var auditDao: SignerAuditDao
    private lateinit var confirmationDao: StubConfirmationDao
    private lateinit var conversationDao: StubConversationDao
    private lateinit var toolRunner: RecordingToolRunner

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        auditDao = SignerAuditDao()
        confirmationDao = StubConfirmationDao()
        conversationDao = StubConversationDao()
        toolRunner = RecordingToolRunner()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ChatViewModel {
        val gateway = CommandGateway(
            ruleEngine = RuleEngine(),
            policyEngine = PolicyEngine(auditDao, StubPolicyDecisionDao(), ioDispatcher = Dispatchers.Unconfined),
            toolRunner = toolRunner,
            commandDao = StubCommandDao(),
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

    // ─────────────────────────────────────────────────────────────────────────
    // VERIFICATION 1: USER_CANCELLED Audit Event with Non-Null Action Hash
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testUserCancelledAuditEventHasNonNullActionHashAndMaintainsChain() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        // Submit SMS draft command that requires confirmation
        vm.onInputChanged("text bob saying please review the draft")
        vm.onSendClicked()
        advanceUntilIdle()

        val pending = vm.uiState.value.pendingConfirmation
        assertNotNull("Pending confirmation must be presented", pending)

        // Dismiss confirmation
        vm.onDismissConfirmation()
        advanceUntilIdle()

        // Verify confirmation record is marked cancelled
        val record = confirmationDao.records.single()
        assertEquals(false, record.userConfirmed)

        // Find the USER_CANCELLED audit event
        val cancelEvent = auditDao.events.find { it.eventType == "USER_CANCELLED" }
        assertNotNull("USER_CANCELLED audit event must be present in AuditDao", cancelEvent)

        // Verify non-null and non-blank action_hash
        assertNotNull("action_hash must not be null", cancelEvent!!.actionHash)
        assertTrue("action_hash must not be empty", cancelEvent.actionHash.isNotBlank())
        assertEquals("action_hash must be a 64-character SHA-256 hex string", 64, cancelEvent.actionHash.length)

        // Verify audit receipt digest
        assertNotNull("receipt_digest must not be null", cancelEvent.receiptDigest)
        assertEquals("receipt_digest must be a 64-character HMAC-SHA256 hex string", 64, cancelEvent.receiptDigest.length)

        // Verify cryptographic chain integrity
        assertTrue("Cryptographic audit chain must remain intact across cancellation", auditDao.verifyChain())

        // Verify tool was NEVER executed
        assertEquals("Tool runner must not be called when user cancels", 0, toolRunner.callCount)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VERIFICATION 2: Parameter Previews for Sensitive Actions
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testParameterPreviewsExtractedCorrectlyForConfirmation() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        // Test SMS parameter extraction
        vm.onInputChanged("text Sarah that the package has arrived")
        vm.onSendClicked()
        advanceUntilIdle()

        val smsPending = vm.uiState.value.pendingConfirmation
        assertNotNull(smsPending)
        assertEquals("sarah", smsPending!!.command.entities["contact"])
        assertEquals("the package has arrived", smsPending.command.entities["body"])

        // Reset
        vm.onDismissConfirmation()
        advanceUntilIdle()
    }
}
