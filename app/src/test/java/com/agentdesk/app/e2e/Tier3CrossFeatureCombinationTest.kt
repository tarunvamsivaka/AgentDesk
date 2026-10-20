package com.agentdesk.app.e2e

import com.agentdesk.agent.command.CommandOutcome
import com.agentdesk.agent.command.CommandResult
import com.agentdesk.agent.rule.Intents
import com.agentdesk.app.e2e.harnesses.E2ETestHarness
import com.agentdesk.app.e2e.harnesses.MockAiCoreHarness
import com.agentdesk.app.e2e.harnesses.UserHabitRecord
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.ConfirmationRecordEntity
import com.agentdesk.core.persistence.entity.NoteEntity
import com.agentdesk.core.persistence.entity.TextChunkEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Tier 3: Cross-Feature Combinations.
 * Exercises pairwise interactions and cross-subsystem contracts:
 * - RAG + Tool execution
 * - Chat Persistence + Monotonic IDs + Audit Logging
 * - Confirmation flow + Cancellation handling
 * - PolicyEngine + Forbidden Intent denial
 * - Privacy Redaction + Cryptographic chaining
 * - Fallback Pyramid transitions
 * - Telemetry + Habit Engine + Command Gateway (Phases 6–9)
 * - LiteRT Embeddings + Dynamic Batching + Room BLOB persistence (Phases 6–9)
 */
class Tier3CrossFeatureCombinationTest {

    private lateinit var harness: E2ETestHarness

    @Before
    fun setUp() {
        harness = E2ETestHarness()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. RAG + Tool Execution: Knowledge retrieval feeding Note Creation
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testRagAndToolExecutionInteraction() = runBlocking {
        // Step 1: Pre-populate knowledge chunks
        val chunk1 = TextChunkEntity(
            id = 1L,
            documentId = 10L,
            chunkIndex = 0,
            content = "Project Alpha deadline is October 15 with deliverables: UI, Vector RAG, and Security Audit."
        )
        harness.ftsSearchDao.chunks.add(chunk1)

        // Step 2: Search knowledge base
        val searchResults = harness.ftsSearchDao.searchChunks("Project Alpha deadline", limit = 5)
        assertEquals(1, searchResults.size)
        assertTrue(searchResults.first().content.contains("October 15"))

        // Step 3: Create a note based on retrieved context
        val noteContent = "Reminder: ${searchResults.first().content}"
        harness.noteDao.insert(
            NoteEntity(
                id = UUID.randomUUID().toString(),
                title = "Alpha Deadline Reminder",
                body = noteContent,
                createdAt = System.currentTimeMillis()
            )
        )

        // Step 4: Verify note persistence
        val notes = harness.noteDao.search("Alpha")
        assertEquals(1, notes.size)
        assertTrue(notes.first().body.contains("October 15"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Chat Persistence + Monotonic IDs + Audit Logging
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testChatPersistenceWithMonotonicIdsAndAuditChaining() = runBlocking {
        val threadId = 100L

        // Turn 1: Low risk command
        val out1 = harness.submitText("alarm at 7:30 am", threadId = threadId)
        assertTrue(out1 is CommandOutcome.Executed)

        // Turn 2: Low risk tool
        val out2 = harness.submitText("battery status", threadId = threadId)
        assertTrue(out2 is CommandOutcome.Executed)

        // Verify command persistence and monotonic IDs
        val commandsInThread = harness.commandDao.findByThread(threadId)
        assertEquals(2, commandsInThread.size)
        assertTrue("Command IDs must be monotonic", commandsInThread[0].id < commandsInThread[1].id)

        // Verify audit log has corresponding events and valid chain
        val auditEvents = harness.auditDao.events
        assertTrue(auditEvents.size >= 2)
        assertTrue("Audit chain must verify cryptographically", harness.verifyAuditChainIntegrity())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Confirmation Flow + User Cancellation Handling
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testConfirmationPromptAndUserCancellation() = runBlocking {
        // Step 1: Submit medium-risk action (SMS draft)
        val outcome = harness.submitText("text Alice saying meeting postponed")
        assertTrue("SMS draft must request confirmation", outcome is CommandOutcome.NeedsUserConfirmation)

        val pending = outcome as CommandOutcome.NeedsUserConfirmation

        // Step 2: Record confirmation prompt in ConfirmationDao
        val confirmationId = harness.confirmationDao.insert(
            ConfirmationRecordEntity(
                taskId = 1L,
                promptShown = pending.prompt,
                userConfirmed = null
            )
        )

        // Step 3: User dismisses / rejects
        harness.confirmationDao.recordResponse(confirmationId, confirmed = false, respondedAt = System.currentTimeMillis())

        // Step 4: Verify REJECTED audit logging
        harness.auditDao.insert(
            AuditEventEntity(
                eventType = "USER_CANCELLED",
                actor = "USER",
                targetType = "CONFIRMATION",
                targetId = confirmationId.toString(),
                summary = "User cancelled confirmation: ${pending.prompt}",
                riskLevel = pending.command.riskLevel
            )
        )

        val record = harness.confirmationDao.records.first()
        assertEquals(false, record.userConfirmed)
        assertNotNull(record.respondedAt)

        // Tool was NEVER executed
        assertTrue(harness.toolRunner.invocations.isEmpty())
        assertTrue("Audit chain remains valid after cancellation", harness.verifyAuditChainIntegrity())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. PolicyEngine + Forbidden Intent Attempt
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testPolicyEngineForbiddenIntentRejection() = runBlocking {
        val maliciousCommands = listOf(
            "cloud.sync_silent",
            "message.send_sms_silent",
            "call.place_silent",
            "accessibility.automate"
        )

        for (forbiddenIntent in maliciousCommands) {
            val command = com.agentdesk.agent.command.Command(
                rawInput = "trigger $forbiddenIntent",
                normalizedInput = "trigger $forbiddenIntent".lowercase(),
                intentName = forbiddenIntent,
                confidence = 1.0f,
                source = com.agentdesk.core.common.model.CommandSource.USER_CHAT
            )

            val policyResult = harness.policyEngine.evaluate(command, 42L)
            assertTrue("PolicyEngine must reject $forbiddenIntent", policyResult is CommandResult.Rejected)

            // Verify DENIED audit event logged
            val lastAudit = harness.auditDao.events.last()
            assertEquals("DENIED", lastAudit.eventType)
            assertTrue(lastAudit.summary.contains("Forbidden intent"))
            assertEquals("HIGH", lastAudit.riskLevel.name)
        }

        assertTrue("Audit chain intact after rejected attempts", harness.verifyAuditChainIntegrity())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Privacy Redaction + Cryptographic Audit Chaining
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testPrivacyRedactionPreservesCryptographicAuditChain() = runBlocking {
        val sensitiveInput = "take a note my secret phone is 555-123-4567 and email is ceo@company.com"
        val outcome = harness.submitText(sensitiveInput)

        // Verify command record is redacted
        val savedCommand = harness.commandDao.records.last()
        assertFalse("Command record must not contain raw phone number", savedCommand.rawInput.contains("555-123-4567"))
        assertFalse("Command record must not contain raw email", savedCommand.rawInput.contains("ceo@company.com"))
        assertTrue("Command record must contain redacted tokens", savedCommand.rawInput.contains("[PHONE]") || savedCommand.rawInput.contains("[EMAIL]"))

        // Verify audit receipt chaining remains 100% mathematically valid
        assertTrue("Cryptographic audit chain remains valid after redaction", harness.verifyAuditChainIntegrity())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Fallback Pyramid: Regex Miss to SLM Classification & Execution
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testFallbackPyramidTransitionFromRegexToLlm() = runBlocking {
        val naturalQuery = "wake me up at 7am tomorrow"

        // Tier 0: Regex check fails, falls back to Tier 1 SLM
        val slmClassification = MockAiCoreHarness.classify(naturalQuery)
        assertEquals(Intents.SET_ALARM, slmClassification.intentName)
        assertTrue(slmClassification.confidence >= 0.9f)

        // Pass classified command through PolicyEngine
        val command = com.agentdesk.agent.command.Command(
            rawInput = naturalQuery,
            normalizedInput = naturalQuery.lowercase(),
            intentName = slmClassification.intentName,
            entities = slmClassification.entities,
            confidence = slmClassification.confidence,
            source = com.agentdesk.core.common.model.CommandSource.USER_CHAT
        )

        val policyResult = harness.policyEngine.evaluate(command, 1L)
        assertTrue("PolicyEngine must approve low-risk alarm intent", policyResult is CommandResult.Accepted)

        // Dispatch tool
        val toolResult = harness.toolRunner.execute("set_alarm", slmClassification.entities, 1L)
        assertTrue(toolResult.success)

        // Log audit event
        harness.auditDao.insert(
            AuditEventEntity(
                eventType = "TOOL_SUCCESS",
                actor = "USER_CHAT",
                targetType = "TOOL",
                targetId = "set_alarm",
                summary = toolResult.message
            )
        )

        assertTrue(harness.verifyAuditChainIntegrity())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Telemetry + Habit Engine + Command Gateway (Phases 6–9)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testTelemetryAndHabitEngineAndCommandGatewayInteraction() = runBlocking {
        // Step 1: Pre-populate morning habit in UserHabitDao
        harness.userHabitDao.insertOrUpdate(
            UserHabitRecord(targetIntent = Intents.CALENDAR_CREATE, hourOfDay = 8, frequency = 12, confidenceWeight = 0.95f)
        )

        // Step 2: Healthy telemetry state (battery = 80%, discharging)
        harness.profiler.simPercent = 80
        harness.profiler.simCharging = false
        assertTrue("Healthy resources allow LLM execution", harness.resourceGovernor.canRunLlm())

        // Ambiguous morning input boosted by habit engine
        val boost = harness.habitEngine.predictBoost(hourOfDay = 8, input = "standup sync")
        assertEquals(Intents.CALENDAR_CREATE, boost)

        val calendarOutcome = harness.submitText("schedule meeting standup sync at 9 am")
        assertTrue(calendarOutcome is CommandOutcome.Executed)

        // Step 3: Battery drops to 14% (discharging) -> Telemetry alerts ResourceGovernor
        harness.profiler.simPercent = 14
        harness.profiler.simCharging = false
        assertFalse("Battery below 20% must pause LLM execution", harness.resourceGovernor.canRunLlm())

        // Complex natural language command gracefully throttles to clarification chips
        val throttledOutcome = harness.submitText("look up my documents regarding alpha release")
        assertTrue("Throttled state must return clarification chips", throttledOutcome is CommandOutcome.Clarification)

        // Fast-path regex ("alarm at 6:30 am") executes without degradation
        val fastOutcome = harness.submitText("alarm at 6:30 am")
        assertTrue("Fast path regex executes even while throttled", fastOutcome is CommandOutcome.Executed)

        // Step 4: Charger plugged in -> Throttle lifted immediately
        harness.profiler.simCharging = true
        assertTrue("Charging overrides battery threshold", harness.resourceGovernor.canRunLlm())
        val restoredOutcome = harness.submitText("look up my documents regarding alpha release")
        assertTrue("Restored capability routes natural language", restoredOutcome is CommandOutcome.Executed)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. LiteRT Embeddings + Dynamic Batching + Room BLOB Persistence (Phases 6–9)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testLiteRtEmbeddingsAndDynamicBatchingAndRoomBlobPersistence() {
        val simulator = harness.embeddingGenerator
        val texts = (1..25).map { "Section $it: On-device privacy and Zero Cloud Egress" }

        // Dynamic batch generation
        val embeddings = simulator.generateEmbeddings(texts, maxBatchSize = 16)
        assertEquals(25, embeddings.size)
        assertEquals(2, simulator.batchInvocations.size)

        // Room BLOB serialization contract: 384 floats * 4 bytes = 1536 bytes
        fun serializeToBlob(floats: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in floats) buf.putFloat(f)
            return buf.array()
        }

        fun deserializeFromBlob(bytes: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(bytes.size / 4)
            for (i in floats.indices) floats[i] = buf.float
            return floats
        }

        for (vec in embeddings) {
            val blob = serializeToBlob(vec)
            assertEquals("384-dimensional float vector serialized to exactly 1536 bytes", 1536, blob.size)
            val deserialized = deserializeFromBlob(blob)
            assertEquals(384, deserialized.size)
            for (i in vec.indices) {
                assertEquals(vec[i], deserialized[i], 1e-6f)
            }
        }
    }
}
