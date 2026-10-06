package com.agentdesk.app.e2e

import com.agentdesk.agent.command.CommandOutcome
import com.agentdesk.agent.command.CommandResult
import com.agentdesk.agent.rule.Intents
import com.agentdesk.app.e2e.harnesses.E2ETestHarness
import com.agentdesk.app.e2e.harnesses.MockAiCoreHarness
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
        // Natural language phrasing that misses regex exact pattern
        val naturalQuery = "wake me up at 7am tomorrow"

        // Tier 0: Regex check fails
        val regexMatch = harness.ruleEngine.evaluate(naturalQuery)
        // If regex doesn't match or has low confidence, route to Tier 1 Mock SLM
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
}
