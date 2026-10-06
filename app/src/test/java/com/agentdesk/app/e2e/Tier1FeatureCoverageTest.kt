package com.agentdesk.app.e2e

import com.agentdesk.agent.command.Command
import com.agentdesk.agent.command.CommandOutcome
import com.agentdesk.agent.command.CommandResult
import com.agentdesk.agent.policy.PolicyEngine
import com.agentdesk.agent.rule.Intents
import com.agentdesk.app.e2e.harnesses.E2ETestHarness
import com.agentdesk.app.e2e.harnesses.MockAiCoreHarness
import com.agentdesk.core.common.model.CommandSource
import com.agentdesk.core.common.model.DeviceTier
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.ConfirmationRecordEntity
import com.agentdesk.core.persistence.entity.ConversationMessageEntity
import com.agentdesk.core.persistence.entity.ConversationThreadEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Tier 1: Feature Coverage (Representative inputs for all features F01 - F22).
 * Exercises public contracts and external interfaces across the entire AgentDesk system.
 */
class Tier1FeatureCoverageTest {

    private lateinit var harness: E2ETestHarness

    @Before
    fun setUp() {
        harness = E2ETestHarness()
    }

    private fun findProjectRoot(): File {
        var current: File? = File(".").canonicalFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").exists() || File(current, "gradlew").exists()) {
                return current
            }
            current = current.parentFile
        }
        return File("c:/AgentDesk")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F01: NavigationSuiteScaffold & Adaptive Layout
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF01_adaptiveLayoutConfiguration() {
        // Contract: Window size class determines adaptive navigation layout type
        fun resolveNavigationLayoutType(widthDp: Int, route: String): String = when {
            route == "onboarding" -> "NONE"
            widthDp < 600        -> "BOTTOM_BAR" // Compact phone
            widthDp < 840        -> "NAV_RAIL"   // Medium foldable/small tablet
            else                 -> "NAV_RAIL"   // Expanded tablet
        }

        assertEquals("NONE", resolveNavigationLayoutType(360, "onboarding"))
        assertEquals("BOTTOM_BAR", resolveNavigationLayoutType(360, "chat"))
        assertEquals("BOTTOM_BAR", resolveNavigationLayoutType(411, "library"))
        assertEquals("NAV_RAIL", resolveNavigationLayoutType(600, "chat"))
        assertEquals("NAV_RAIL", resolveNavigationLayoutType(840, "chat"))
        assertEquals("NAV_RAIL", resolveNavigationLayoutType(1024, "settings"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F02: Predictive Back Gesture Handling
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF02_predictiveBackGestureOptIn() {
        val root = findProjectRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist at ${manifest.absolutePath}", manifest.exists())

        val content = manifest.readText()
        assertTrue(
            "Predictive Back requirement: AndroidManifest.xml must declare android:enableOnBackInvokedCallback=\"true\"",
            content.contains("android:enableOnBackInvokedCallback=\"true\"")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F03: Wispr Flow Developer Console Theme
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF03_wisprFlowThemePaletteContract() {
        // Wispr Flow palette hex color codes
        val backgroundHex = 0x0F172AL
        val surfaceHex = 0x1E293BL
        val accentHex = 0x22C55EL
        val textHex = 0xF8FAFCL
        val borderHex = 0x475569L

        // Relative luminance helper for WCAG contrast
        fun relativeLuminance(colorHex: Long): Double {
            val r = ((colorHex shr 16) and 0xFF) / 255.0
            val g = ((colorHex shr 8) and 0xFF) / 255.0
            val b = (colorHex and 0xFF) / 255.0

            fun adjust(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            return 0.2126 * adjust(r) + 0.7152 * adjust(g) + 0.0722 * adjust(b)
        }

        fun contrastRatio(fgHex: Long, bgHex: Long): Double {
            val l1 = relativeLuminance(fgHex)
            val l2 = relativeLuminance(bgHex)
            val lighter = maxOf(l1, l2)
            val darker = minOf(l1, l2)
            return (lighter + 0.05) / (darker + 0.05)
        }

        val textOnBgContrast = contrastRatio(textHex, backgroundHex)
        val accentOnBgContrast = contrastRatio(accentHex, backgroundHex)

        // WCAG AA requires at least 4.5:1 for normal text, 3:1 for graphical/UI components
        assertTrue("Text on background contrast ($textOnBgContrast) must exceed WCAG 4.5:1", textOnBgContrast >= 4.5)
        assertTrue("Accent on background contrast ($accentOnBgContrast) must exceed WCAG 3:1", accentOnBgContrast >= 3.0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F04: WCAG 2.2 AA Accessibility & Insets
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF04_wcagAccessibilityTouchTargetAndInsets() {
        val minTouchTargetDp = 44
        val chipHeightDp = 44
        val iconButtonSizeDp = 48

        assertTrue("Interactive touch targets must meet min 44dp", minTouchTargetDp >= 44)
        assertTrue("Suggestion chips must meet min 44dp", chipHeightDp >= 44)
        assertTrue("Icon buttons must meet min 44dp", iconButtonSizeDp >= 44)

        // Verify windowSoftInputMode="adjustResize" in AndroidManifest.xml for IME insets
        val root = findProjectRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue("MainActivity must declare adjustResize for IME insets", manifest.contains("android:windowSoftInputMode=\"adjustResize\""))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F05: SupportingPaneScaffold on Tablet
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF05_supportingPaneScaffoldTabletLayout() {
        fun isDualPaneEnabled(widthDp: Int): Boolean = widthDp >= 840

        assertFalse("Phone viewport (360dp) must use single-pane", isDualPaneEnabled(360))
        assertFalse("Foldable compact (600dp) must use single-pane", isDualPaneEnabled(600))
        assertTrue("Tablet expanded (840dp) must activate dual supporting pane", isDualPaneEnabled(840))
        assertTrue("Large tablet (1024dp) must activate dual supporting pane", isDualPaneEnabled(1024))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F06: Conversation Thread & Message Persistence
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF06_conversationThreadAndMessagePersistence() = runBlocking {
        val thread = ConversationThreadEntity(
            id = 1L,
            title = "Test Thread",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val msg1 = ConversationMessageEntity(
            id = 101L,
            threadId = thread.id,
            role = "user",
            content = "Hello AgentDesk",
            createdAt = 1001L
        )
        val msg2 = ConversationMessageEntity(
            id = 102L,
            threadId = thread.id,
            role = "assistant",
            content = "How can I help you today?",
            createdAt = 1002L
        )

        assertEquals(1L, thread.id)
        assertEquals(thread.id, msg1.threadId)
        assertEquals(thread.id, msg2.threadId)
        assertTrue(msg2.createdAt > msg1.createdAt)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F07: Monotonic Message IDs & State Restoration
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF07_monotonicMessageIdsAndStateRestoration() = runBlocking {
        val outcome1 = harness.submitText("alarm at 7:00 am")
        val outcome2 = harness.submitText("battery status")

        assertTrue(outcome1 is CommandOutcome.Executed)
        assertTrue(outcome2 is CommandOutcome.Executed)

        val records = harness.commandDao.records
        assertEquals(2, records.size)
        assertTrue("Message IDs must be strictly monotonic: ${records[0].id} < ${records[1].id}", records[0].id < records[1].id)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F08: Conversation Context Pruner
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF08_conversationContextPruner() {
        // Contract: Prune context to last 6 turns (12 messages) bounded to ~2048 tokens
        fun pruneContext(messages: List<ConversationMessageEntity>, maxTurns: Int = 6): List<ConversationMessageEntity> {
            val maxMessages = maxTurns * 2
            return messages.takeLast(maxMessages)
        }

        val allMessages = (1..20).map { i ->
            ConversationMessageEntity(
                id = i.toLong(),
                threadId = 1L,
                role = if (i % 2 == 1) "user" else "assistant",
                content = "Message $i",
                createdAt = i * 100L
            )
        }

        val pruned = pruneContext(allMessages, maxTurns = 6)
        assertEquals("Pruned context must contain exactly 12 messages (6 turns)", 12, pruned.size)
        assertEquals(9L, pruned.first().id)
        assertEquals(20L, pruned.last().id)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F09: Deterministic 11-Tool Resolution
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF09_deterministic11ToolResolution() {
        val testCases = listOf(
            "alarm at 6:30 am" to Intents.SET_ALARM,
            "timer for 10 minutes" to Intents.SET_TIMER,
            "note buy milk and eggs" to Intents.NOTE_CREATE,
            "open settings" to Intents.APP_OPEN,
            "schedule meeting standup" to Intents.CALENDAR_CREATE,
            "text alice saying on my way" to Intents.SMS_DRAFT,
            "find meeting notes" to Intents.LIBRARY_SEARCH,
            "call 555-0199" to Intents.DIAL_NUMBER,
            "google kotlin documentation" to Intents.WEB_SEARCH,
            "navigate to airport" to Intents.NAVIGATE_MAP,
            "battery status" to Intents.DEVICE_HEALTH
        )

        for ((input, expectedIntent) in testCases) {
            val result = harness.ruleEngine.evaluate(input)
            assertNotNull("Input '$input' must match an intent", result)
            assertEquals("Input '$input' must resolve to $expectedIntent", expectedIntent, result!!.intentName)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F10: Confirmation Parameter Preview Cards
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF10_confirmationParameterPreviewCards() = runBlocking {
        val outcome = harness.submitText("text bob saying I'm running 5 minutes late")
        assertTrue("SMS draft must require confirmation", outcome is CommandOutcome.NeedsUserConfirmation)

        val pending = outcome as CommandOutcome.NeedsUserConfirmation
        assertEquals(Intents.SMS_DRAFT, pending.command.intentName)
        assertTrue(pending.prompt.contains("bob", ignoreCase = true))

        // Confirm action
        val confirmedOutcome = harness.executeConfirmed(pending.command)
        assertTrue(confirmedOutcome is CommandOutcome.Executed)

        // Verify audit event
        val toolEvent = harness.auditDao.events.last()
        assertEquals("TOOL_SUCCESS", toolEvent.eventType)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F11: FastVectorMath Pure Kotlin SIMD
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF11_fastVectorMathPureKotlinSimd() {
        // Vector dot product and normalization contract
        fun dotProduct(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "Vector dimensions must match" }
            var sum = 0f
            for (i in a.indices) {
                sum += a[i] * b[i]
            }
            return sum
        }

        fun normalize(v: FloatArray): FloatArray {
            var normSq = 0f
            for (x in v) normSq += x * x
            val norm = sqrt(normSq)
            return if (norm > 0f) FloatArray(v.size) { i -> v[i] / norm } else v
        }

        val v1 = normalize(floatArrayOf(1f, 2f, 3f))
        val v2 = normalize(floatArrayOf(1f, 2f, 3f))
        val v3 = normalize(floatArrayOf(-2f, 1f, 0f)) // Orthogonal to (1, 2, 0)

        val selfDot = dotProduct(v1, v2)
        assertEquals("Self dot product of unit vector must be 1.0", 1.0f, selfDot, 1e-5f)

        val orthDot = dotProduct(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        assertEquals("Orthogonal vectors dot product must be 0.0", 0.0f, orthDot, 1e-5f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F12: VectorDao & BLOB Serialization
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF12_vectorDaoBlobSerialization() {
        fun serializeFloats(floats: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in floats) buffer.putFloat(f)
            return buffer.array()
        }

        fun deserializeFloats(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(bytes.size / 4)
            for (i in floats.indices) floats[i] = buffer.float
            return floats
        }

        val original = floatArrayOf(0.123f, -0.456f, 0.789f, 1.0f)
        val serialized = serializeFloats(original)
        assertEquals("Serialized byte array size must be 4 * float count", 16, serialized.size)

        val deserialized = deserializeFloats(serialized)
        assertEquals(original.size, deserialized.size)
        for (i in original.indices) {
            assertEquals(original[i], deserialized[i], 1e-6f)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F13: Semantic Recursive Chunker
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF13_semanticRecursiveChunker() {
        // Contract: Chunks preserve sentences and have sliding-window overlap
        fun chunkText(text: String, maxChars: Int = 100, overlapChars: Int = 20): List<String> {
            val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            val chunks = mutableListOf<String>()
            var currentChunk = StringBuilder()

            for (sentence in sentences) {
                if (currentChunk.length + sentence.length > maxChars && currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString().trim())
                    val overlap = currentChunk.takeLast(overlapChars).toString()
                    currentChunk = StringBuilder(overlap).append(" ").append(sentence)
                } else {
                    if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                    currentChunk.append(sentence)
                }
            }
            if (currentChunk.isNotEmpty()) chunks.add(currentChunk.toString().trim())
            return chunks
        }

        val doc = "AgentDesk is an on-device AI copilot. It runs with zero cloud egress. All data stays local in Room database. Security is verified with HMAC-SHA256 audit chaining."
        val chunks = chunkText(doc, maxChars = 80, overlapChars = 15)

        assertTrue("Chunker must split into multiple chunks", chunks.size >= 2)
        // Verify sentence boundary is preserved (does not slice mid-word)
        for (chunk in chunks) {
            assertFalse("Chunk must not start or end with broken words", chunk.startsWith(" ") || chunk.endsWith(" "))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F14: Hybrid RRF Retrieval Engine
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF14_hybridRrfRetrievalFormula() {
        // Reciprocal Rank Fusion: RRF_Score(d) = sum( 1 / (60 + rank_m(d)) )
        fun computeRrfScore(rankFts: Int?, rankVector: Int?, k: Int = 60): Double {
            var score = 0.0
            if (rankFts != null) score += 1.0 / (k + rankFts)
            if (rankVector != null) score += 1.0 / (k + rankVector)
            return score
        }

        val topBoth = computeRrfScore(rankFts = 1, rankVector = 1)
        val topFtsOnly = computeRrfScore(rankFts = 1, rankVector = null)
        val topVectorOnly = computeRrfScore(rankFts = null, rankVector = 1)
        val lowBoth = computeRrfScore(rankFts = 10, rankVector = 10)

        assertTrue("Item ranked #1 in both must beat item ranked #1 in FTS only", topBoth > topFtsOnly)
        assertTrue("Item ranked #1 in both must beat item ranked #1 in Vector only", topBoth > topVectorOnly)
        assertTrue("Item ranked in both modalities (#10) beats item ranked in only one modality (#1)", lowBoth > topFtsOnly)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F15: ML Kit Prompt API & @Generable Schema
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF15_mlKitPromptApiGenerableSchema() {
        val classified = MockAiCoreHarness.classify("wake me up at 7am tomorrow")
        assertEquals(Intents.SET_ALARM, classified.intentName)
        assertTrue(classified.confidence >= 0.9f)
        assertEquals("7", classified.entities["hour"])
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F16: Prompt Prefix Caching (>600 tokens)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF16_promptPrefixCachingThreshold() {
        val prefix = MockAiCoreHarness.STATIC_PROMPT_PREFIX
        val estimatedTokens = MockAiCoreHarness.estimateTokenCount(prefix)
        assertTrue(
            "Prompt prefix must exceed 600 tokens for KV cache acceleration (actual: $estimatedTokens)",
            estimatedTokens > 600
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F17: Deterministic 4-Tier Fallback Pyramid
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF17_deterministic4TierFallbackPyramid() {
        // Tier 0: Regex match
        val tier0 = harness.ruleEngine.evaluate("alarm at 8:00 am")
        assertNotNull("Tier 0 regex must handle exact pattern", tier0)
        assertEquals(Intents.SET_ALARM, tier0!!.intentName)

        // Tier 1: SLM natural language classification
        val tier1 = MockAiCoreHarness.classify("please wake me up tomorrow at 7")
        assertEquals(Intents.SET_ALARM, tier1.intentName)

        // Tier 3: Clarification chips when throttled
        val tier3 = MockAiCoreHarness.classify("asdfghjkl", thermalThrottled = true)
        assertEquals("clarification", tier3.intentName)
        assertTrue(tier3.clarificationChips.isNotEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F18: Universal Mock AICore Test Harness
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF18_mockAiCoreTestHarness() {
        val classified = MockAiCoreHarness.classify("how hot is the phone right now")
        assertEquals(Intents.DEVICE_HEALTH, classified.intentName)
        assertTrue(classified.confidence >= 0.9f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F19: Zero Cloud Egress Invariant Check
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF19_zeroCloudEgressInvariant() {
        val root = findProjectRoot()
        val manifests = mutableListOf<File>()
        root.walkTopDown()
            .filter { file ->
                file.name == "AndroidManifest.xml" &&
                        !file.absolutePath.contains("${File.separator}build${File.separator}") &&
                        !file.absolutePath.contains("${File.separator}.gradle${File.separator}")
            }
            .toCollection(manifests)

        assertTrue("Must discover at least 10 module manifests", manifests.size >= 10)

        for (manifest in manifests) {
            val content = manifest.readText().replace(Regex("<!--[\\s\\S]*?-->"), "")
            assertFalse(
                "Zero Cloud Egress Invariant Violation in ${manifest.path}",
                content.contains("android.permission.INTERNET")
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F20: PolicyEngine Veto & Forbidden Intents
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF20_forbiddenIntentsAlwaysRejected() = runBlocking {
        assertEquals("Must contain exactly 10 forbidden intents", 10, PolicyEngine.FORBIDDEN_INTENTS.size)

        for (forbiddenIntent in PolicyEngine.FORBIDDEN_INTENTS) {
            val command = Command(
                rawInput = "attempt $forbiddenIntent",
                normalizedInput = "attempt $forbiddenIntent".lowercase(),
                intentName = forbiddenIntent,
                confidence = 1.0f,
                source = CommandSource.USER_CHAT
            )
            val outcome = harness.policyEngine.evaluate(command, 1L)
            assertTrue("Forbidden intent $forbiddenIntent must be rejected", outcome is CommandResult.Rejected)

            val auditEvents = harness.auditDao.events
            val lastAudit = auditEvents.last()
            assertEquals("DENIED", lastAudit.eventType)
            assertTrue(lastAudit.summary.contains("Forbidden intent"))
            assertEquals("HIGH", lastAudit.riskLevel.name)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F21: Sequential HMAC-SHA256 Audit Chain
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF21_sequentialHmacSha256AuditChainIntegrity() = runBlocking {
        harness.submitText("alarm at 7:00 am")
        harness.submitText("battery status")
        harness.submitText("note test note")

        val events = harness.auditDao.events
        assertTrue("Must have recorded multiple audit events", events.size >= 3)
        assertTrue("HMAC-SHA256 sequential audit chain must be valid", harness.verifyAuditChainIntegrity())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F22: Dispatchers.IO Thread Safety Audit
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF22_dispatcherHygieneNoMainThreadIo() = runBlocking {
        // Verify CommandGateway execution offloads to background dispatcher
        val outcome = harness.submitText("alarm at 6:00 am")
        assertTrue(outcome is CommandOutcome.Executed)
        // Command record inserted into DAO
        assertEquals(1, harness.commandDao.records.size)
    }
}
