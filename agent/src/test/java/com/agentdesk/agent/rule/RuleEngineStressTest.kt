package com.agentdesk.agent.rule

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Empirical stress test suite by Challenger 2 for Milestone 2 (Phase 3).
 *
 * Evaluates:
 * 1. All 11 core tool intents across phrasing variations.
 * 2. Dispatch challenge cases:
 *    - "search web for kotlin"
 *    - "google android"
 *    - "set timer 5 mins"
 *    - "create note meeting"
 *    - "text Alice: hello"
 * 3. Boundary and edge conditions.
 */
class RuleEngineStressTest {

    private lateinit var engine: RuleEngine

    @Before
    fun setUp() {
        engine = RuleEngine()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Core 11 Tools — Standard Phrasings
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testAll11CoreToolsStandardPhrasings() {
        val testCases = listOf(
            "set alarm at 7:00 am" to Intents.SET_ALARM,
            "timer for 5 minutes" to Intents.SET_TIMER,
            "note down buy milk" to Intents.NOTE_CREATE,
            "schedule a meeting with team" to Intents.CALENDAR_CREATE,
            "text alice saying running late" to Intents.SMS_DRAFT,
            "search the web for kotlin coroutines" to Intents.WEB_SEARCH,
            "check battery status" to Intents.DEVICE_HEALTH,
            "find passport photo" to Intents.LIBRARY_SEARCH,
            "call 555-1234" to Intents.DIAL_NUMBER,
            "navigate to airport" to Intents.NAVIGATE_MAP,
            "open camera" to Intents.APP_OPEN
        )

        for ((input, expectedIntent) in testCases) {
            val result = engine.evaluate(input)
            assertNotNull("Input '$input' failed to match", result)
            assertEquals("Input '$input' matched wrong intent", expectedIntent, result!!.intentName)
            assertTrue("Input '$input' confidence < 0.7", result.confidence >= 0.7f)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Dispatch Challenge Cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testChallenge_searchWebForKotlin() {
        // Dispatch variation: "search web for kotlin"
        val result = engine.evaluate("search web for kotlin")
        assertNotNull("Must match WEB_SEARCH", result)
        assertEquals(Intents.WEB_SEARCH, result!!.intentName)
        assertEquals("kotlin", result.entities["query"])
    }

    @Test
    fun testChallenge_googleAndroid() {
        // Dispatch variation: "google android"
        val result = engine.evaluate("google android")
        assertNotNull("Must match WEB_SEARCH", result)
        assertEquals(Intents.WEB_SEARCH, result!!.intentName)
        assertEquals("android", result.entities["query"])
    }

    @Test
    fun testChallenge_createNoteMeeting() {
        // Dispatch variation: "create note meeting"
        val result = engine.evaluate("create note meeting")
        assertNotNull("Must match NOTE_CREATE", result)
        assertEquals(Intents.NOTE_CREATE, result!!.intentName)
        assertEquals("meeting", result.entities["text"])
    }

    @Test
    fun testChallenge_setTimerPhrasings() {
        // "timer for 5 minutes" -> matches
        val standard = engine.evaluate("timer for 5 minutes")
        assertNotNull("Standard minutes must match", standard)
        assertEquals(Intents.SET_TIMER, standard!!.intentName)
        assertEquals("5", standard.entities["duration"])
        assertEquals("minute", standard.entities["unit"])

        // "set timer for 10 seconds" -> matches
        val seconds = engine.evaluate("set timer for 10 seconds")
        assertNotNull("Seconds must match", seconds)
        assertEquals(Intents.SET_TIMER, seconds!!.intentName)

        // "set a timer for 1 hour" -> matches
        val hour = engine.evaluate("set a timer for 1 hour")
        assertNotNull("Hour must match", hour)
        assertEquals(Intents.SET_TIMER, hour!!.intentName)

        // Edge phrasing: "set timer 5 mins"
        // Empirical observation: RuleEngine regex uses (minute|second|hour)s? which requires full word "minute(s)"
        // Therefore abbreviations like "mins" return null
        val abbrev = engine.evaluate("set timer 5 mins")
        assertNull("Empirically proven: 'set timer 5 mins' returns null due to strict (minute|second|hour)s? regex", abbrev)
    }

    @Test
    fun testChallenge_textAlicePhrasings() {
        // "text alice saying hello" -> matches
        val saying = engine.evaluate("text alice saying hello")
        assertNotNull(saying)
        assertEquals(Intents.SMS_DRAFT, saying!!.intentName)
        assertEquals("alice", saying.entities["contact"])
        assertEquals("hello", saying.entities["body"])

        // "text alice that hello" -> matches
        val that = engine.evaluate("text alice that hello")
        assertNotNull(that)
        assertEquals(Intents.SMS_DRAFT, that!!.intentName)
        assertEquals("alice", that.entities["contact"])
        assertEquals("hello", that.entities["body"])

        // "text alice : hello" (with space before colon) -> matches
        val spaceColon = engine.evaluate("text alice : hello")
        assertNotNull(spaceColon)
        assertEquals(Intents.SMS_DRAFT, spaceColon!!.intentName)

        // "text alice: hello" (no space before colon)
        // Empirical observation: RuleEngine regex has "\s+(?:saying|that|:)\s+" which mandates leading space before colon
        val directColon = engine.evaluate("text alice: hello")
        assertNull("Empirically proven: 'text alice: hello' returns null due to mandatory whitespace before colon", directColon)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Web Search vs Library Search Disambiguation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testWebVsLibraryDisambiguation() {
        // Explicit web searches must NEVER resolve to library search
        val web1 = engine.evaluate("search online for weather")
        assertEquals(Intents.WEB_SEARCH, web1?.intentName)

        val web2 = engine.evaluate("search internet for news")
        assertEquals(Intents.WEB_SEARCH, web2?.intentName)

        val web3 = engine.evaluate("search the web for recipes")
        assertEquals(Intents.WEB_SEARCH, web3?.intentName)

        val web4 = engine.evaluate("google quantum computing")
        assertEquals(Intents.WEB_SEARCH, web4?.intentName)

        // Local library searches must resolve to LIBRARY_SEARCH
        val lib1 = engine.evaluate("find my boarding pass")
        assertEquals(Intents.LIBRARY_SEARCH, lib1?.intentName)

        val lib2 = engine.evaluate("search annual report pdf")
        assertEquals(Intents.LIBRARY_SEARCH, lib2?.intentName)

        val lib3 = engine.evaluate("look for contract draft")
        assertEquals(Intents.LIBRARY_SEARCH, lib3?.intentName)

        val lib4 = engine.evaluate("where is my resume")
        assertEquals(Intents.LIBRARY_SEARCH, lib4?.intentName)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Navigation vs App Open Disambiguation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testNavigateVsAppOpenDisambiguation() {
        // Navigation phrasings must resolve to NAVIGATE_MAP
        val nav1 = engine.evaluate("navigate to san francisco")
        assertEquals(Intents.NAVIGATE_MAP, nav1?.intentName)
        assertEquals("san francisco", nav1?.entities?.get("destination"))

        val nav2 = engine.evaluate("directions to golden gate park")
        assertEquals(Intents.NAVIGATE_MAP, nav2?.intentName)
        assertEquals("golden gate park", nav2?.entities?.get("destination"))

        val nav3 = engine.evaluate("take me home")
        assertEquals(Intents.NAVIGATE_MAP, nav3?.intentName)
        assertEquals("home", nav3?.entities?.get("destination"))

        // BUG FINDING: "open google maps" is shadowed by WEB_SEARCH because "google\s+(.+)" is unanchored!
        val appGoogle = engine.evaluate("open google maps")
        assertEquals("Demonstrates shadowing: 'google' inside app name triggers WEB_SEARCH", Intents.WEB_SEARCH, appGoogle?.intentName)

        // Generic app opens without 'google' resolve to APP_OPEN
        val app1 = engine.evaluate("open maps")
        assertEquals(Intents.APP_OPEN, app1?.intentName)
        assertEquals("maps", app1?.entities?.get("appName"))

        val app2 = engine.evaluate("launch camera")
        assertEquals(Intents.APP_OPEN, app2?.intentName)
        assertEquals("camera", app2?.entities?.get("appName"))

        val app3 = engine.evaluate("start settings")
        assertEquals(Intents.APP_OPEN, app3?.intentName)
        assertEquals("settings", app3?.entities?.get("appName"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Device Health Variations
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testDeviceHealthVariations() {
        val healthInputs = listOf(
            "check device health",
            "device health",
            "battery status",
            "check battery level",
            "storage status",
            "storage space",
            "storage free",
            "battery",
            "storage",
            "health"
        )
        for (input in healthInputs) {
            val result = engine.evaluate(input)
            assertNotNull("Device health input '$input' must match", result)
            assertEquals("Input '$input' must be DEVICE_HEALTH", Intents.DEVICE_HEALTH, result!!.intentName)
        }
    }
}
