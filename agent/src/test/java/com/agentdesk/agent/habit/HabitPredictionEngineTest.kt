package com.agentdesk.agent.habit

import com.agentdesk.agent.rule.Intents
import com.agentdesk.core.persistence.dao.UserHabitDao
import com.agentdesk.core.persistence.entity.UserHabitEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitPredictionEngineTest {

    private class FakeUserHabitDao : UserHabitDao {
        val habits = mutableListOf<UserHabitEntity>()

        override fun observeTopHabitsForHour(hourOfDay: Int, limit: Int): Flow<List<UserHabitEntity>> {
            val list = habits.filter { it.hourOfDay == hourOfDay }
                .sortedByDescending { it.interactionCount }
                .take(limit)
            return MutableStateFlow(list)
        }

        override suspend fun getTopHabitsForHour(hourOfDay: Int, limit: Int): List<UserHabitEntity> {
            return habits.filter { it.hourOfDay == hourOfDay }
                .sortedByDescending { it.interactionCount }
                .take(limit)
        }

        override suspend fun getHabit(packageName: String, hourOfDay: Int): UserHabitEntity? {
            return habits.firstOrNull { it.packageName == packageName && it.hourOfDay == hourOfDay }
        }

        override suspend fun getHabitForIntent(intent: String, hourOfDay: Int): UserHabitEntity? {
            return habits.firstOrNull { it.associatedIntent == intent && it.hourOfDay == hourOfDay }
        }

        override suspend fun insertOrUpdate(habit: UserHabitEntity): Long {
            habits.removeAll { it.packageName == habit.packageName && it.hourOfDay == habit.hourOfDay }
            habits.add(habit)
            return habit.id
        }

        override suspend fun insertAll(habits: List<UserHabitEntity>) {
            this.habits.addAll(habits)
        }

        override suspend fun deleteAll() {
            habits.clear()
        }
    }

    @Test
    fun `morning calendar queries are boosted to CALENDAR_CREATE between 7 and 9 AM`() {
        val engine = HabitPredictionEngine(FakeUserHabitDao())

        for (hour in 7..9) {
            val boostNext = engine.boostIntentIfHabitual("what's next", currentHour = hour)
            assertNotNull(boostNext)
            assertEquals(Intents.CALENDAR_CREATE, boostNext?.intentName)
            assertEquals(0.88f, boostNext?.confidence ?: 0f, 1e-4f)
            assertEquals("view_today", boostNext?.entities?.get("action"))

            val boostSchedule = engine.boostIntentIfHabitual("show schedule", currentHour = hour)
            assertNotNull(boostSchedule)
            assertEquals(Intents.CALENDAR_CREATE, boostSchedule?.intentName)

            val boostMyDay = engine.boostIntentIfHabitual("my day", currentHour = hour)
            assertNotNull(boostMyDay)
            assertEquals(Intents.CALENDAR_CREATE, boostMyDay?.intentName)
        }
    }

    @Test
    fun `morning calendar query returns null outside 7 to 9 AM window`() {
        val engine = HabitPredictionEngine(FakeUserHabitDao())

        assertNull(engine.boostIntentIfHabitual("what's next", currentHour = 6))
        assertNull(engine.boostIntentIfHabitual("what's next", currentHour = 10))
        assertNull(engine.boostIntentIfHabitual("show schedule", currentHour = 14))
        assertNull(engine.boostIntentIfHabitual("my day", currentHour = 20))
    }

    @Test
    fun `evening alarm queries are boosted to SET_ALARM between 21 and 23 PM`() {
        val engine = HabitPredictionEngine(FakeUserHabitDao())

        for (hour in 21..23) {
            val boostTomorrow = engine.boostIntentIfHabitual("tomorrow", currentHour = hour)
            assertNotNull(boostTomorrow)
            assertEquals(Intents.SET_ALARM, boostTomorrow?.intentName)
            assertEquals(0.90f, boostTomorrow?.confidence ?: 0f, 1e-4f)
            assertEquals("7", boostTomorrow?.entities?.get("hour"))
            assertEquals("0", boostTomorrow?.entities?.get("minute"))

            val boostWake = engine.boostIntentIfHabitual("wake me up", currentHour = hour)
            assertNotNull(boostWake)
            assertEquals(Intents.SET_ALARM, boostWake?.intentName)

            val boostBedtime = engine.boostIntentIfHabitual("bedtime", currentHour = hour)
            assertNotNull(boostBedtime)
            assertEquals(Intents.SET_ALARM, boostBedtime?.intentName)
        }
    }

    @Test
    fun `evening alarm query returns null outside 21 to 23 PM window`() {
        val engine = HabitPredictionEngine(FakeUserHabitDao())

        assertNull(engine.boostIntentIfHabitual("tomorrow", currentHour = 12))
        assertNull(engine.boostIntentIfHabitual("wake me up", currentHour = 18))
        assertNull(engine.boostIntentIfHabitual("bedtime", currentHour = 8))
    }

    @Test
    fun `unrelated or non-habitual query returns null`() {
        val engine = HabitPredictionEngine(FakeUserHabitDao())

        assertNull(engine.boostIntentIfHabitual("play rock music", currentHour = 8))
        assertNull(engine.boostIntentIfHabitual("open settings", currentHour = 22))
        assertNull(engine.boostIntentIfHabitual("", currentHour = 8))
    }

    @Test
    fun `observeActiveSuggestions emits proactive suggestions based on top habits`() = runBlocking {
        val dao = FakeUserHabitDao()
        dao.habits.add(
            UserHabitEntity(
                id = 1L,
                packageName = "com.google.android.calendar",
                appName = "Calendar",
                hourOfDay = 8,
                interactionCount = 10,
                associatedIntent = "calendar.create_event"
            )
        )
        dao.habits.add(
            UserHabitEntity(
                id = 2L,
                packageName = "com.spotify.music",
                appName = "Spotify",
                hourOfDay = 8,
                interactionCount = 5,
                associatedIntent = null
            )
        )

        val engine = HabitPredictionEngine(dao)
        val suggestions = engine.observeActiveSuggestions(hourOfDay = 8).first()

        assertEquals(2, suggestions.size)
        assertEquals("calendar.create_event", suggestions[0])
        assertEquals("Spotify", suggestions[1])
    }

    @Test
    fun `getActiveSuggestions queries DAO and returns non-empty list`() = runBlocking {
        val dao = FakeUserHabitDao()
        dao.habits.add(
            UserHabitEntity(
                id = 1L,
                packageName = "com.google.android.deskclock",
                appName = "Clock",
                hourOfDay = 22,
                interactionCount = 8,
                associatedIntent = "system.set_alarm"
            )
        )

        val engine = HabitPredictionEngine(dao)
        val suggestions = engine.getActiveSuggestions(hourOfDay = 22)
        assertEquals(1, suggestions.size)
        assertEquals("system.set_alarm", suggestions[0])

        val emptySuggestions = engine.getActiveSuggestions(hourOfDay = 3)
        assertTrue(emptySuggestions.isEmpty())
    }
}
