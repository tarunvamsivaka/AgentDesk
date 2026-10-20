package com.agentdesk.core.persistence.dao

import com.agentdesk.core.persistence.entity.UserHabitEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Contract test for [UserHabitDao] — validates insertion, upsert behavior,
 * hourly aggregation query ordering, intent matching, batch insertion, and deletion.
 */
class UserHabitDaoTest {

    private class FakeUserHabitDao : UserHabitDao {
        private val habits = mutableListOf<UserHabitEntity>()
        private val flow = MutableStateFlow<List<UserHabitEntity>>(emptyList())
        private var nextId = 1L

        override fun observeTopHabitsForHour(hourOfDay: Int, limit: Int): Flow<List<UserHabitEntity>> {
            val topHabits = habits.filter { it.hourOfDay == hourOfDay }
                .sortedByDescending { it.interactionCount }
                .take(limit)
            return MutableStateFlow(topHabits)
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
            val existingIndex = habits.indexOfFirst {
                it.packageName == habit.packageName && it.hourOfDay == habit.hourOfDay
            }
            return if (existingIndex >= 0) {
                val updated = habit.copy(id = habits[existingIndex].id)
                habits[existingIndex] = updated
                flow.value = habits.toList()
                updated.id
            } else {
                val id = if (habit.id == 0L) nextId++ else habit.id
                val saved = habit.copy(id = id)
                habits.add(saved)
                flow.value = habits.toList()
                id
            }
        }

        override suspend fun insertAll(habitsToInsert: List<UserHabitEntity>) {
            for (h in habitsToInsert) {
                insertOrUpdate(h)
            }
        }

        override suspend fun deleteAll() {
            habits.clear()
            flow.value = emptyList()
        }
    }

    private fun habit(
        packageName: String,
        appName: String = "",
        hourOfDay: Int,
        interactionCount: Int = 1,
        associatedIntent: String? = null
    ) = UserHabitEntity(
        packageName = packageName,
        appName = appName,
        hourOfDay = hourOfDay,
        interactionCount = interactionCount,
        associatedIntent = associatedIntent
    )

    @Test
    fun `insertOrUpdate inserts new habit and gets by package and hour`() = runBlocking {
        val dao = FakeUserHabitDao()
        val id = dao.insertOrUpdate(habit("com.google.android.calendar", "Calendar", 8, 5, "calendar.create_event"))
        assertTrue(id > 0)

        val retrieved = dao.getHabit("com.google.android.calendar", 8)
        assertNotNull(retrieved)
        assertEquals("Calendar", retrieved?.appName)
        assertEquals(5, retrieved?.interactionCount)
        assertEquals("calendar.create_event", retrieved?.associatedIntent)
    }

    @Test
    fun `insertOrUpdate replaces existing habit for same package and hour`() = runBlocking {
        val dao = FakeUserHabitDao()
        dao.insertOrUpdate(habit("com.google.android.deskclock", "Clock", 22, 3, "system.set_alarm"))
        dao.insertOrUpdate(habit("com.google.android.deskclock", "Clock", 22, 10, "system.set_alarm"))

        val retrieved = dao.getHabit("com.google.android.deskclock", 22)
        assertNotNull(retrieved)
        assertEquals(10, retrieved?.interactionCount)
    }

    @Test
    fun `getTopHabitsForHour returns ordered by interaction count descending up to limit`() = runBlocking {
        val dao = FakeUserHabitDao()
        dao.insertOrUpdate(habit("com.pkg.c", "C", 8, 2))
        dao.insertOrUpdate(habit("com.pkg.a", "A", 8, 15))
        dao.insertOrUpdate(habit("com.pkg.b", "B", 8, 8))
        dao.insertOrUpdate(habit("com.pkg.d", "D", 8, 1))
        dao.insertOrUpdate(habit("com.pkg.other", "Other", 9, 20)) // Different hour

        val top2 = dao.getTopHabitsForHour(8, limit = 2)
        assertEquals(2, top2.size)
        assertEquals("com.pkg.a", top2[0].packageName)
        assertEquals(15, top2[0].interactionCount)
        assertEquals("com.pkg.b", top2[1].packageName)
        assertEquals(8, top2[1].interactionCount)
    }

    @Test
    fun `observeTopHabitsForHour emits top habits for specified hour`() = runBlocking {
        val dao = FakeUserHabitDao()
        dao.insertOrUpdate(habit("com.pkg.a", "A", 12, 10))
        dao.insertOrUpdate(habit("com.pkg.b", "B", 12, 20))

        val observed = dao.observeTopHabitsForHour(12, limit = 5).first()
        assertEquals(2, observed.size)
        assertEquals("com.pkg.b", observed[0].packageName)
        assertEquals("com.pkg.a", observed[1].packageName)
    }

    @Test
    fun `getHabitForIntent retrieves habit matching intent and hour`() = runBlocking {
        val dao = FakeUserHabitDao()
        dao.insertOrUpdate(habit("com.google.android.calendar", "Calendar", 8, 5, "calendar.create_event"))

        val match = dao.getHabitForIntent("calendar.create_event", 8)
        assertNotNull(match)
        assertEquals("com.google.android.calendar", match?.packageName)

        val noMatch = dao.getHabitForIntent("calendar.create_event", 15)
        assertNull(noMatch)
    }

    @Test
    fun `insertAll inserts multiple habits and deleteAll clears them`() = runBlocking {
        val dao = FakeUserHabitDao()
        val list = listOf(
            habit("com.pkg.1", "1", 7, 3),
            habit("com.pkg.2", "2", 7, 5)
        )
        dao.insertAll(list)

        val top = dao.getTopHabitsForHour(7, limit = 10)
        assertEquals(2, top.size)

        dao.deleteAll()
        val afterDelete = dao.getTopHabitsForHour(7, limit = 10)
        assertTrue(afterDelete.isEmpty())
    }
}
