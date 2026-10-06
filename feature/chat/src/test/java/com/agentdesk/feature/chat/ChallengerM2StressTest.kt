package com.agentdesk.feature.chat

import com.agentdesk.agent.command.CommandGateway
import com.agentdesk.agent.policy.PolicyEngine
import com.agentdesk.agent.rule.RuleEngine
import com.agentdesk.core.common.model.CommandSource
import com.agentdesk.core.common.tools.ToolRunResult
import com.agentdesk.core.common.tools.ToolRunner
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
 * Empirical Adversarial Challenger Test Suite for Milestone 2 (Phase 3 Chat & Tools):
 *
 * 1. Monotonic ID stability & collision-free LazyColumn key invariants under rapid message bursts
 * 2. Multi-turn dialogue persistence across multiple ViewModel recreations
 * 3. ConversationPruner stress under extreme overflow (>1,000 messages) and huge text buffers (>50KB)
 * 4. Multi-turn confirmation cycles, dismissal audit invariants, and thread boundary isolation
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChallengerM2StressTest {

    // ─── Test Fakes ───────────────────────────────────────────────────────────

    private class ChallengerFakeCommandDao : CommandDao {
        override fun observeRecent(limit: Int): Flow<List<CommandRecordEntity>> = emptyFlow()
        override suspend fun findByThread(threadId: Long): List<CommandRecordEntity> = emptyList()
        override suspend fun insert(record: CommandRecordEntity): Long = 1L
        override suspend fun updateResult(id: Long, intentName: String, status: String, latencyMs: Long) {}
        override suspend fun deleteOlderThan(beforeEpoch: Long) {}
    }

    private class ChallengerFakeAuditDao : AuditDao {
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

    private class ChallengerFakePolicyDecisionDao : PolicyDecisionDao {
        override suspend fun insert(decision: PolicyDecisionEntity): Long = 1L
        override fun observeRecent(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
        override fun observeByIntent(intent: String, limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
        override fun observeRejected(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
        override suspend fun deleteOlderThan(beforeMs: Long) {}
        override suspend fun count(): Int = 0
    }

    private class ChallengerFakeToolRunner : ToolRunner {
        var lastToolExecuted: String? = null
        var executionCount = 0

        override suspend fun execute(
            toolId: String,
            parameters: Map<String, String>,
            commandId: Long
        ): ToolRunResult {
            lastToolExecuted = toolId
            executionCount++
            return ToolRunResult(toolId, true, "Execution successful for $toolId")
        }
    }

    private class ChallengerFakeConfirmationDao : ConfirmationDao {
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

        override suspend fun recordResponse(id: Long, confirmed: Boolean, respondedAt: Long) {
            val index = records.indexOfFirst { it.id == id }
            if (index >= 0) {
                records[index] = records[index].copy(
                    userConfirmed = confirmed,
                    respondedAt = respondedAt
                )
            }
        }
    }

    private class ChallengerFakeConversationDao : ConversationDao {
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
            messagesFlow.value = messages.filter { it.threadId == message.threadId }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
            return id
        }

        override fun observeMessages(threadId: Long): Flow<List<ConversationMessageEntity>> = messagesFlow

        override suspend fun getRecentMessages(threadId: Long, limit: Int): List<ConversationMessageEntity> =
            messages.filter { it.threadId == threadId }
                .sortedWith(compareByDescending<ConversationMessageEntity> { it.createdAt }.thenByDescending { it.id })
                .take(limit)

        override suspend fun clearThread(threadId: Long) {
            messages.removeAll { it.threadId == threadId }
            messagesFlow.value = emptyList()
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

    // ─── Environment Setup ────────────────────────────────────────────────────

    private lateinit var confirmationDao: ChallengerFakeConfirmationDao
    private lateinit var conversationDao: ChallengerFakeConversationDao
    private lateinit var auditDao: ChallengerFakeAuditDao
    private lateinit var toolRunner: ChallengerFakeToolRunner

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        confirmationDao = ChallengerFakeConfirmationDao()
        conversationDao = ChallengerFakeConversationDao()
        auditDao = ChallengerFakeAuditDao()
        toolRunner = ChallengerFakeToolRunner()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ChatViewModel {
        val policyDecisionDao = ChallengerFakePolicyDecisionDao()
        val gateway = CommandGateway(
            ruleEngine = RuleEngine(),
            policyEngine = PolicyEngine(auditDao, policyDecisionDao, ioDispatcher = Dispatchers.Unconfined),
            toolRunner = toolRunner,
            commandDao = ChallengerFakeCommandDao(),
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

    // ──────────────────────────────────────────────────────────────────────────
    // CHALLENGE 1: Monotonic ID Stability & LazyColumn Unique Key Invariant
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testMonotonicLazyColumnIds() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        // Send 50 sequential commands (generating 50 user + 50 assistant messages)
        val commands = listOf("battery status", "timer for 1 minute", "open settings", "alarm at 8am")
        for (i in 1..50) {
            val cmd = commands[i % commands.size]
            vm.onInputChanged(cmd)
            vm.onSendClicked()
            advanceUntilIdle()
        }

        val messages = vm.uiState.value.messages
        assertEquals("Must produce exactly 100 messages (50 user + 50 assistant)", 100, messages.size)

        val ids = messages.map { it.id }

        // Empirical assertion 1: Zero duplicate IDs
        val distinctCount = ids.distinct().size
        assertEquals("Every message ID must be unique (distinct IDs == total IDs)", messages.size, distinctCount)

        // Empirical assertion 2: Strictly monotonic ordering
        for (i in 0 until ids.size - 1) {
            assertTrue(
                "ID at index $i (${ids[i]}) must be strictly smaller than index ${i + 1} (${ids[i + 1]})",
                ids[i] < ids[i + 1]
            )
        }

        // Empirical assertion 3: Verify Compose LazyColumn key selector produces unique keys
        val keySet = HashSet<Long>()
        for (msg in messages) {
            val added = keySet.add(msg.id)
            assertTrue("LazyColumn key collision detected for message ID ${msg.id}", added)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CHALLENGE 2: Multi-Turn Conversation Survival Across ViewModel Recreations
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testConversationSurvivesRecreation() = runTest {
        // Generation 1: Initial conversation
        var vm = createViewModel()
        advanceUntilIdle()

        vm.onInputChanged("alarm at 7am")
        vm.onSendClicked()
        advanceUntilIdle()

        vm.onInputChanged("battery status")
        vm.onSendClicked()
        advanceUntilIdle()

        val gen1Messages = vm.uiState.value.messages
        assertEquals(4, gen1Messages.size)
        val gen1LastId = gen1Messages.last().id

        // Generation 2: Simulate process recreation / navigation return
        vm = createViewModel()
        advanceUntilIdle()

        val gen2Messages = vm.uiState.value.messages
        assertEquals("Gen 2 must restore exact message count", 4, gen2Messages.size)
        assertEquals("Gen 2 must restore exact IDs", gen1Messages.map { it.id }, gen2Messages.map { it.id })
        assertEquals("Gen 2 must restore exact texts", gen1Messages.map { it.text }, gen2Messages.map { it.text })

        // Add more messages in Generation 2
        vm.onInputChanged("timer for 5 minutes")
        vm.onSendClicked()
        advanceUntilIdle()

        val gen2Updated = vm.uiState.value.messages
        assertEquals(6, gen2Updated.size)
        val gen2LastId = gen2Updated.last().id
        assertTrue("Subsequent messages after recreation must have strictly higher ID", gen2LastId > gen1LastId)

        // Generation 3: Simulate second recreation after confirmation flow
        vm.onInputChanged("text alice saying meet me at the cafe")
        vm.onSendClicked()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.pendingConfirmation)
        vm.onConfirmAction()
        advanceUntilIdle()

        val gen2FinalMessages = vm.uiState.value.messages
        val gen2FinalCount = gen2FinalMessages.size

        // Recreate Generation 3
        vm = createViewModel()
        advanceUntilIdle()

        val gen3Messages = vm.uiState.value.messages
        assertEquals("Gen 3 must restore all persisted messages including confirmation reply", gen2FinalCount, gen3Messages.size)
        assertEquals(gen2FinalMessages.map { it.id }, gen3Messages.map { it.id })

        // Check monotonicity across entire restored history
        val allIds = gen3Messages.map { it.id }
        for (i in 0 until allIds.size - 1) {
            assertTrue(allIds[i] < allIds[i + 1])
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CHALLENGE 3: ConversationPruner Under Extreme Overflow (>1000 messages)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testPruner1000MessageBurst() {
        val largeList = (1..1000).map { i ->
            ConversationMessageEntity(
                id = i.toLong(),
                threadId = 1L,
                role = if (i % 2 == 1) "user" else "assistant",
                content = "Turn message payload #$i",
                createdAt = 10000L + i * 100L
            )
        }

        val pruned = ConversationPruner.prune(largeList, maxTurns = 6)
        assertEquals("Must retain exactly 12 messages under 1000 message overflow", 12, pruned.size)

        // Must retain messages 989 to 1000
        assertEquals(989L, pruned.first().id)
        assertEquals(1000L, pruned.last().id)

        // Strict chronological ordering
        for (i in 0 until pruned.size - 1) {
            assertTrue("Message at $i must be strictly earlier than ${i + 1}", pruned[i].createdAt < pruned[i + 1].createdAt)
            assertTrue("ID at $i must be strictly smaller than ${i + 1}", pruned[i].id < pruned[i + 1].id)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CHALLENGE 4: ConversationPruner Character Buffer Safety & Extreme Payloads
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testPrunerCharacterBudgetCeiling() {
        // Test with massive 50,000-character payload
        val hugePayload = "X".repeat(50_000)
        val singleHugeMessage = listOf(
            ConversationMessageEntity(
                id = 1L,
                threadId = 1L,
                role = "user",
                content = hugePayload,
                createdAt = 1000L
            )
        )

        // Even when a single message exceeds MAX_CONTEXT_CHARS, pruner must safely retain it without crash or infinite loop
        val prunedHuge = ConversationPruner.prune(singleHugeMessage, maxTurns = 6)
        assertEquals("Pruner must retain single latest message even if exceeding char budget", 1, prunedHuge.size)
        assertEquals(1L, prunedHuge.first().id)

        // Test with 10 messages of 1,500 characters each (= 15,000 total characters, budget is 8,192)
        val longMessages = (1..10).map { i ->
            ConversationMessageEntity(
                id = i.toLong(),
                threadId = 1L,
                role = if (i % 2 == 1) "user" else "assistant",
                content = "[$i] " + "Z".repeat(1500),
                createdAt = i * 1000L
            )
        }

        val prunedLong = ConversationPruner.prune(longMessages, maxTurns = 6, maxChars = 8192)
        // 5 messages * ~1504 = ~7520 <= 8192, 6 messages would be ~9024 > 8192
        assertTrue("Pruned count must be bounded by budget: size=${prunedLong.size}", prunedLong.size in 4..6)
        val totalChars = prunedLong.sumOf { it.content.length }
        assertTrue("Total characters ($totalChars) must be <= 8192", totalChars <= 8192)
        assertEquals("Latest message (#10) must always be retained", 10L, prunedLong.last().id)

        // Verify chronological order is strictly preserved
        for (i in 0 until prunedLong.size - 1) {
            assertTrue(prunedLong[i].createdAt < prunedLong[i + 1].createdAt)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CHALLENGE 5: Dismissal Flow Audit Receipt & Tool Runner Safety
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testDismissalAuditLogging() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onInputChanged("text charlie saying top secret details")
        vm.onSendClicked()
        advanceUntilIdle()

        val pending = vm.uiState.value.pendingConfirmation
        assertNotNull("SMS intent must trigger confirmation dialog", pending)
        assertEquals(0, toolRunner.executionCount)

        // User dismisses / rejects
        vm.onDismissConfirmation()
        advanceUntilIdle()

        // Tool runner must NOT have executed
        assertEquals("Tool runner must NEVER execute on dismissal", 0, toolRunner.executionCount)

        // Confirmation record in DAO must be marked userConfirmed = false
        val confRecord = confirmationDao.records.single()
        assertEquals(false, confRecord.userConfirmed)

        // AuditDao MUST contain USER_CANCELLED event
        val cancelEvent = auditDao.events.find { it.eventType == "USER_CANCELLED" }
        assertNotNull("USER_CANCELLED audit event is mandatory", cancelEvent)
        assertEquals("USER", cancelEvent?.actor)
        assertEquals("message.draft_sms", cancelEvent?.targetId)

        // Persisted dialogue contains cancellation notice
        val lastMsg = vm.uiState.value.messages.last()
        assertEquals("Action cancelled.", lastMsg.text)
        assertEquals(MessageSender.AGENT, lastMsg.sender)
    }
}
