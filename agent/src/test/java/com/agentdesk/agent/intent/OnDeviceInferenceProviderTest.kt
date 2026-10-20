package com.agentdesk.agent.intent

import com.agentdesk.agent.prompt.PromptPrefixCatalog
import com.agentdesk.agent.rule.Intents
import com.agentdesk.core.common.model.DeviceTier
import com.agentdesk.core.device.DeviceProfiler
import com.agentdesk.core.device.ResourceGovernor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OnDeviceInferenceProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestDeviceProfiler(
        var battery: Int = 80,
        var charging: Boolean = false,
        var thermal: Int = 0,
        var ramMb: Long = 4096L,
        var storageMb: Long = 10000L,
        var tier: DeviceTier = DeviceTier.FULL
    ) : DeviceProfiler() {
        override fun getBatteryPercent(): Int = battery
        override fun isCharging(): Boolean = charging
        override fun getThermalStatus(): Int = thermal
        override fun getAvailableRamMb(): Long = ramMb
        override fun isLowMemory(): Boolean = ramMb < 512L
        override fun getAvailableStorageMb(): Long = storageMb
        override fun classifyTier(): DeviceTier = tier
    }

    private class TestPromptEngine(
        var status: FeatureStatus = FeatureStatus.AVAILABLE,
        var structuredResponse: String? = null
    ) : PromptApiEngine {
        var warmupCalled = false
        var closeCalled = false

        override suspend fun checkStatus(): FeatureStatus = status
        override suspend fun warmup(prefix: String): Boolean {
            warmupCalled = true
            return true
        }
        override suspend fun generateStructuredIntent(userInput: String): String? = structuredResponse
        override fun close() {
            closeCalled = true
        }
    }

    private lateinit var profiler: TestDeviceProfiler
    private lateinit var governor: ResourceGovernor

    @Before
    fun setUp() {
        profiler = TestDeviceProfiler()
        governor = ResourceGovernor(profiler)
    }

    @Test
    fun `MlKitPromptApiProvider parses structured JSON output and respects availability`() = runBlocking {
        val engine = TestPromptEngine(
            status = FeatureStatus.AVAILABLE,
            structuredResponse = """
                {
                    "intent": "system.set_alarm",
                    "confidence": 0.96,
                    "slots": { "hour": "8", "minute": "30" },
                    "requiresConfirmation": false
                }
            """.trimIndent()
        )
        val provider = MlKitPromptApiProvider(context = null, promptEngine = engine)

        assertTrue(provider.isAvailable())
        assertTrue(provider.warmup())
        assertTrue(engine.warmupCalled)

        val result = provider.classifyIntent("wake me up at 8:30")
        assertNotNull(result)
        assertEquals("system.set_alarm", result?.intentName)
        assertEquals(0.96f, result?.confidence ?: 0f, 0.01f)
        assertEquals("8", result?.entities?.get("hour"))
        assertEquals("30", result?.entities?.get("minute"))

        val streamChunk = provider.streamResponse("test").first()
        assertTrue(streamChunk.contains("ML Kit GenAI Prompt API"))

        provider.releaseResources()
        assertTrue(engine.closeCalled)
    }

    @Test
    fun `MlKitPromptApiProvider returns null when AICore is unavailable`() = runBlocking {
        val engine = TestPromptEngine(status = FeatureStatus.UNAVAILABLE)
        val provider = MlKitPromptApiProvider(context = null, promptEngine = engine)

        assertFalse(provider.isAvailable())
        assertFalse(provider.warmup())
        assertNull(provider.classifyIntent("wake me up"))
    }

    @Test
    fun `OnnxLocalSlmProvider discovers local model bundle in models directory`() = runBlocking {
        val modelsDir = tempFolder.newFolder("models")
        val onnxFile = File(modelsDir, "agentdesk-slm-4bit.onnx")
        onnxFile.writeText("MOCK_ONNX_MODEL_BINARY_DATA")

        val provider = OnnxLocalSlmProvider(
            context = null,
            resourceGovernor = governor,
            customModelsDir = modelsDir
        )

        assertTrue("Provider must be available when valid model file exists", provider.isAvailable())
        val result = provider.classifyIntent("wake me up at 7am")
        assertNotNull(result)
        assertEquals(Intents.SET_ALARM, result?.intentName)

        provider.releaseResources()
    }

    @Test
    fun `OnnxLocalSlmProvider throttles when ResourceGovernor disallows inference`() = runBlocking {
        val modelsDir = tempFolder.newFolder("models")
        val onnxFile = File(modelsDir, "slm.onnx")
        onnxFile.writeText("ONNX_DATA")

        // Thermal status SEVERE
        profiler.thermal = 3
        assertFalse(governor.canRunLlm())

        val provider = OnnxLocalSlmProvider(
            context = null,
            resourceGovernor = governor,
            customModelsDir = modelsDir
        )

        assertFalse("Provider must disallow when thermal throttled", provider.isAvailable())
        assertNull(provider.classifyIntent("wake me up"))
    }

    @Test
    fun `DeterministicRuleFallback succeeds even under complete resource starvation`() = runBlocking {
        val fallback = DeterministicRuleFallback()
        assertTrue(fallback.isAvailable())

        val alarm = fallback.classifyIntent("wake me up tomorrow")
        assertEquals(Intents.SET_ALARM, alarm.intentName)

        val sms = fallback.classifyIntent("ping John")
        assertEquals(Intents.SMS_DRAFT, sms.intentName)
        assertTrue(sms.requiresConfirmation)

        val unknown = fallback.classifyIntent("xyz123 random words")
        assertEquals("unknown", unknown.intentName)
        assertTrue(unknown.clarificationChips.isNotEmpty())

        val blank = fallback.classifyIntent("   ")
        assertEquals("clarification", blank.intentName)
    }

    @Test
    fun `CascadingOnDeviceLlmManager dynamically cascades across providers`() = runBlocking {
        val engine = TestPromptEngine(
            status = FeatureStatus.AVAILABLE,
            structuredResponse = """
                {
                    "intent": "${Intents.DEVICE_HEALTH}",
                    "confidence": 0.99
                }
            """.trimIndent()
        )
        val mlKit = MlKitPromptApiProvider(context = null, promptEngine = engine)
        val onnx = OnnxLocalSlmProvider(context = null, resourceGovernor = governor, customModelsDir = tempFolder.root)
        val ruleFallback = DeterministicRuleFallback()

        val cascading = CascadingOnDeviceLlmManager(
            mlKitProvider = mlKit,
            onnxProvider = onnx,
            ruleFallback = ruleFallback,
            resourceGovernor = governor,
            profiler = profiler
        )

        // 1. Should route to ML Kit when available
        val res1 = cascading.classifyIntent("check battery status")
        assertEquals(Intents.DEVICE_HEALTH, res1.intentName)

        // 2. When ML Kit is unavailable, should fall back cleanly
        engine.status = FeatureStatus.UNAVAILABLE
        val res2 = cascading.classifyIntent("wake me up tomorrow")
        assertEquals(Intents.SET_ALARM, res2.intentName)

        // 3. When thermally throttled, should degrade to clarification
        val res3 = cascading.classifyIntent("wake me up tomorrow", thermalThrottled = true)
        assertEquals("clarification", res3.intentName)

        cascading.close()
    }
}
