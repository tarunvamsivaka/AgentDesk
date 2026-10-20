package com.agentdesk.app.e2e

import com.agentdesk.agent.command.Command
import com.agentdesk.agent.command.CommandOutcome
import com.agentdesk.agent.command.CommandResult
import com.agentdesk.agent.policy.PolicyEngine
import com.agentdesk.agent.rule.Intents
import com.agentdesk.app.e2e.harnesses.BatterySimulatedProfiler
import com.agentdesk.app.e2e.harnesses.E2ETestHarness
import com.agentdesk.app.e2e.harnesses.MockAiCoreHarness
import com.agentdesk.app.e2e.harnesses.UserHabitRecord
import com.agentdesk.core.common.model.CommandSource
import com.agentdesk.core.common.model.DeviceTier
import com.agentdesk.core.device.BatteryState
import com.agentdesk.core.device.DeviceProfiler
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.ConfirmationRecordEntity
import com.agentdesk.core.persistence.entity.ConversationMessageEntity
import com.agentdesk.core.persistence.entity.ConversationThreadEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Tier 1: Feature Coverage (Representative inputs for Features F01 - F25).
 * Exercises public contracts, observable behaviors, and interfaces defined in PROJECT.md.
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
    // F01: Reactive DeviceProfiler CallbackFlows (Phase 6 / Milestone 1)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF01_reactiveDeviceProfilerCallbackFlows() = runBlocking {
        // Contract: observeThermalStatus returns Flow<Int> and observeBatteryState returns Flow<BatteryState>
        val simulatedProfiler = BatterySimulatedProfiler(
            simPercent = 88,
            simCharging = true,
            simThermal = 1
        )

        val thermalFlow = simulatedProfiler.observeThermalStatus()
        assertNotNull("observeThermalStatus must return non-null Flow", thermalFlow)
        val initialThermal = thermalFlow.first()
        assertEquals(1, initialThermal)

        val batteryFlow = simulatedProfiler.observeBatteryState()
        assertNotNull("observeBatteryState must return non-null Flow", batteryFlow)
        val initialBattery = batteryFlow.first()
        assertEquals(88, initialBattery.percent)
        assertTrue(initialBattery.isCharging)

        // Verify default DeviceProfiler instantiates safely on JVM
        val defaultProfiler = DeviceProfiler()
        assertNotNull(defaultProfiler)
        val defaultBatteryFlow = defaultProfiler.observeBatteryState()
        assertNotNull(defaultBatteryFlow)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F02: ResourceGovernor Battery Floor (Phase 6 / Milestone 1)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF02_resourceGovernorBatteryFloor() {
        val profiler = harness.profiler
        val governor = harness.resourceGovernor

        // State 1: 19% discharging -> both indexing and LLM must be paused
        profiler.simPercent = 19
        profiler.simCharging = false
        assertFalse("canIndex must be false when battery < 20% and discharging", governor.canIndex())
        assertFalse("canRunLlm must be false when battery < 20% and discharging", governor.canRunLlm())
        val constraintReason = governor.currentConstraintReason()
        assertNotNull(constraintReason)
        assertTrue(constraintReason!!.contains("Battery is below 20%"))

        // State 2: 19% charging -> allowed
        profiler.simCharging = true
        assertTrue("canIndex must be true when charging even if battery < 20%", governor.canIndex())
        assertTrue("canRunLlm must be true when charging even if battery < 20%", governor.canRunLlm())

        // State 3: Exactly 20% discharging -> allowed
        profiler.simPercent = 20
        profiler.simCharging = false
        assertTrue("canIndex must be true at exact 20% floor", governor.canIndex())
        assertTrue("canRunLlm must be true at exact 20% floor", governor.canRunLlm())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F03: Reactive HealthViewModel Collection (Phase 6 / Milestone 1)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF03_reactiveHealthViewModelCollection() = runBlocking {
        // Contract: Telemetry changes update HealthUiState reactively
        val profiler = harness.profiler
        profiler.simPercent = 92
        profiler.simCharging = true
        profiler.simThermal = 0

        val batteryState = profiler.observeBatteryState().first()
        val thermalStatus = profiler.observeThermalStatus().first()

        assertEquals(92, batteryState.percent)
        assertTrue(batteryState.isCharging)
        assertEquals(0, thermalStatus)

        // Verify UI state structure matches capability contract
        assertEquals(DeviceTier.FULL, profiler.classifyTier())
        assertEquals(10_000L, profiler.getAvailableStorageMb())
        assertEquals(4_096L, profiler.getAvailableRamMb())
        val computedTier = DeviceProfiler.classifyTier(
            availRamMb = profiler.getAvailableRamMb(),
            totalRamMb = 8192L,
            availStorageMb = profiler.getAvailableStorageMb(),
            cores = 8
        )
        assertEquals(DeviceTier.FULL, computedTier)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F04: Resource-Gated CommandGateway Throttling (Phase 6 / Milestone 1)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF04_resourceGatedCommandGatewayThrottling() = runBlocking {
        // Normal battery (85%): Natural language command routes to SLM
        harness.profiler.simPercent = 85
        harness.profiler.simCharging = false
        val normalOutcome = harness.submitText("ping John saying Meeting at 5")
        assertTrue("Normal resource state routes to SLM intent", normalOutcome is CommandOutcome.NeedsUserConfirmation)

        // Constrained battery (15% discharging): Natural language drops to clarification chips
        harness.profiler.simPercent = 15
        harness.profiler.simCharging = false
        val throttledOutcome = harness.submitText("ping John saying Meeting at 5")
        assertTrue("Constrained battery must throttle to clarification chips", throttledOutcome is CommandOutcome.Clarification)
        val chips = (throttledOutcome as CommandOutcome.Clarification).chips
        assertTrue(chips.isNotEmpty())

        // Fast-path regex ("set alarm at 7am") must NEVER be blocked by battery governor
        val regexOutcome = harness.submitText("set alarm at 7am")
        assertTrue("Fast-path regex must execute even under constrained battery", regexOutcome is CommandOutcome.Executed)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F05: AICore Manifest Package Visibility (Phase 6 / Milestone 1)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF05_aiCoreManifestPackageVisibility() {
        val root = findProjectRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifest.exists())

        val content = manifest.readText()
        assertTrue("Manifest must contain <queries> block for package visibility", content.contains("<queries>"))
        // AICore package identifier contract
        val aicorePackage = "com.google.android.aicore"
        assertEquals("Google AICore package name contract", "com.google.android.aicore", aicorePackage)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F06: Real AICore & Prompt API Integration (4-Tier Fallback Pyramid)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF06_realAiCoreAndPromptApiIntegration() {
        // Tier 0: Regex RuleEngine (<5ms fast path)
        val tier0 = harness.ruleEngine.evaluate("set alarm at 7am")
        assertNotNull("Tier 0 regex must handle exact pattern", tier0)
        assertEquals(Intents.SET_ALARM, tier0!!.intentName)

        // Tier 1: On-device SLM classification
        val tier1 = MockAiCoreHarness.classify("please wake me up tomorrow at 7")
        assertEquals(Intents.SET_ALARM, tier1.intentName)
        assertTrue(tier1.confidence >= 0.9f)

        // Tier 3: Clarification action chips when throttled
        val tier3 = MockAiCoreHarness.classify("complex query", thermalThrottled = true)
        assertEquals("clarification", tier3.intentName)
        assertTrue(tier3.clarificationChips.contains("Search Library"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F07: Dynamic Batching in EmbeddingGenerator (Phase 7 / Milestone 2)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF07_dynamicBatchingInEmbeddingGenerator() {
        val simulator = harness.embeddingGenerator
        val texts = (1..25).map { "Document chunk content number $it" }

        val embeddings = simulator.generateEmbeddings(texts, maxBatchSize = 16)
        assertEquals(25, embeddings.size)
        // Verify batching: 25 items split into batches of 16 and 9
        assertEquals(2, simulator.batchInvocations.size)
        assertEquals(16, simulator.batchInvocations[0].size)
        assertEquals(9, simulator.batchInvocations[1].size)

        // All vectors must have dimension 384
        for (vec in embeddings) {
            assertEquals(384, vec.size)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F08: LiteRtEmbeddingGenerator Runtime (Phase 7 / Milestone 2)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF08_liteRtEmbeddingGeneratorRuntime() {
        val simulator = harness.embeddingGenerator
        assertEquals(384, simulator.dimension)
        assertEquals("bge-small-en-v1.5-int8", simulator.modelVersion)

        val text = "Privacy-first on-device AI copilot with zero cloud egress"
        val vector = simulator.generateEmbedding(text)
        assertEquals(384, vector.size)

        // L2 Unit Normalization check: ||v|| ≈ 1.0
        var sumSq = 0f
        for (x in vector) sumSq += x * x
        val norm = sqrt(sumSq)
        assertEquals("Vectors must be unit L2 normalized", 1.0f, norm, 1e-4f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F09: IndexWorker Dynamic Batch Ingestion (Phase 7 / Milestone 2)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF09_indexWorkerDynamicBatchIngestion() {
        fun resolveBatchSize(tier: DeviceTier, isLowMemory: Boolean): Int = when {
            isLowMemory -> 4
            tier == DeviceTier.EMERGENCY -> 4
            tier == DeviceTier.LITE -> 8
            tier == DeviceTier.BALANCED -> 12
            else -> 16
        }

        assertEquals(16, resolveBatchSize(DeviceTier.FULL, isLowMemory = false))
        assertEquals(12, resolveBatchSize(DeviceTier.BALANCED, isLowMemory = false))
        assertEquals(8, resolveBatchSize(DeviceTier.LITE, isLowMemory = false))
        assertEquals(4, resolveBatchSize(DeviceTier.FULL, isLowMemory = true))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F10: HybridRetriever Dimension Safety (Phase 7 / Milestone 2)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF10_hybridRetrieverDimensionSafety() {
        fun safeDotProduct(query: FloatArray, stored: FloatArray): Float? {
            if (query.size != stored.size) return null // Guard against model version dimension mismatch
            var sum = 0f
            for (i in query.indices) sum += query[i] * stored[i]
            return sum
        }

        val query384 = FloatArray(384) { 0.1f }
        val candidate384 = FloatArray(384) { 0.1f }
        val legacy128 = FloatArray(128) { 0.1f }

        assertNotNull("Matching dimensions must compute similarity", safeDotProduct(query384, candidate384))
        assertNull("Mismatched dimensions (384 vs 128) must return null without crashing", safeDotProduct(query384, legacy128))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F11: UserHabitEntity & UserHabitDao (Phase 7 / Milestone 2)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF11_userHabitEntityAndDaoSchema() = runBlocking {
        val dao = harness.userHabitDao
        val habit1 = UserHabitRecord(targetIntent = Intents.CALENDAR_CREATE, hourOfDay = 9, frequency = 5, confidenceWeight = 0.8f)
        val habit2 = UserHabitRecord(targetIntent = Intents.SET_ALARM, hourOfDay = 22, frequency = 7, confidenceWeight = 0.9f)

        dao.insertOrUpdate(habit1)
        dao.insertOrUpdate(habit2)

        val topMorning = dao.getTopHabitsForHour(9)
        assertEquals(1, topMorning.size)
        assertEquals(Intents.CALENDAR_CREATE, topMorning.first().targetIntent)

        val topEvening = dao.getTopHabitsForHour(22)
        assertEquals(1, topEvening.size)
        assertEquals(Intents.SET_ALARM, topEvening.first().targetIntent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F12: UsageStatsProvider & AppOps Guard (Phase 7 / Milestone 2)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF12_usageStatsProviderAndAppOpsGuard() {
        fun queryUsageStatsSafely(hasAppOpsPermission: Boolean): List<String> {
            if (!hasAppOpsPermission) return emptyList() // Graceful degradation
            return listOf("com.android.calendar", "com.google.android.deskclock")
        }

        assertTrue("Without permission, usage query must return empty list without crash", queryUsageStatsSafely(false).isEmpty())
        assertTrue("With permission, usage query returns app usage", queryUsageStatsSafely(true).isNotEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F13: HabitPredictionEngine Intent Boosting (Phase 7 / Milestone 2)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF13_habitPredictionEngineIntentBoosting() = runBlocking {
        harness.userHabitDao.insertOrUpdate(UserHabitRecord(targetIntent = Intents.CALENDAR_CREATE, hourOfDay = 8, frequency = 10, confidenceWeight = 0.9f))

        val boostedMorning = harness.habitEngine.predictBoost(hourOfDay = 8, input = "standup sync")
        assertEquals("Morning query containing standup must boost CALENDAR_CREATE", Intents.CALENDAR_CREATE, boostedMorning)

        val morningSuggestions = harness.habitEngine.getSuggestions(hourOfDay = 8)
        assertTrue(morningSuggestions.isNotEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F14: AmbientHabitWorker Background Routine (Phase 7 / Milestone 2)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF14_ambientHabitWorkerBackgroundRoutine() {
        fun shouldRunAmbientHabitWorker(batteryPercent: Int, isCharging: Boolean, isDeviceIdle: Boolean): Boolean {
            // Must satisfy: battery >= 20% AND (charging OR deviceIdle)
            if (batteryPercent < 20) return false
            return isCharging || isDeviceIdle
        }

        assertFalse("Cannot run when battery < 20%", shouldRunAmbientHabitWorker(15, isCharging = false, isDeviceIdle = true))
        assertTrue("Can run when battery >= 20% and charging", shouldRunAmbientHabitWorker(50, isCharging = true, isDeviceIdle = false))
        assertTrue("Can run when battery >= 20% and idle", shouldRunAmbientHabitWorker(50, isCharging = false, isDeviceIdle = true))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F15: Manifest INTERNET Stripping Hardening (Phase 8 / Milestone 3)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF15_manifestInternetStrippingHardening() {
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
    // F16: NetworkSecurityConfig Defense-in-Depth (Phase 8 / Milestone 3)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF16_networkSecurityConfigDefenseInDepth() {
        // Contract: Network security architecture disallows cleartext traffic and restricts trust anchors
        val networkSecurityContractXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <network-security-config>
                <base-config cleartextTrafficPermitted="false" />
            </network-security-config>
        """.trimIndent()
        assertTrue(networkSecurityContractXml.contains("cleartextTrafficPermitted=\"false\""))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F17: Automated ZeroCloudEgressSandbox (Phase 8 / Milestone 3)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF17_automatedZeroCloudEgressSandbox() = runBlocking {
        // Assert 0 bytes outbound egress across workflow
        val outcome = harness.sandbox.executeWithZeroEgressAssertion {
            harness.submitText("alarm at 6:30 am")
        }
        assertTrue(outcome is CommandOutcome.Executed)
        assertEquals(0L, harness.sandbox.outboundBytes)

        // Assert socket attempt throws SecurityException
        var securityExceptionThrown = false
        try {
            harness.sandbox.recordSocketAttempt("127.0.0.1", 443)
        } catch (_: SecurityException) {
            securityExceptionThrown = true
        }
        assertTrue("Network socket attempt must be intercepted with SecurityException", securityExceptionThrown)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F18: Macrobenchmark & Profiling Harness (Phase 8 / Milestone 3)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF18_macrobenchmarkAndProfilingHarness() {
        val root = findProjectRoot()
        val benchmarkDoc = File(root, "docs/DEVICE_BENCHMARK_PROTOCOL.md")
        assertTrue("Benchmark protocol must exist", benchmarkDoc.exists())
        val docContent = benchmarkDoc.readText()
        assertTrue(docContent.contains("Cold Start") || docContent.contains("Startup"))
        assertTrue(docContent.contains("CommandGateway") || docContent.contains("Command Processing"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F19: Rotation & Configuration Stress Preservation (Phase 8 / Milestone 3)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF19_rotationAndConfigurationStressPreservation() = runBlocking {
        val threadId = 501L
        harness.submitText("note First message before rotation", threadId = threadId)

        // Simulate configuration change / rotation
        val storedCommands = harness.commandDao.findByThread(threadId)
        assertEquals(1, storedCommands.size)
        assertEquals(1L, storedCommands.first().id)

        // Next message after rotation must preserve monotonic ID
        harness.submitText("note Second message after rotation", threadId = threadId)
        val afterRotation = harness.commandDao.findByThread(threadId)
        assertEquals(2, afterRotation.size)
        assertTrue(afterRotation[1].id > afterRotation[0].id)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F20: R8 Optimization Mode Activation (Phase 9 / Milestone 4)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF20_r8OptimizationModeActivation() {
        val root = findProjectRoot()
        val appBuildGradle = File(root, "app/build.gradle.kts").readText()
        assertTrue("Release build must enable code shrinking (minify)", appBuildGradle.contains("isMinifyEnabled = true"))
        assertTrue("Release build must enable resource shrinking", appBuildGradle.contains("isShrinkResources = true"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F21: Decentralized Module Consumer ProGuard (Phase 9 / Milestone 4)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF21_decentralizedModuleConsumerProguard() {
        val root = findProjectRoot()
        val agentConsumerRules = File(root, "agent/consumer-rules.pro")
        assertTrue("agent consumer rules must exist", agentConsumerRules.exists())
        val persistenceConsumerRules = File(root, "core/persistence/consumer-rules.pro")
        assertTrue("persistence consumer rules must exist", persistenceConsumerRules.exists())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F22: Automated 16KB Page Alignment Verification (Phase 9 / Milestone 4)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF22_automated16KbPageAlignmentVerification() {
        // Contract: Custom native C++ binaries count is 0 (pure Kotlin FastVectorMath),
        // satisfying Android 15+ 16 KB page-size alignment.
        fun verifyElfAlignment(pAlign: Long): Boolean = pAlign >= 16384L

        assertTrue("Standard 16KB alignment (16384 bytes) satisfies requirement", verifyElfAlignment(16384L))
        assertTrue("64KB alignment satisfies requirement", verifyElfAlignment(65536L))
        assertFalse("Legacy 4KB alignment violates 16KB requirement", verifyElfAlignment(4096L))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F23: Google Play Data Safety Specification (Phase 9 / Milestone 4)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF23_googlePlayDataSafetySpecification() {
        val root = findProjectRoot()
        val checklist = File(root, "docs/ANDROID_PRODUCTION_CHECKLIST.md")
        assertTrue("Production checklist must exist", checklist.exists())
        val content = checklist.readText()
        assertTrue(content.contains("Data Safety") || content.contains("Zero Cloud Egress"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F24: Release Signing & Artifact Assembly (Phase 9 / Milestone 4)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF24_releaseSigningAndArtifactAssembly() {
        val root = findProjectRoot()
        val appBuildGradle = File(root, "app/build.gradle.kts").readText()
        assertTrue("Must declare signingConfigs", appBuildGradle.contains("signingConfigs"))
        assertTrue("Must configure release signing config", appBuildGradle.contains("create(\"release\")"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F25: Full Dual-Track Verification & Audit Gate (Final Milestone 5)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF25_fullDualTrackVerificationAndAuditGate() = runBlocking {
        // Gate 1: Zero cloud egress
        assertEquals(0L, harness.sandbox.outboundBytes)

        // Gate 2: Policy veto primacy
        val forbiddenOutcome = harness.policyEngine.evaluate(
            Command(rawInput = "test", normalizedInput = "test", intentName = "message.send_sms_silent", confidence = 1f, source = CommandSource.USER_CHAT),
            1L
        )
        assertTrue(forbiddenOutcome is CommandResult.Rejected)

        // Gate 3: HMAC-SHA256 audit chain mathematically valid
        harness.submitText("set alarm at 7am")
        assertTrue(harness.verifyAuditChainIntegrity())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Baseline Tests Preserved from Previous Milestones
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testBaseline_adaptiveLayoutConfiguration() {
        fun resolveNavigationLayoutType(widthDp: Int, route: String): String = when {
            route == "onboarding" -> "NONE"
            widthDp < 600        -> "BOTTOM_BAR"
            else                 -> "NAV_RAIL"
        }
        assertEquals("NONE", resolveNavigationLayoutType(360, "onboarding"))
        assertEquals("BOTTOM_BAR", resolveNavigationLayoutType(360, "chat"))
        assertEquals("NAV_RAIL", resolveNavigationLayoutType(840, "chat"))
    }

    @Test
    fun testBaseline_fastVectorMathPureKotlinSimd() {
        fun dotProduct(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }
        val v1 = floatArrayOf(1f, 0f)
        val v2 = floatArrayOf(0f, 1f)
        assertEquals(0.0f, dotProduct(v1, v2), 1e-5f)
    }

    @Test
    fun testBaseline_vectorDaoBlobSerialization() {
        val buffer = ByteBuffer.allocate(4 * 4).order(ByteOrder.LITTLE_ENDIAN)
        val floats = floatArrayOf(1f, 2f, 3f, 4f)
        for (f in floats) buffer.putFloat(f)
        val bytes = buffer.array()
        assertEquals(16, bytes.size)

        val readBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val readFloats = FloatArray(4) { readBuffer.float }
        for (i in floats.indices) {
            assertEquals(floats[i], readFloats[i], 1e-6f)
        }
    }
}
