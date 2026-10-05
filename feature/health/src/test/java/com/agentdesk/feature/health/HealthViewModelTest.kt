package com.agentdesk.feature.health

import android.content.ContextWrapper
import com.agentdesk.core.common.model.DeviceTier
import com.agentdesk.core.device.DeviceProfiler
import com.agentdesk.core.persistence.dao.DeviceDao
import com.agentdesk.core.persistence.entity.DeviceCapabilitySnapshotEntity
import com.agentdesk.core.persistence.entity.DeviceHealthSnapshotEntity
import com.agentdesk.core.persistence.entity.ResourceEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HealthViewModelTest {

    private class FakeDeviceProfiler(
        var availableStorage: Long = 2048L,
        var totalStorage: Long = 16384L,
        var thermal: Int = 1,
        var tier: DeviceTier = DeviceTier.BALANCED
    ) : DeviceProfiler() {
        var classifyCalled = 0
        var storageCalled = 0

        override fun getAvailableStorageMb(): Long {
            storageCalled++
            return availableStorage
        }

        override fun getTotalStorageMb(): Long = totalStorage
        override fun getThermalStatus(): Int = thermal
        override fun classifyTier(): DeviceTier {
            classifyCalled++
            return tier
        }
    }

    private class FakeDeviceDao(
        var cachedSnapshot: DeviceCapabilitySnapshotEntity? = null
    ) : DeviceDao {
        val capabilitySnapshots = mutableListOf<DeviceCapabilitySnapshotEntity>()

        override suspend fun insertCapabilitySnapshot(snapshot: DeviceCapabilitySnapshotEntity): Long {
            capabilitySnapshots.add(snapshot)
            this.cachedSnapshot = snapshot
            return capabilitySnapshots.size.toLong()
        }

        override suspend fun latestCapabilitySnapshot(): DeviceCapabilitySnapshotEntity? = cachedSnapshot

        override suspend fun insertHealthSnapshot(snapshot: DeviceHealthSnapshotEntity): Long = 1L
        override fun observeHealthSnapshots(limit: Int): Flow<List<DeviceHealthSnapshotEntity>> = emptyFlow()
        override suspend fun latestHealthSnapshot(): DeviceHealthSnapshotEntity? = null
        override suspend fun insertResourceEvent(event: ResourceEventEntity): Long = 1L
        override fun observeResourceEvents(limit: Int): Flow<List<ResourceEventEntity>> = emptyFlow()
        override suspend fun pruneHealthSnapshots(beforeEpoch: Long) {}
    }

    @Test
    fun refreshPullsMetricsAndFallsBackToProfilerTierWhenNoSnapshot() = runBlocking {
        val profiler = FakeDeviceProfiler(
            availableStorage = 4096L,
            totalStorage = 32768L,
            thermal = 0,
            tier = DeviceTier.LITE
        )
        val dao = FakeDeviceDao(cachedSnapshot = null)
        val testScope = CoroutineScope(Dispatchers.Default)

        val viewModel = HealthViewModel(
            deviceProfiler = profiler,
            deviceDao = dao,
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.Default,
            coroutineScope = testScope
        )

        // Wait for the refresh launched in init
        val job = viewModel.refresh()
        job.join()

        val state = viewModel.uiState.value
        assertEquals(4096L, state.freeStorageMb)
        assertEquals(32768L, state.totalStorageMb)
        assertEquals(0, state.thermalStatus)
        assertEquals(DeviceTier.LITE, state.deviceTier)
        assertEquals(false, state.isCharging)
    }

    @Test
    fun refreshUsesCachedTierFromLatestCapabilitySnapshot() = runBlocking {
        val profiler = FakeDeviceProfiler(tier = DeviceTier.LITE)
        val snapshot = DeviceCapabilitySnapshotEntity(
            id = 1L,
            deviceTier = DeviceTier.FULL,
            totalRamMb = 12288L,
            availableRamMb = 6144L,
            cpuCores = 8,
            totalStorageMb = 128000L,
            availableStorageMb = 50000L,
            capturedAt = System.currentTimeMillis()
        )
        val dao = FakeDeviceDao(cachedSnapshot = snapshot)
        val testScope = CoroutineScope(Dispatchers.Default)

        val viewModel = HealthViewModel(
            deviceProfiler = profiler,
            deviceDao = dao,
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.Default,
            coroutineScope = testScope
        )

        val job = viewModel.refresh()
        job.join()

        val state = viewModel.uiState.value
        assertEquals(DeviceTier.FULL, state.deviceTier)
        assertEquals(0, profiler.classifyCalled)
    }

    @Test
    fun repeatedRefreshUpdatesDynamicMetrics() = runBlocking {
        val profiler = FakeDeviceProfiler(
            availableStorage = 1000L,
            totalStorage = 10000L,
            thermal = 1
        )
        val dao = FakeDeviceDao()
        val testScope = CoroutineScope(Dispatchers.Default)

        val viewModel = HealthViewModel(
            deviceProfiler = profiler,
            deviceDao = dao,
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.Default,
            coroutineScope = testScope
        )

        var job = viewModel.refresh()
        job.join()
        assertEquals(1000L, viewModel.uiState.value.freeStorageMb)

        // Update profiler storage dynamically
        profiler.availableStorage = 2500L
        profiler.thermal = 2

        job = viewModel.refresh()
        job.join()

        assertEquals(2500L, viewModel.uiState.value.freeStorageMb)
        assertEquals(2, viewModel.uiState.value.thermalStatus)
    }

    @Test
    fun initialUiStateDefaultsAreSafe() {
        val state = HealthUiState()
        assertEquals(0L, state.freeStorageMb)
        assertEquals(0L, state.totalStorageMb)
        assertEquals(0, state.batteryPercent)
        assertEquals(false, state.isCharging)
        assertEquals(0, state.thermalStatus)
        assertNull(state.deviceTier)
    }
}
