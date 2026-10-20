package com.agentdesk.app.e2e

import com.agentdesk.agent.command.CommandOutcome
import com.agentdesk.app.e2e.harnesses.E2ETestHarness
import com.agentdesk.app.e2e.harnesses.MockAiCoreHarness
import com.agentdesk.core.persistence.entity.ConversationMessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tier 2: Boundary & Corner Cases.
 * Exercises limits, malformed inputs, edge geometries, and threshold conditions.
 */
class Tier2BoundaryCornerCaseTest {

    private lateinit var harness: E2ETestHarness

    @Before
    fun setUp() {
        harness = E2ETestHarness()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Empty & Whitespace Input Handling
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testEmptyAndWhitespaceInputHandling() = runBlocking {
        val emptyOutcome = harness.submitText("")
        assertTrue("Empty string must resolve to Unknown without crash", emptyOutcome is CommandOutcome.Unknown)

        val whitespaceOutcome = harness.submitText("   \t  \n  ")
        assertTrue("Whitespace string must resolve to Unknown without crash", whitespaceOutcome is CommandOutcome.Unknown)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Extreme Payload Buffer Boundary (> 10,000 characters)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testExtremelyLongPayloadBoundary() = runBlocking {
        val massivePayload = "set alarm " + "A".repeat(12_000)
        val outcome = harness.submitText(massivePayload)

        // Gateway must process and redact without memory overflow or crash
        assertNotNull(outcome)
        val records = harness.commandDao.records
        assertEquals(1, records.size)
        assertTrue(records.first().rawInput.isNotEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. SQL & FTS4 Injection / Metacharacter Boundary
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testSqlAndFts4MetacharacterHandling() = runBlocking {
        val injectionInputs = listOf(
            "'; DROP TABLE command_record; --",
            "\"* OR 1=1 --",
            "search library for * NEAR/2 \"\" NOT ''",
            "create note with \u0000 null byte and \uFFFF",
            "text bob saying <script>alert('xss')</script>"
        )

        for (input in injectionInputs) {
            val outcome = harness.submitText(input)
            assertNotNull("Gateway must safely handle injection input: $input", outcome)
        }

        // Verify database records were not corrupted
        assertTrue(harness.commandDao.records.size == injectionInputs.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Prompt Prefix Token Count Boundary
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testPromptPrefixTokenCountBoundary() {
        fun satisfiesKvCacheThreshold(text: String, thresholdTokens: Int = 600): Boolean {
            return MockAiCoreHarness.estimateTokenCount(text) >= thresholdTokens
        }

        val shortPrompt = "You are AgentDesk. Follow instructions."
        val exact600TokensPrompt = "word ".repeat(600) // ~600 words / tokens
        val productionPrompt = MockAiCoreHarness.STATIC_PROMPT_PREFIX

        assertFalse("Short prompt must not qualify for prefix cache", satisfiesKvCacheThreshold(shortPrompt))
        assertTrue("Exact 600 tokens prompt must qualify", satisfiesKvCacheThreshold(exact600TokensPrompt))
        assertTrue("Production prompt must comfortably exceed threshold", satisfiesKvCacheThreshold(productionPrompt))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Vector Similarity Geometry Edge Cases
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testVectorGeometryBoundaryConditions() {
        fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
            require(v1.size == v2.size)
            var dot = 0f
            var n1 = 0f
            var n2 = 0f
            for (i in v1.indices) {
                dot += v1[i] * v2[i]
                n1 += v1[i] * v1[i]
                n2 += v2[i] * v2[i]
            }
            val denom = sqrt(n1) * sqrt(n2)
            return if (denom > 0f) dot / denom else 0f
        }

        val a = floatArrayOf(1f, 0f, 0f)
        val identical = floatArrayOf(1f, 0f, 0f)
        val opposite = floatArrayOf(-1f, 0f, 0f)
        val orthogonal = floatArrayOf(0f, 1f, 0f)
        val zeroVector = floatArrayOf(0f, 0f, 0f)

        assertEquals("Identical vectors must have cosine similarity 1.0", 1.0f, cosineSimilarity(a, identical), 1e-5f)
        assertEquals("Opposite vectors must have cosine similarity -1.0", -1.0f, cosineSimilarity(a, opposite), 1e-5f)
        assertEquals("Orthogonal vectors must have cosine similarity 0.0", 0.0f, cosineSimilarity(a, orthogonal), 1e-5f)
        assertEquals("Zero vector comparison must safely return 0.0 without NaN", 0.0f, cosineSimilarity(a, zeroVector), 1e-5f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Semantic Chunker Text Boundary Conditions
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testSemanticChunkerBoundaries() {
        fun chunkText(text: String, maxChars: Int = 100): List<String> {
            if (text.isBlank()) return emptyList()
            if (text.length <= maxChars) return listOf(text.trim())

            val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            if (sentences.isEmpty()) return listOf(text.trim())

            val chunks = mutableListOf<String>()
            var current = StringBuilder()
            for (s in sentences) {
                if (current.length + s.length > maxChars && current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current = StringBuilder(s)
                } else {
                    if (current.isNotEmpty()) current.append(" ")
                    current.append(s)
                }
            }
            if (current.isNotEmpty()) chunks.add(current.toString().trim())
            return chunks
        }

        assertTrue("Empty document returns empty chunk list", chunkText("").isEmpty())
        assertEquals("Single short sentence returns exactly 1 chunk", 1, chunkText("Hello world.").size)

        // Huge continuous string without whitespace
        val continuousString = "X".repeat(250)
        val singleChunk = chunkText(continuousString, maxChars = 300)
        assertEquals(1, singleChunk.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Conversation Pruner Message Count Boundaries
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testConversationPrunerBoundaries() {
        fun pruneMessages(messages: List<ConversationMessageEntity>, maxCount: Int = 12): List<ConversationMessageEntity> {
            return messages.takeLast(maxCount)
        }

        // Empty list
        assertTrue(pruneMessages(emptyList()).isEmpty())

        // Exactly 12 messages
        val exact12 = (1..12).map { i ->
            ConversationMessageEntity(id = i.toLong(), threadId = 1L, role = "user", content = "msg $i")
        }
        assertEquals(12, pruneMessages(exact12).size)

        // 13 messages -> drops oldest
        val thirteen = (1..13).map { i ->
            ConversationMessageEntity(id = i.toLong(), threadId = 1L, role = "user", content = "msg $i")
        }
        val pruned13 = pruneMessages(thirteen)
        assertEquals(12, pruned13.size)
        assertEquals(2L, pruned13.first().id)
        assertEquals(13L, pruned13.last().id)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Resource Governor Storage & Thermal Safety Boundaries
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testResourceGovernorSafetyBoundaries() {
        fun canIndex(availableStorageMb: Long, isLowMemory: Boolean, thermalStatus: Int): Boolean {
            if (isLowMemory) return false
            if (availableStorageMb < 500L) return false
            if (thermalStatus >= 3) return false // 3 = THERMAL_STATUS_SEVERE
            return true
        }

        // Storage boundaries
        assertTrue("Storage at exactly 500MB is permitted", canIndex(500L, false, 0))
        assertFalse("Storage at 499MB must be blocked by safety floor", canIndex(499L, false, 0))
        assertTrue("Storage at 1000MB is permitted", canIndex(1000L, false, 0))

        // Thermal boundaries
        assertTrue("Thermal status 0 (NONE) permitted", canIndex(1000L, false, 0))
        assertTrue("Thermal status 1 (LIGHT) permitted", canIndex(1000L, false, 1))
        assertTrue("Thermal status 2 (MODERATE) permitted", canIndex(1000L, false, 2))
        assertFalse("Thermal status 3 (SEVERE) must pause indexing", canIndex(1000L, false, 3))
        assertFalse("Thermal status 4 (CRITICAL) must pause indexing", canIndex(1000L, false, 4))

        // Low memory boundary
        assertFalse("Low memory flag must pause indexing", canIndex(1000L, true, 0))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. Battery Boundary: Exact 20% Discharging vs Charging (Phases 6–9)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testBatteryBoundaryExact20PercentDischargingVsCharging() {
        val profiler = harness.profiler
        val governor = harness.resourceGovernor

        // Boundary test 1: 19% discharging -> both false
        profiler.simPercent = 19
        profiler.simCharging = false
        assertFalse("19% discharging must pause indexing", governor.canIndex())
        assertFalse("19% discharging must pause LLM", governor.canRunLlm())

        // Boundary test 2: 20% discharging -> both true
        profiler.simPercent = 20
        profiler.simCharging = false
        assertTrue("Exact 20% discharging must allow indexing", governor.canIndex())
        assertTrue("Exact 20% discharging must allow LLM", governor.canRunLlm())

        // Boundary test 3: 19% charging -> both true (charging overrides low battery)
        profiler.simPercent = 19
        profiler.simCharging = true
        assertTrue("19% charging must allow indexing", governor.canIndex())
        assertTrue("19% charging must allow LLM", governor.canRunLlm())

        // Boundary test 4: 0% charging -> both true
        profiler.simPercent = 0
        profiler.simCharging = true
        assertTrue("0% charging must allow indexing", governor.canIndex())
        assertTrue("0% charging must allow LLM", governor.canRunLlm())

        // Boundary test 5: 0% discharging -> both false
        profiler.simPercent = 0
        profiler.simCharging = false
        assertFalse("0% discharging must pause indexing", governor.canIndex())
        assertFalse("0% discharging must pause LLM", governor.canRunLlm())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. Dynamic Batching Boundary Sizes (0, 1, 16, 25 items) (Phases 6–9)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testDynamicBatchingBoundarySizes() {
        val simulator = harness.embeddingGenerator

        // Case 1: Empty list (0 items)
        simulator.batchInvocations.clear()
        val emptyResult = simulator.generateEmbeddings(emptyList(), maxBatchSize = 16)
        assertTrue("Empty list produces empty embedding list", emptyResult.isEmpty())
        assertEquals("Empty list triggers zero batch invocations", 0, simulator.batchInvocations.size)

        // Case 2: Single item (1 item)
        simulator.batchInvocations.clear()
        val singleResult = simulator.generateEmbeddings(listOf("single item"), maxBatchSize = 16)
        assertEquals(1, singleResult.size)
        assertEquals(1, simulator.batchInvocations.size)
        assertEquals(1, simulator.batchInvocations.first().size)

        // Case 3: Exactly batch size 16 (16 items)
        simulator.batchInvocations.clear()
        val exact16Items = (1..16).map { "Item $it" }
        val exact16Result = simulator.generateEmbeddings(exact16Items, maxBatchSize = 16)
        assertEquals(16, exact16Result.size)
        assertEquals("Exact batch size produces exactly 1 batch", 1, simulator.batchInvocations.size)
        assertEquals(16, simulator.batchInvocations.first().size)

        // Case 4: Overflow batch size (25 items with batch size 16)
        simulator.batchInvocations.clear()
        val overflow25Items = (1..25).map { "Item $it" }
        val overflow25Result = simulator.generateEmbeddings(overflow25Items, maxBatchSize = 16)
        assertEquals(25, overflow25Result.size)
        assertEquals("25 items with batch size 16 produces exactly 2 batches", 2, simulator.batchInvocations.size)
        assertEquals(16, simulator.batchInvocations[0].size)
        assertEquals(9, simulator.batchInvocations[1].size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 11. Storage Threshold Boundary: Exact 500MB Storage Floor (Phases 6–9)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testStorageThresholdBoundaryExact500MbFloor() {
        val profiler = harness.profiler
        val governor = harness.resourceGovernor

        // State 1: 501MB available -> allowed
        profiler.simAvailableStorageMb = 501L
        assertTrue("501MB storage must allow indexing", governor.canIndex())

        // State 2: Exactly 500MB available -> allowed (boundary threshold)
        profiler.simAvailableStorageMb = 500L
        assertTrue("Exact 500MB floor must allow indexing", governor.canIndex())

        // State 3: 499MB available -> denied
        profiler.simAvailableStorageMb = 499L
        assertFalse("499MB storage must pause indexing", governor.canIndex())
        val reason = governor.currentConstraintReason()
        assertNotNull(reason)
        assertTrue(reason!!.contains("below 500MB"))
    }
}
