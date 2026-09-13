package com.agentdesk.agent.rule

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for RuleEngine.
 *
 * Uses actual API: engine.evaluate(text): RuleResult?
 * Intent names from Intents object: SET_ALARM, SET_TIMER, NOTE_CREATE, APP_OPEN, SMS_DRAFT
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
    }

    @Test
    fun `draft sms - message that phrasing`() {
        val result = engine.evaluate("message mom that dinner is ready")
        assertNotNull(result)
        assertEquals(Intents.SMS_DRAFT, result!!.intentName)
    }

    // ─── Unknown ──────────────────────────────────────────────────────────────

    @Test
    fun `unknown input returns null or very low confidence`() {
        val result = engine.evaluate("zzzblahblah123nonsense")
        // Either null (no match) or confidence < 0.5
        assertTrue(result == null || result.confidence < 0.5f)
    }
}
