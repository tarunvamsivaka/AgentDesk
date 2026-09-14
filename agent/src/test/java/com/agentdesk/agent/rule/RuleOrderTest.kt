package com.agentdesk.agent.rule

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for ambiguous phrases (BUG-012):
 * - "navigate to airport" must route to maps.navigate (never swallowed by
 *   the generic APP_OPEN catch-all).
 * - "open maps" routes to app.open; the app.open tool then falls back to
 *   opening Maps home via a geo: URI when no installed app matches.
 */
class RuleOrderTest {

    private lateinit var engine: RuleEngine

    @Before
    fun setUp() {
        engine = RuleEngine()
    }

    @Test
    fun `navigate to airport routes to maps navigate`() {
        val result = engine.evaluate("navigate to airport")
        assertEquals(Intents.NAVIGATE_MAP, result!!.intentName)
        assertEquals("airport", result.entities["destination"])
    }

    @Test
    fun `directions phrasing routes to maps navigate`() {
        val result = engine.evaluate("take me to the station")
        assertEquals(Intents.NAVIGATE_MAP, result!!.intentName)
    }

    @Test
    fun `open maps routes to app open with fallback path`() {
        val result = engine.evaluate("open maps")
        assertEquals(Intents.APP_OPEN, result!!.intentName)
        assertEquals("maps", result.entities["appName"])
    }

    @Test
    fun `generic app open still works for non-navigation targets`() {
        val result = engine.evaluate("open youtube")
        assertEquals(Intents.APP_OPEN, result!!.intentName)
        assertEquals("youtube", result.entities["appName"])
    }
}
