package com.agentdesk.feature.health

import android.content.ContextWrapper
import com.agentdesk.core.common.model.DeviceTier
import com.agentdesk.core.device.BatteryState
import com.agentdesk.core.device.DeviceProfiler
import com.agentdesk.core.persistence.dao.DeviceDao
import com.agentdesk.core.persistence.entity.DeviceCapabilitySnapshotEntity
import com.agentdesk.core.persistence.entity.DeviceHealthSnapshotEntity
import com.agentdesk.core.persistence.entity.ResourceEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical stress test harness for HealthViewModel under high-frequency telemetry load,
 * rapid battery fluctuations, charging toggles, and thermal state changes.
 */
class HealthTelemetryStressTest {

    private class StressDeviceProfiler(
        var availableStorage: Long = 8192L,
        var totalStorage: Long = 65536L,
        var tier: DeviceTier = DeviceTier.FULL
    ) : DeviceProfiler() {
        val thermalFlow = MutableStateFlow(0)
        val batteryFlow = MutableStateFlow(BatteryState(percent = 100, isCharging = false))

        override fun getAvailableStorageMb(): Long = availableStorage
        override fun getTotalStorageMb(): Long = totalStorage
        override fun getThermalStatus(): Int = thermalFlow.value
        override fun getBatteryPercent(): Int = batteryFlow.value.percent
        override fun isCharging(): Boolean = batteryFlow.value.isCharging
        override fun observeThermalStatus(): Flow<Int> = thermalFlow
        override fun observeBatteryState(): Flow<BatteryState> = batteryFlow
        override fun classifyTier(): DeviceTier = tier
    }

    private class DummyDeviceDao(
        var snapshot: DeviceCapabilitySnapshotEntity? = null
    ) : DeviceDao {
        override suspend fun insertCapabilitySnapshot(snapshot: DeviceCapabilitySnapshotEntity): Long = 1L
        override suspend fun latestCapabilitySnapshot(): DeviceCapabilitySnapshotEntity? = snapshot
        override suspend fun insertHealthSnapshot(snapshot: DeviceHealthSnapshotEntity): Long = 1L
        override fun observeHealthSnapshots(limit: Int): Flow<List<DeviceHealthSnapshotEntity>> = emptyFlow()
        override suspend fun latestHealthSnapshot(): DeviceHealthSnapshotEntity? = null
        override suspend fun insertResourceEvent(event: ResourceEventEntity): Long = 1L
        override fun observeResourceEvents(limit: Int): Flow<List<ResourceEventEntity>> = emptyFlow()
        override suspend fun pruneHealthSnapshots(beforeEpoch: Long) {}
    }

    @Test
    fun `stress HealthViewModel tracks rapid battery drop 25 to 20 to 19 to 15 to charging`() = runBlocking {
        val profiler = StressDeviceProfiler()
        val dao = DummyDeviceDao()
        val testScope = CoroutineScope(Dispatchers.Default)

        val viewModel = HealthViewModel(
            deviceProfiler = profiler,
            deviceDao = dao,
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.Default,
            coroutineScope = testScope
        )

        viewModel.refresh().join()

        // Sequence: 25% discharging -> 20% discharging -> 19% discharging -> 15% discharging -> 15% charging
        val batteryStates = listOf(
            BatteryState(percent = 25, isCharging = false),
            BatteryState(percent = 20, isCharging = false),
            BatteryState(percent = 19, isCharging = false),
            BatteryState(percent = 15, isCharging = false),
            BatteryState(percent = 15, isCharging = true),
            BatteryState(percent = 10, isCharging = true),
            BatteryState(percent = 0, isCharging = true),
            BatteryState(percent = 0, isCharging = false)
        )

        for (state in batteryStates) {
            profiler.batteryFlow.value = state
            delay(10)
            assertEquals("UI battery percent must match emitted state", state.percent, viewModel.uiState.value.batteryPercent)
            assertEquals("UI charging state must match emitted state", state.isCharging, viewModel.uiState.value.isCharging)
        }
    }

    @Test
    fun `stress HealthViewModel tracks rapid thermal status fluctuations`() = runBlocking {
        val profiler = StressDeviceProfiler()
        val dao = DummyDeviceDao()
        val testScope = CoroutineScope(Dispatchers.Default)

        val viewModel = HealthViewModel(
            deviceProfiler = profiler,
            deviceDao = dao,
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.Default,
            coroutineScope = testScope
        )

        viewModel.refresh().join()

        // Heat up to critical and cooldown
        val thermalSequence = listOf(0, 1, 2, 3, 4, 5, 6, 4, 3, 2, 1, 0)
        for (temp in thermalSequence) {
            profiler.thermalFlow.value = temp
            delay(10)
            assertEquals("UI thermal status must match emitted temperature", temp, viewModel.uiState.value.thermalStatus)
        }
    }

    @Test
    fun `stress concurrent telemetry updates and user refreshes without corruption`() = runBlocking {
        val profiler = StressDeviceProfiler()
        val dao = DummyDeviceDao()
        val testScope = CoroutineScope(Dispatchers.Default)

        val viewModel = HealthViewModel(
            deviceProfiler = profiler,
            deviceDao = dao,
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.Default,
            coroutineScope = testScope
        )

        val refreshCompleted = AtomicInteger(0)

        // Launch 5 concurrent coroutines calling refresh
        val refreshJobs = (1..5).map {
            testScope.launch {
                repeat(20) {
                    viewModel.refresh().join()
                    refreshCompleted.incrementAndGet()
                }
            }
        }

        // Simultaneously stream 50 rapid battery and thermal updates
        val telemetryJob = testScope.launch {
            for (i in 1..50) {
                profiler.batteryFlow.value = BatteryState(percent = i % 100, isCharging = i % 2 == 0)
                profiler.thermalFlow.value = i % 5
                delay(2)
            }
            // Settle on known final state
            profiler.batteryFlow.value = BatteryState(percent = 95, isCharging = true)
            profiler.thermalFlow.value = 1
        }

        refreshJobs.forEach { it.join() }
        telemetryJob.join()

        delay(50)

        assertEquals(95, viewModel.uiState.value.batteryPercent)
        assertTrue(viewModel.uiState.value.isCharging)
        assertEquals(1, viewModel.uiState.value.thermalStatus)
        assertEquals(100, refreshCompleted.get())
    }
}
