package com.agentdesk.agent.intent

import com.agentdesk.agent.prompt.PromptPrefixCatalog
import com.agentdesk.agent.rule.Intents
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceLlmManagerTest {

    @Test
    fun `ModelExecutionTier encompasses complete 4-tier pyramid`() {
        assertEquals(4, ModelExecutionTier.values().size)
        assertTrue(ModelExecutionTier.values().contains(ModelExecutionTier.TIER_0_REGEX))
        assertTrue(ModelExecutionTier.values().contains(ModelExecutionTier.TIER_1_AICORE))
        assertTrue(ModelExecutionTier.values().contains(ModelExecutionTier.TIER_2_LITERT))
        assertTrue(ModelExecutionTier.values().contains(ModelExecutionTier.TIER_3_CLARIFICATION))
    }

    @Test
    fun `DefaultOnDeviceLlmManager runs on Tier 2 in absence of AICore package`() {
        val manager = DefaultOnDeviceLlmManager(context = null)
        assertTrue(manager.isModelAvailable)
        assertEquals(ModelExecutionTier.TIER_2_LITERT, manager.currentExecutionTier)
    }

    @Test
    fun `DefaultOnDeviceLlmManager degrades to Tier 3 when thermal throttled`() = runBlocking {
        val manager = DefaultOnDeviceLlmManager()
        val result = manager.classifyIntent("wake me up at 7am", thermalThrottled = true)
        assertEquals("clarification", result.intentName)
        assertTrue(result.clarificationChips.isNotEmpty())
    }

    @Test
    fun `DefaultOnDeviceLlmManager degrades to Tier 3 on blank input`() = runBlocking {
        val manager = DefaultOnDeviceLlmManager()
        val result = manager.classifyIntent("   ", thermalThrottled = false)
        assertEquals("clarification", result.intentName)
    }

    @Test
    fun `DefaultOnDeviceLlmManager classifies natural language intents correctly`() = runBlocking {
        val manager = DefaultOnDeviceLlmManager()

        val alarm = manager.classifyIntent("wake me up at 7")
        assertEquals(Intents.SET_ALARM, alarm.intentName)
        assertEquals("7", alarm.entities["hour"])

        val sms = manager.classifyIntent("send a message to John")
        assertEquals(Intents.SMS_DRAFT, sms.intentName)
        assertTrue(sms.requiresConfirmation)

        val health = manager.classifyIntent("how hot is the phone")
        assertEquals(Intents.DEVICE_HEALTH, health.intentName)

        val unknown = manager.classifyIntent("completely unfamiliar query xyz")
        assertEquals("unknown", unknown.intentName)
        assertTrue(unknown.clarificationChips.isNotEmpty())
    }

    @Test
    fun `AiCoreLlmClassifier defaults to Tier 2 fallback when AICore is not installed`() = runBlocking {
        val classifier = AiCoreLlmClassifier(context = null)
        assertFalse(classifier.isAiCoreInstalled())
        assertEquals(FeatureStatus.UNAVAILABLE, classifier.checkFeatureStatus())
        assertEquals(ModelExecutionTier.TIER_2_LITERT, classifier.currentExecutionTier)

        assertTrue(classifier.warmup())

        val result = classifier.classifyIntent("wake me up tomorrow", thermalThrottled = false)
        assertEquals(Intents.SET_ALARM, result.intentName)

        val throttled = classifier.classifyIntent("wake me up tomorrow", thermalThrottled = true)
        assertEquals("clarification", throttled.intentName)

        classifier.close()
    }

    @Test
    fun `PromptPrefixCatalog exceeds 600 tokens for Gemini Nano KV cache acceleration`() {
        val estimatedTokens = PromptPrefixCatalog.estimateTokenCount()
        assertTrue("Expected prompt prefix > 600 tokens, was $estimatedTokens", estimatedTokens >= 600)
    }
}
