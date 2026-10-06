package com.agentdesk.app.e2e.harnesses

import com.agentdesk.agent.command.Command
import com.agentdesk.agent.command.CommandGateway
import com.agentdesk.agent.command.CommandOutcome
import com.agentdesk.agent.policy.PolicyEngine
import com.agentdesk.agent.rule.Intents
import com.agentdesk.agent.rule.RuleEngine
import com.agentdesk.core.common.model.CommandSource
import com.agentdesk.core.common.tools.ToolRunResult
import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.core.persistence.audit.DefaultAuditReceiptSigner
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.dao.CommandDao
import com.agentdesk.core.persistence.dao.ConfirmationDao
import com.agentdesk.core.persistence.dao.FtsSearchDao
import com.agentdesk.core.persistence.dao.NoteDao
import com.agentdesk.core.persistence.dao.PolicyDecisionDao
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.CommandRecordEntity
import com.agentdesk.core.persistence.entity.ConfirmationRecordEntity
import com.agentdesk.core.persistence.entity.NoteEntity
import com.agentdesk.core.persistence.entity.PolicyDecisionEntity
import com.agentdesk.core.persistence.entity.TextChunkEntity
import com.agentdesk.core.security.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

// ---------------------------------------------------------------------------
// In-Memory Fakes for Opaque-Box E2E Testing
// ---------------------------------------------------------------------------

class FakeCommandDao : CommandDao {
    val records = mutableListOf<CommandRecordEntity>()
    private val recordsFlow = MutableStateFlow<List<CommandRecordEntity>>(emptyList())

    override fun observeRecent(limit: Int): Flow<List<CommandRecordEntity>> =
        recordsFlow.asStateFlow().map { it.take(limit) }

    override suspend fun findByThread(threadId: Long): List<CommandRecordEntity> =
        records.filter { it.threadId == threadId }

    override suspend fun insert(record: CommandRecordEntity): Long {
        val id = (records.size + 1).toLong()
        val persisted = record.copy(id = id)
        records.add(persisted)
        recordsFlow.value = records.toList()
        return id
    }

    override suspend fun updateResult(id: Long, intentName: String, status: String, latencyMs: Long) {
        val index = records.indexOfFirst { it.id == id }
        if (index >= 0) {
            records[index] = records[index].copy(
                intentName = intentName,
                status = status,
                latencyMs = latencyMs
            )
            recordsFlow.value = records.toList()
        }
    }

    override suspend fun deleteOlderThan(beforeEpoch: Long) {
        records.removeAll { it.issuedAt < beforeEpoch }
        recordsFlow.value = records.toList()
    }
}

class FakeAuditDao(
    private val signer: DefaultAuditReceiptSigner = DefaultAuditReceiptSigner()
) : AuditDao {
    val events = mutableListOf<AuditEventEntity>()
    private val eventsFlow = MutableStateFlow<List<AuditEventEntity>>(emptyList())
    private var sequenceCounter = 0L

    override fun observeRecent(limit: Int): Flow<List<AuditEventEntity>> =
        eventsFlow.asStateFlow().map { it.take(limit) }

    override fun observeByType(eventType: String): Flow<List<AuditEventEntity>> =
        eventsFlow.asStateFlow().map { list -> list.filter { it.eventType == eventType } }

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

        val signedEvent = event.copy(
            id = sequenceCounter,
            sequenceNumber = sequenceCounter,
            prevDigest = prevDigest,
            actionHash = actionHash,
            receiptDigest = receiptDigest
        )
        events.add(signedEvent)
        eventsFlow.value = events.toList()
        return sequenceCounter
    }

    override suspend fun deleteOlderThan(beforeEpoch: Long) {
        events.removeAll { it.occurredAt < beforeEpoch }
        eventsFlow.value = events.toList()
    }

    override suspend fun getLatestEvent(): AuditEventEntity? = events.lastOrNull()

    override suspend fun getEventsBySequenceRange(startSeq: Long, endSeq: Long): List<AuditEventEntity> =
        events.filter { it.sequenceNumber in startSeq..endSeq }

    override suspend fun getEventCount(): Long = events.size.toLong()

    override suspend fun deleteAll() {
        events.clear()
        eventsFlow.value = emptyList()
        sequenceCounter = 0L
    }
}

class FakePolicyDecisionDao : PolicyDecisionDao {
    val decisions = mutableListOf<PolicyDecisionEntity>()

    override suspend fun insert(decision: PolicyDecisionEntity): Long {
        val id = (decisions.size + 1).toLong()
        decisions.add(decision.copy(id = id))
        return id
    }

    override fun observeRecent(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
    override fun observeByIntent(intent: String, limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
    override fun observeRejected(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
    override suspend fun deleteOlderThan(beforeMs: Long) {}
    override suspend fun count(): Int = decisions.size
}

class FakeConfirmationDao : ConfirmationDao {
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

class FakeNoteDao : NoteDao {
    val notes = mutableListOf<NoteEntity>()
    private val notesFlow = MutableStateFlow<List<NoteEntity>>(emptyList())

    override suspend fun insert(note: NoteEntity) {
        notes.add(note)
        notesFlow.value = notes.toList()
    }

    override fun observeAll(): Flow<List<NoteEntity>> = notesFlow.asStateFlow()

    override suspend fun search(query: String): List<NoteEntity> =
        notes.filter { it.body.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true) }

    override suspend fun deleteAll() {
        notes.clear()
        notesFlow.value = emptyList()
    }
}

class FakeFtsSearchDao : FtsSearchDao {
    val chunks = mutableListOf<TextChunkEntity>()

    override suspend fun searchChunks(sanitizedQuery: String, limit: Int): List<TextChunkEntity> {
        val terms = sanitizedQuery.split(" ").filter { it.isNotBlank() }
        return chunks.filter { chunk ->
            terms.all { term -> chunk.content.contains(term, ignoreCase = true) }
        }.take(limit)
    }

    override suspend fun countChunks(): Int = chunks.size
}

class FakeToolRunner : ToolRunner {
    val invocations = mutableListOf<ToolInvocation>()
    var defaultResponse: (toolId: String, params: Map<String, String>) -> ToolRunResult = { toolId, params ->
        ToolRunResult(
            toolId = toolId,
            success = true,
            message = "Executed $toolId with parameters $params successfully."
        )
    }

    data class ToolInvocation(
        val toolId: String,
        val parameters: Map<String, String>,
        val commandId: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    override suspend fun execute(toolId: String, parameters: Map<String, String>, commandId: Long): ToolRunResult {
        invocations.add(ToolInvocation(toolId, parameters, commandId))
        return defaultResponse(toolId, parameters)
    }
}

// ---------------------------------------------------------------------------
// E2E Test Harness Orchestrator
// ---------------------------------------------------------------------------

class E2ETestHarness {
    val signer = DefaultAuditReceiptSigner()
    val commandDao = FakeCommandDao()
    val auditDao = FakeAuditDao(signer)
    val policyDecisionDao = FakePolicyDecisionDao()
    val confirmationDao = FakeConfirmationDao()
    val noteDao = FakeNoteDao()
    val ftsSearchDao = FakeFtsSearchDao()
    val toolRunner = FakeToolRunner()
    val redactor = Redactor()
    val ruleEngine = RuleEngine()

    val policyEngine = PolicyEngine(
        auditDao = auditDao,
        policyDecisionDao = policyDecisionDao,
        ioDispatcher = Dispatchers.Unconfined
    )

    val gateway = CommandGateway(
        ruleEngine = ruleEngine,
        policyEngine = policyEngine,
        toolRunner = toolRunner,
        commandDao = commandDao,
        auditDao = auditDao,
        redactor = redactor,
        dispatcher = Dispatchers.Unconfined
    )

    suspend fun submitText(
        input: String,
        source: CommandSource = CommandSource.USER_CHAT,
        threadId: Long? = null
    ): CommandOutcome = gateway.submitText(input, source, threadId)

    suspend fun executeConfirmed(
        command: Command,
        threadId: Long? = null
    ): CommandOutcome = gateway.executeConfirmed(command, threadId)

    fun verifyAuditChainIntegrity(): Boolean = signer.verifyChain(auditDao.events)
}
