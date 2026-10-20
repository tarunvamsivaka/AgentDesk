package com.agentdesk.agent.rule

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for RuleEngine — verifies deterministic 11-tool intent classification
 * and slot entity extraction across all supported device capabilities.
 */
class RuleEngineTest {

    private lateinit var engine: RuleEngine

    @Before
    fun setUp() {
        engine = RuleEngine()
    }

    // ─── Set Alarm ────────────────────────────────────────────────────────────

    @Test
    fun `set alarm - full match returns set_alarm intent`() {
        val result = engine.evaluate("set alarm at 7am")
        assertNotNull(result)
        assertEquals(Intents.SET_ALARM, result!!.intentName)
        assertTrue("Expected confidence >= 0.7", result.confidence >= 0.7f)
    }

    @Test
    fun `set alarm - extracts time entity`() {
        val result = engine.evaluate("set alarm at 6:30 am")
        assertNotNull(result)
        assertEquals(Intents.SET_ALARM, result!!.intentName)
        assertNotNull(result.entities["time"])
        assertTrue(result.entities["time"]!!.isNotBlank())
    }

    @Test
    fun `wake me phrasing matches set_alarm`() {
        val result = engine.evaluate("wake me at 8pm")
        assertNotNull(result)
        assertEquals(Intents.SET_ALARM, result!!.intentName)
    }

    // ─── Set Timer ────────────────────────────────────────────────────────────

    @Test
    fun `set timer - minutes returns set_timer intent`() {
        val result = engine.evaluate("timer for 5 minutes")
        assertNotNull(result)
        assertEquals(Intents.SET_TIMER, result!!.intentName)
        assertTrue("Expected confidence >= 0.7", result.confidence >= 0.7f)
    }

    @Test
    fun `set timer - seconds`() {
        val result = engine.evaluate("timer for 30 seconds")
        assertNotNull(result)
        assertEquals(Intents.SET_TIMER, result!!.intentName)
    }

    @Test
    fun `set timer - hours`() {
        val result = engine.evaluate("set a timer for 2 hours")
        assertNotNull(result)
        assertEquals(Intents.SET_TIMER, result!!.intentName)
    }

    // ─── Create Note ──────────────────────────────────────────────────────────

    @Test
    fun `create note - basic`() {
        val result = engine.evaluate("note down buy milk")
        assertNotNull(result)
        assertEquals(Intents.NOTE_CREATE, result!!.intentName)
        assertTrue("Expected confidence >= 0.7", result.confidence >= 0.7f)
    }

    @Test
    fun `remember phrasing matches note_create`() {
        val result = engine.evaluate("remember to call dentist")
        assertNotNull(result)
        assertEquals(Intents.NOTE_CREATE, result!!.intentName)
    }

    // ─── Calendar Create ──────────────────────────────────────────────────────

    @Test
    fun `create calendar event matches calendar_create`() {
        val result = engine.evaluate("schedule a meeting with alex")
        assertNotNull(result)
        assertEquals(Intents.CALENDAR_CREATE, result!!.intentName)
        assertEquals("with alex", result.entities["title"])
    }

    // ─── Open App ─────────────────────────────────────────────────────────────

    @Test
    fun `open app - basic`() {
        val result = engine.evaluate("open maps")
        assertNotNull(result)
        assertEquals(Intents.APP_OPEN, result!!.intentName)
        assertTrue("Expected confidence >= 0.7", result.confidence >= 0.7f)
    }

    @Test
    fun `launch phrasing matches app_open`() {
        val result = engine.evaluate("launch spotify")
        assertNotNull(result)
        assertEquals(Intents.APP_OPEN, result!!.intentName)
    }

    @Test
    fun `open app - extracts app name`() {
        val result = engine.evaluate("open youtube")
        assertNotNull(result)
        assertEquals(Intents.APP_OPEN, result!!.intentName)
        assertEquals("youtube", result.entities["appName"])
    }

    // ─── Draft SMS ────────────────────────────────────────────────────────────

    @Test
    fun `draft sms - text saying phrasing`() {
        val result = engine.evaluate("text john saying i am on my way")
        assertNotNull(result)
        assertEquals(Intents.SMS_DRAFT, result!!.intentName)
        assertTrue("Expected confidence >= 0.7", result.confidence >= 0.7f)
        assertEquals("john", result.entities["contact"])
        assertEquals("i am on my way", result.entities["body"])
    }

    @Test
    fun `draft sms - message that phrasing`() {
        val result = engine.evaluate("message mom that dinner is ready")
        assertNotNull(result)
        assertEquals(Intents.SMS_DRAFT, result!!.intentName)
    }

    // ─── Dial Number ──────────────────────────────────────────────────────────

    @Test
    fun `call phrasing matches dial_number`() {
        val result = engine.evaluate("call alice")
        assertNotNull(result)
        assertEquals(Intents.DIAL_NUMBER, result!!.intentName)
        assertEquals("alice", result.entities["contact"])
    }

    // ─── Web Search ───────────────────────────────────────────────────────────

    @Test
    fun `search web for query extracts web_search intent and query entity`() {
        val result = engine.evaluate("search the web for kotlin coroutines")
        assertNotNull(result)
        assertEquals(Intents.WEB_SEARCH, result!!.intentName)
        assertEquals("kotlin coroutines", result.entities["query"])
    }

    @Test
    fun `search online extracts web_search intent and query entity`() {
        val result = engine.evaluate("search online for weather today")
        assertNotNull(result)
        assertEquals(Intents.WEB_SEARCH, result!!.intentName)
        assertEquals("weather today", result.entities["query"])
    }

    @Test
    fun `google query extracts web_search intent and query entity`() {
        val result = engine.evaluate("google android compose documentation")
        assertNotNull(result)
        assertEquals(Intents.WEB_SEARCH, result!!.intentName)
        assertEquals("android compose documentation", result.entities["query"])
    }

    // ─── Device Health ────────────────────────────────────────────────────────

    @Test
    fun `battery status matches device_health`() {
        val result = engine.evaluate("check battery status")
        assertNotNull(result)
        assertEquals(Intents.DEVICE_HEALTH, result!!.intentName)
    }

    @Test
    fun `device health matches device_health`() {
        val result = engine.evaluate("device health")
        assertNotNull(result)
        assertEquals(Intents.DEVICE_HEALTH, result!!.intentName)
    }

    // ─── Library Search vs Web Search ─────────────────────────────────────────

    @Test
    fun `find document routes to library_search`() {
        val result = engine.evaluate("find tax return 2025")
        assertNotNull(result)
        assertEquals(Intents.LIBRARY_SEARCH, result!!.intentName)
        assertEquals("tax return 2025", result.entities["query"])
    }

    // ─── All 11 Tools Deterministic Resolution ───────────────────────────────

    @Test
    fun `all 11 core tool intents resolve deterministically with confidence ge 0_7`() {
        val inputs = listOf(
            "set alarm at 7:00 am" to Intents.SET_ALARM,
            "timer for 10 minutes" to Intents.SET_TIMER,
            "note down buy groceries" to Intents.NOTE_CREATE,
            "schedule a meeting with team" to Intents.CALENDAR_CREATE,
            "text alice saying running late" to Intents.SMS_DRAFT,
            "search the web for rust language" to Intents.WEB_SEARCH,
            "check battery level" to Intents.DEVICE_HEALTH,
            "where is my passport" to Intents.LIBRARY_SEARCH,
            "call 5551234" to Intents.DIAL_NUMBER,
            "navigate to downtown" to Intents.NAVIGATE_MAP,
            "open camera" to Intents.APP_OPEN
        )

        for ((input, expectedIntent) in inputs) {
            val result = engine.evaluate(input)
            assertNotNull("Failed to match input: $input", result)
            assertEquals("Wrong intent for $input", expectedIntent, result!!.intentName)
            assertTrue("Confidence too low for $input", result.confidence >= 0.7f)
        }
    }

    // ─── Unknown ──────────────────────────────────────────────────────────────

    @Test
    fun `unknown input returns null or very low confidence`() {
        val result = engine.evaluate("zzzblahblah123nonsense")
        assertTrue(result == null || result.confidence < 0.5f)
    }

    // ─── Custom User Rules ───────────────────────────────────────────────────

    @Test
    fun `custom user rule matches with highest priority and confidence 1_0`() {
        val customRule = RuleEngine.CustomRule(
            triggerPhrase = "flight status",
            intentName = Intents.APP_OPEN,
            targetAppOrPackage = "com.airline.app"
        )
        engine.registerCustomRule(customRule)

        val result = engine.evaluate("check flight status please")
        assertNotNull(result)
        assertEquals(Intents.APP_OPEN, result!!.intentName)
        assertEquals(1.0f, result.confidence, 1e-6f)
        assertEquals("com.airline.app", result.entities["app"])
    }

    @Test
    fun `custom user rule can be removed`() {
        val customRule = RuleEngine.CustomRule(
            id = "test-rule-1",
            triggerPhrase = "open my notes",
            intentName = Intents.NOTE_CREATE
        )
        engine.registerCustomRule(customRule)
        assertEquals(1, engine.getCustomRules().size)

        engine.removeCustomRule("test-rule-1")
        assertEquals(0, engine.getCustomRules().size)
    }
}
