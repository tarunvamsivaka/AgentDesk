package com.agentdesk.app.e2e.harnesses

import com.agentdesk.agent.command.Command
import com.agentdesk.agent.command.CommandGateway
import com.agentdesk.agent.command.CommandOutcome
import com.agentdesk.agent.policy.PolicyEngine
import com.agentdesk.agent.rule.Intents
import com.agentdesk.agent.rule.RuleEngine
import com.agentdesk.core.common.model.CommandSource
import com.agentdesk.core.common.model.DeviceTier
import com.agentdesk.core.common.tools.ToolRunResult
import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.core.device.BatteryState
import com.agentdesk.core.device.DeviceProfiler
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
// Telemetry & Hardware Governance Fakes (Phases 6–9)
// ---------------------------------------------------------------------------

open class BatterySimulatedProfiler(
    var simPercent: Int = 85,
    var simCharging: Boolean = false,
    var simThermal: Int = 0,
    var simAvailableStorageMb: Long = 10_000L,
    var simAvailableRamMb: Long = 4_096L,
    var simLowMemory: Boolean = false,
    var simTier: DeviceTier = DeviceTier.FULL
) : DeviceProfiler() {
    override fun getBatteryPercent(): Int = simPercent
    override fun isCharging(): Boolean = simCharging
    override fun getThermalStatus(): Int = simThermal
    override fun getAvailableStorageMb(): Long = simAvailableStorageMb
    override fun getAvailableRamMb(): Long = simAvailableRamMb
    override fun isLowMemory(): Boolean = simLowMemory
    override fun classifyTier(): DeviceTier = simTier
    override fun isBatteryLow(): Boolean = simPercent < 20 && !simCharging

    override fun observeThermalStatus(): Flow<Int> = kotlinx.coroutines.flow.flowOf(simThermal)
    override fun observeBatteryState(): Flow<BatteryState> = kotlinx.coroutines.flow.flowOf(
        BatteryState(percent = simPercent, isCharging = simCharging)
    )
}

class GovernorContractEvaluator(private val profiler: DeviceProfiler) {
    companion object {
        const val MIN_STORAGE_FOR_INDEX_MB = 500L
        const val MIN_RAM_FOR_LLM_MB = 1024L
        const val THERMAL_THRESHOLD_PAUSE = 3
        const val BATTERY_THRESHOLD_PAUSE = 20
    }

    fun canIndex(): Boolean {
        if (profiler.isLowMemory()) return false
        if (profiler.getAvailableStorageMb() < MIN_STORAGE_FOR_INDEX_MB) return false
        if (profiler.getThermalStatus() >= THERMAL_THRESHOLD_PAUSE) return false
        if (profiler.isBatteryLow()) return false
        return true
    }

    fun canRunLlm(): Boolean {
        val tier = profiler.classifyTier()
        if (tier == DeviceTier.EMERGENCY || tier == DeviceTier.LITE) return false
        if (profiler.getAvailableRamMb() < MIN_RAM_FOR_LLM_MB) return false
        if (profiler.getThermalStatus() >= THERMAL_THRESHOLD_PAUSE) return false
        if (profiler.isBatteryLow()) return false
        return true
    }

    fun canUseRichUi(): Boolean {
        return profiler.classifyTier() != DeviceTier.EMERGENCY
    }

    fun currentConstraintReason(): String? {
        return when {
            profiler.isLowMemory() -> "Device is low on memory."
            profiler.getAvailableStorageMb() < MIN_STORAGE_FOR_INDEX_MB ->
                "Available storage is below ${MIN_STORAGE_FOR_INDEX_MB}MB."
            profiler.getThermalStatus() >= THERMAL_THRESHOLD_PAUSE ->
                "Device is too hot. Pausing heavy operations."
            profiler.isBatteryLow() ->
                "Battery is below ${BATTERY_THRESHOLD_PAUSE}% and discharging. Pausing heavy operations."
            else -> null
        }
    }

    fun observeCanIndex(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(canIndex())
    fun observeCanRunLlm(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(canRunLlm())
}

// ---------------------------------------------------------------------------
// Ambient Habit Engine Fakes (Phases 7–9)
// ---------------------------------------------------------------------------

data class UserHabitRecord(
    val id: Long = 0,
    val targetIntent: String,
    val hourOfDay: Int,
    val frequency: Int = 1,
    val confidenceWeight: Float = 0.5f,
    val lastInvokedAt: Long = System.currentTimeMillis()
)

class FakeUserHabitDao {
    val habits = mutableListOf<UserHabitRecord>()
    private var nextId = 1L

    suspend fun insertOrUpdate(record: UserHabitRecord): Long {
        val existingIndex = habits.indexOfFirst {
            it.targetIntent == record.targetIntent && it.hourOfDay == record.hourOfDay
        }
        return if (existingIndex >= 0) {
            val existing = habits[existingIndex]
            val updated = existing.copy(
                frequency = existing.frequency + record.frequency,
                confidenceWeight = minOf(1.0f, existing.confidenceWeight + 0.1f),
                lastInvokedAt = System.currentTimeMillis()
            )
            habits[existingIndex] = updated
            updated.id
        } else {
            val newRecord = record.copy(id = nextId++)
            habits.add(newRecord)
            newRecord.id
        }
    }

    suspend fun getTopHabitsForHour(hourOfDay: Int, limit: Int = 3): List<UserHabitRecord> =
        habits.filter { it.hourOfDay == hourOfDay }
            .sortedByDescending { it.frequency * it.confidenceWeight }
            .take(limit)

    fun observeTopHabitsForHour(hourOfDay: Int, limit: Int = 3): Flow<List<UserHabitRecord>> =
        kotlinx.coroutines.flow.flowOf(
            habits.filter { it.hourOfDay == hourOfDay }
                .sortedByDescending { it.frequency * it.confidenceWeight }
                .take(limit)
        )
}

class FakeHabitPredictionEngine(private val habitDao: FakeUserHabitDao) {
    suspend fun predictBoost(hourOfDay: Int, input: String): String? {
        val topHabits = habitDao.getTopHabitsForHour(hourOfDay)
        val normalized = input.trim().lowercase()
        for (habit in topHabits) {
            if (habit.targetIntent == Intents.CALENDAR_CREATE &&
                (normalized.contains("standup") || normalized.contains("sync") || normalized.contains("meeting"))) {
                return Intents.CALENDAR_CREATE
            }
            if (habit.targetIntent == Intents.SET_ALARM &&
                (normalized.contains("morning") || normalized.contains("sleep") || normalized.contains("night"))) {
                return Intents.SET_ALARM
            }
        }
        return null
    }

    suspend fun getSuggestions(hourOfDay: Int): List<String> {
        val top = habitDao.getTopHabitsForHour(hourOfDay)
        return if (top.isNotEmpty()) {
            top.map { "Suggested: ${it.targetIntent}" }
        } else when (hourOfDay) {
            in 6..10 -> listOf("Schedule standup meeting", "Check morning agenda")
            in 20..23 -> listOf("Set alarm for 7am", "Check device battery")
            else -> listOf("Search Library", "Set Alarm", "Create Note")
        }
    }
}

// ---------------------------------------------------------------------------
// Dynamic Batching & LiteRT Embedding Simulator (Phase 7)
// ---------------------------------------------------------------------------

class DynamicBatchEmbeddingSimulator(
    val dimension: Int = 384,
    val modelVersion: String = "bge-small-en-v1.5-int8"
) {
    val batchInvocations = mutableListOf<List<String>>()

    fun generateEmbedding(text: String): FloatArray {
        val raw = FloatArray(dimension) { i ->
            val h = (text.hashCode() * (i + 1) * 31).toFloat()
            (h % 1000f) / 1000f
        }
        return normalize(raw)
    }

    fun generateEmbeddings(texts: List<String>, maxBatchSize: Int = 16): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val results = mutableListOf<FloatArray>()
        texts.chunked(maxBatchSize).forEach { batch ->
            batchInvocations.add(batch)
            for (t in batch) {
                results.add(generateEmbedding(t))
            }
        }
        return results
    }

    fun normalize(v: FloatArray): FloatArray {
        var sumSq = 0f
        for (x in v) sumSq += x * x
        val norm = kotlin.math.sqrt(sumSq)
        return if (norm > 0f) FloatArray(v.size) { i -> v[i] / norm } else v
    }
}

// ---------------------------------------------------------------------------
// Zero-Cloud Network Egress Sandbox Fake (Phase 8)
// ---------------------------------------------------------------------------

class FakeZeroCloudEgressSandbox {
    var outboundBytes: Long = 0
    var inboundBytes: Long = 0
    var socketAttempts: Int = 0

    suspend fun <T> executeWithZeroEgressAssertion(block: suspend () -> T): T {
        val startOutbound = outboundBytes
        val result = block()
        if (outboundBytes > startOutbound) {
            throw SecurityException("ZeroCloudEgressSandbox violation: attempted $outboundBytes bytes egress")
        }
        return result
    }

    fun recordSocketAttempt(destinationHost: String, port: Int) {
        socketAttempts++
        throw SecurityException("ZeroCloudEgressSandbox: Sockets to $destinationHost:$port are prohibited by policy.")
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

    val profiler = BatterySimulatedProfiler(simPercent = 85, simCharging = false)
    val resourceGovernor = GovernorContractEvaluator(profiler)
    val userHabitDao = FakeUserHabitDao()
    val embeddingGenerator = DynamicBatchEmbeddingSimulator()
    val habitEngine = FakeHabitPredictionEngine(userHabitDao)
    val sandbox = FakeZeroCloudEgressSandbox()

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
        threadId: Long? = null,
        thermalThrottled: Boolean? = null
    ): CommandOutcome = sandbox.executeWithZeroEgressAssertion {
        val isThrottled = thermalThrottled ?: !resourceGovernor.canRunLlm()
        gateway.submitText(input, source, threadId, isThrottled)
    }

    suspend fun executeConfirmed(
        command: Command,
        threadId: Long? = null
    ): CommandOutcome = sandbox.executeWithZeroEgressAssertion {
        gateway.executeConfirmed(command, threadId)
    }

    fun verifyAuditChainIntegrity(): Boolean = signer.verifyChain(auditDao.events)
}
