package com.agentdesk.agent.habit

import android.content.ContextWrapper
import androidx.work.ListenableWorker
import com.agentdesk.core.common.model.DeviceTier
import com.agentdesk.core.device.DeviceProfiler
import com.agentdesk.core.device.ResourceGovernor
import com.agentdesk.core.persistence.dao.UserHabitDao
import com.agentdesk.core.persistence.entity.UserHabitEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientHabitWorkerTest {

    private class FakeContext : ContextWrapper(null)

    private class SimulatedDeviceProfiler(
        private val lowBattery: Boolean = false,
        private val thermalHot: Boolean = false
    ) : DeviceProfiler() {
        override fun getBatteryPercent(): Int = if (lowBattery) 10 else 80
        override fun isCharging(): Boolean = false
        override fun getThermalStatus(): Int = if (thermalHot) 3 else 0
        override fun getAvailableRamMb(): Long = 2048L
        override fun isLowMemory(): Boolean = false
        override fun getAvailableStorageMb(): Long = 2000L
        override fun classifyTier(): DeviceTier = DeviceTier.FULL
    }

    private class FakeUserHabitDao : UserHabitDao {
        val habits = mutableListOf<UserHabitEntity>()

        override fun observeTopHabitsForHour(hourOfDay: Int, limit: Int): Flow<List<UserHabitEntity>> {
            return MutableStateFlow(habits.filter { it.hourOfDay == hourOfDay })
        }

        override suspend fun getTopHabitsForHour(hourOfDay: Int, limit: Int): List<UserHabitEntity> {
            return habits.filter { it.hourOfDay == hourOfDay }.sortedByDescending { it.interactionCount }.take(limit)
        }

        override suspend fun getHabit(packageName: String, hourOfDay: Int): UserHabitEntity? {
            return habits.firstOrNull { it.packageName == packageName && it.hourOfDay == hourOfDay }
        }

        override suspend fun getHabitForIntent(intent: String, hourOfDay: Int): UserHabitEntity? {
            return habits.firstOrNull { it.associatedIntent == intent && it.hourOfDay == hourOfDay }
        }

        override suspend fun insertOrUpdate(habit: UserHabitEntity): Long {
            val idx = habits.indexOfFirst { it.packageName == habit.packageName && it.hourOfDay == habit.hourOfDay }
            return if (idx >= 0) {
                habits[idx] = habit
                habit.id
            } else {
                val newHabit = if (habit.id == 0L) habit.copy(id = habits.size + 1L) else habit
                habits.add(newHabit)
                newHabit.id
            }
        }

        override suspend fun insertAll(habits: List<UserHabitEntity>) {
            this.habits.addAll(habits)
        }

        override suspend fun deleteAll() {
            habits.clear()
        }
    }

    @Test
    fun `worker retries when resource governor denies indexing due to low battery`() = runBlocking {
        val throttledGov = ResourceGovernor(SimulatedDeviceProfiler(lowBattery = true))
        val habitDao = FakeUserHabitDao()
        val provider = object : UsageStatsProvider(FakeContext()) {
            override fun hasPermission(): Boolean = true
            override fun queryRecentForegroundEvents(durationMs: Long): List<ForegroundAppEvent> = listOf(
                ForegroundAppEvent("com.google.android.calendar", 8, 1000L)
            )
        }

        val result = AmbientHabitWorker.syncHabits(
            usageStatsProvider = provider,
            habitDao = habitDao,
            resourceGovernor = throttledGov
        )

        assertTrue(result is ListenableWorker.Result.Retry)
        assertTrue(habitDao.habits.isEmpty())
    }

    @Test
    fun `worker succeeds with zero updates when no foreground events exist`() = runBlocking {
        val healthyGov = ResourceGovernor(SimulatedDeviceProfiler(lowBattery = false, thermalHot = false))
        val habitDao = FakeUserHabitDao()
        val provider = object : UsageStatsProvider(FakeContext()) {
            override fun hasPermission(): Boolean = true
            override fun queryRecentForegroundEvents(durationMs: Long): List<ForegroundAppEvent> = emptyList()
        }

        val result = AmbientHabitWorker.syncHabits(
            usageStatsProvider = provider,
            habitDao = habitDao,
            resourceGovernor = healthyGov
        )

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(habitDao.habits.isEmpty())
    }

    @Test
    fun `worker aggregates events into habits and maps associated intents`() = runBlocking {
        val healthyGov = ResourceGovernor(SimulatedDeviceProfiler(lowBattery = false, thermalHot = false))
        val habitDao = FakeUserHabitDao()
        val provider = object : UsageStatsProvider(FakeContext()) {
            override fun hasPermission(): Boolean = true
            override fun queryRecentForegroundEvents(durationMs: Long): List<ForegroundAppEvent> = listOf(
                ForegroundAppEvent("com.google.android.calendar", 8, 1000L),
                ForegroundAppEvent("com.google.android.calendar", 8, 2000L),
                ForegroundAppEvent("com.google.android.deskclock", 22, 3000L),
                ForegroundAppEvent("com.google.android.apps.maps", 14, 4000L),
                ForegroundAppEvent("com.example.calculator", 12, 5000L)
            )
        }

        val result = AmbientHabitWorker.syncHabits(
            usageStatsProvider = provider,
            habitDao = habitDao,
            resourceGovernor = healthyGov
        )

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(4, habitDao.habits.size)

        val calendarHabit = habitDao.getHabit("com.google.android.calendar", 8)
        assertNotNull(calendarHabit)
        assertEquals(2, calendarHabit?.interactionCount)
        assertEquals("calendar.create_event", calendarHabit?.associatedIntent)

        val clockHabit = habitDao.getHabit("com.google.android.deskclock", 22)
        assertNotNull(clockHabit)
        assertEquals(1, clockHabit?.interactionCount)
        assertEquals("system.set_alarm", clockHabit?.associatedIntent)

        val mapsHabit = habitDao.getHabit("com.google.android.apps.maps", 14)
        assertNotNull(mapsHabit)
        assertEquals(1, mapsHabit?.interactionCount)
        assertEquals("maps.navigate", mapsHabit?.associatedIntent)

        val calcHabit = habitDao.getHabit("com.example.calculator", 12)
        assertNotNull(calcHabit)
        assertEquals(1, calcHabit?.interactionCount)
        assertNull(calcHabit?.associatedIntent)
    }

    @Test
    fun `worker increments interaction count for existing habit records`() = runBlocking {
        val healthyGov = ResourceGovernor(SimulatedDeviceProfiler(lowBattery = false, thermalHot = false))
        val habitDao = FakeUserHabitDao()
        habitDao.insertOrUpdate(
            UserHabitEntity(
                id = 1L,
                packageName = "com.google.android.calendar",
                appName = "Calendar",
                hourOfDay = 8,
                interactionCount = 5,
                associatedIntent = "calendar.create_event"
            )
        )

        val provider = object : UsageStatsProvider(FakeContext()) {
            override fun hasPermission(): Boolean = true
            override fun queryRecentForegroundEvents(durationMs: Long): List<ForegroundAppEvent> = listOf(
                ForegroundAppEvent("com.google.android.calendar", 8, 1000L),
                ForegroundAppEvent("com.google.android.calendar", 8, 2000L)
            )
        }

        val result = AmbientHabitWorker.syncHabits(
            usageStatsProvider = provider,
            habitDao = habitDao,
            resourceGovernor = healthyGov
        )

        assertTrue(result is ListenableWorker.Result.Success)

        val updated = habitDao.getHabit("com.google.android.calendar", 8)
        assertNotNull(updated)
        assertEquals(7, updated?.interactionCount)
    }

    @Test
    fun `buildWorkRequest configures idle and charging constraints`() {
        val workRequest = AmbientHabitWorker.buildWorkRequest()
        val constraints = workRequest.workSpec.constraints

        assertTrue(constraints.requiresCharging())
        assertTrue(constraints.requiresBatteryNotLow())
        assertTrue(constraints.requiresStorageNotLow())
        assertTrue(workRequest.tags.contains(AmbientHabitWorker.TAG))
    }
}
