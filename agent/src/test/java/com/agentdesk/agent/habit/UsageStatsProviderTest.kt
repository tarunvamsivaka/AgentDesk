package com.agentdesk.agent.habit

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageStatsProviderTest {

    private class FakeContext : ContextWrapper(null)

    @Test
    fun `hasPermission returns false safely when AppOps service is unavailable`() {
        val provider = UsageStatsProvider(FakeContext())
        assertFalse(provider.hasPermission())
    }

    @Test
    fun `queryRecentForegroundEvents returns empty list when permission is not granted`() {
        val provider = UsageStatsProvider(FakeContext())
        val events = provider.queryRecentForegroundEvents()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `simulated UsageStatsProvider emits foreground events when permitted`() {
        val simulatedEvents = listOf(
            ForegroundAppEvent("com.google.android.calendar", 8, 1_000_000L),
            ForegroundAppEvent("com.google.android.deskclock", 22, 2_000_000L),
            ForegroundAppEvent("com.google.android.apps.maps", 14, 3_000_000L)
        )

        val provider = object : UsageStatsProvider(FakeContext()) {
            override fun hasPermission(): Boolean = true
            override fun queryRecentForegroundEvents(durationMs: Long): List<ForegroundAppEvent> = simulatedEvents
        }

        assertTrue(provider.hasPermission())
        val result = provider.queryRecentForegroundEvents(86_400_000L)
        assertEquals(3, result.size)
        assertEquals("com.google.android.calendar", result[0].packageName)
        assertEquals(8, result[0].hourOfDay)
        assertEquals(1_000_000L, result[0].timestamp)
    }

    @Test
    fun `ForegroundAppEvent data model holds properties accurately`() {
        val event = ForegroundAppEvent(
            packageName = "com.android.chrome",
            hourOfDay = 15,
            timestamp = 1700000000000L
        )
        assertEquals("com.android.chrome", event.packageName)
        assertEquals(15, event.hourOfDay)
        assertEquals(1700000000000L, event.timestamp)
    }
}
