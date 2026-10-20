package com.agentdesk.core.device

import com.agentdesk.core.common.model.DeviceTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ResourceGovernorTest {

    private class TestDeviceProfiler : DeviceProfiler() {
        var simLowMemory: Boolean = false
        var simAvailableStorageMb: Long = 1_000L
        var simAvailableRamMb: Long = 2_048L
        var simThermalStatus: Int = 0
        var simTier: DeviceTier = DeviceTier.FULL
        var simBatteryPercent: Int = 100
        var simIsCharging: Boolean = false

        override fun isLowMemory(): Boolean = simLowMemory
        override fun getAvailableStorageMb(): Long = simAvailableStorageMb
        override fun getAvailableRamMb(): Long = simAvailableRamMb
        override fun getThermalStatus(): Int = simThermalStatus
        override fun classifyTier(): DeviceTier = simTier
        override fun getBatteryPercent(): Int = simBatteryPercent
        override fun isCharging(): Boolean = simIsCharging
        override fun observeThermalStatus(): Flow<Int> = flowOf(simThermalStatus)
        override fun observeBatteryState(): Flow<BatteryState> = flowOf(BatteryState(simBatteryPercent, simIsCharging))
    }

    private lateinit var profiler: TestDeviceProfiler
    private lateinit var governor: ResourceGovernor

    @Before
    fun setUp() {
        profiler = TestDeviceProfiler()
        governor = ResourceGovernor(profiler)
    }

    // ─── canIndex() Tests ─────────────────────────────────────────────────────

    @Test
    fun `canIndex returns true when system resources are healthy`() {
        assertTrue(governor.canIndex())
    }

    @Test
    fun `canIndex returns false when system reports low memory`() {
        profiler.simLowMemory = true
        assertFalse(governor.canIndex())
    }

    @Test
    fun `canIndex returns false when storage is below 500MB threshold`() {
        profiler.simAvailableStorageMb = 499L
        assertFalse(governor.canIndex())
    }

    @Test
    fun `canIndex returns true when storage is exactly at 500MB boundary`() {
        profiler.simAvailableStorageMb = 500L
        assertTrue(governor.canIndex())
    }

    @Test
    fun `canIndex returns false when thermal status is severe (status 3 or greater)`() {
        profiler.simThermalStatus = 3
        assertFalse(governor.canIndex())

        profiler.simThermalStatus = 4
        assertFalse(governor.canIndex())
    }

    @Test
    fun `canIndex returns true when thermal status is moderate (status 2 or lower)`() {
        profiler.simThermalStatus = 2
        assertTrue(governor.canIndex())

        profiler.simThermalStatus = 1
        assertTrue(governor.canIndex())

        profiler.simThermalStatus = 0
        assertTrue(governor.canIndex())
    }

    // ─── canRunLlm() Tests ────────────────────────────────────────────────────

    @Test
    fun `canRunLlm returns false on EMERGENCY tier regardless of other metrics`() {
        profiler.simTier = DeviceTier.EMERGENCY
        profiler.simAvailableRamMb = 4_096L
        profiler.simThermalStatus = 0
        assertFalse(governor.canRunLlm())
    }

    @Test
    fun `canRunLlm returns false on LITE tier`() {
        profiler.simTier = DeviceTier.LITE
        profiler.simAvailableRamMb = 2_048L
        profiler.simThermalStatus = 0
        assertFalse(governor.canRunLlm())
    }

    @Test
    fun `canRunLlm returns false when available RAM is below 1024MB on capable tier`() {
        profiler.simTier = DeviceTier.FULL
        profiler.simAvailableRamMb = 1_023L
        assertFalse(governor.canRunLlm())

        profiler.simTier = DeviceTier.BALANCED
        profiler.simAvailableRamMb = 512L
        assertFalse(governor.canRunLlm())
    }

    @Test
    fun `canRunLlm returns true when available RAM is at 1024MB boundary on capable tier`() {
        profiler.simTier = DeviceTier.BALANCED
        profiler.simAvailableRamMb = 1_024L
        profiler.simThermalStatus = 0
        assertTrue(governor.canRunLlm())

        profiler.simTier = DeviceTier.FULL
        profiler.simAvailableRamMb = 1_024L
        profiler.simThermalStatus = 0
        assertTrue(governor.canRunLlm())
    }

    @Test
    fun `canRunLlm returns false when thermal status is 3 or higher on capable tier`() {
        profiler.simTier = DeviceTier.FULL
        profiler.simAvailableRamMb = 4_096L
        profiler.simThermalStatus = 3
        assertFalse(governor.canRunLlm())
    }

    @Test
    fun `canRunLlm returns true under optimal conditions on FULL tier`() {
        profiler.simTier = DeviceTier.FULL
        profiler.simAvailableRamMb = 2_048L
        profiler.simThermalStatus = 0
        assertTrue(governor.canRunLlm())
    }

    // ─── canUseRichUi() Tests ─────────────────────────────────────────────────

    @Test
    fun `canUseRichUi is disabled only on EMERGENCY tier`() {
        profiler.simTier = DeviceTier.EMERGENCY
        assertFalse(governor.canUseRichUi())

        profiler.simTier = DeviceTier.LITE
        assertTrue(governor.canUseRichUi())

        profiler.simTier = DeviceTier.BALANCED
        assertTrue(governor.canUseRichUi())

        profiler.simTier = DeviceTier.FULL
        assertTrue(governor.canUseRichUi())
    }

    // ─── currentConstraintReason() Tests ──────────────────────────────────────

    @Test
    fun `currentConstraintReason returns null when healthy`() {
        assertNull(governor.currentConstraintReason())
    }

    @Test
    fun `currentConstraintReason describes low memory`() {
        profiler.simLowMemory = true
        assertEquals("Device is low on memory.", governor.currentConstraintReason())
    }

    @Test
    fun `currentConstraintReason describes storage deficit`() {
        profiler.simAvailableStorageMb = 350L
        assertEquals("Available storage is below 500MB.", governor.currentConstraintReason())
    }

    @Test
    fun `currentConstraintReason describes thermal throttling`() {
        profiler.simThermalStatus = 3
        assertEquals("Device is too hot. Pausing heavy operations.", governor.currentConstraintReason())
    }

    @Test
    fun `currentConstraintReason describes battery constraint when below 20 percent and discharging`() {
        profiler.simBatteryPercent = 18
        profiler.simIsCharging = false
        assertEquals("Battery level is below 20%.", governor.currentConstraintReason())
    }

    // ─── Battery Floor Tests ──────────────────────────────────────────────────

    @Test
    fun `canIndex returns false when battery is below 20 percent and discharging`() {
        profiler.simBatteryPercent = 19
        profiler.simIsCharging = false
        assertFalse(governor.canIndex())
    }

    @Test
    fun `canIndex returns true when battery is below 20 percent but device is charging`() {
        profiler.simBatteryPercent = 15
        profiler.simIsCharging = true
        assertTrue(governor.canIndex())
    }

    @Test
    fun `canIndex returns true when battery is exactly at 20 percent boundary`() {
        profiler.simBatteryPercent = 20
        profiler.simIsCharging = false
        assertTrue(governor.canIndex())
    }

    @Test
    fun `canRunLlm returns false when battery is below 20 percent and discharging`() {
        profiler.simBatteryPercent = 19
        profiler.simIsCharging = false
        assertFalse(governor.canRunLlm())
    }

    @Test
    fun `canRunLlm returns true when battery is below 20 percent but device is charging`() {
        profiler.simBatteryPercent = 10
        profiler.simIsCharging = true
        assertTrue(governor.canRunLlm())
    }

    @Test
    fun `canRunLlm returns true when battery is exactly at 20 percent boundary`() {
        profiler.simBatteryPercent = 20
        profiler.simIsCharging = false
        assertTrue(governor.canRunLlm())
    }

    // ─── Reactive Flow Tests ──────────────────────────────────────────────────

    @Test
    fun `observeCanIndex emits true when healthy and false when constrained`() = runBlocking {
        assertTrue(governor.observeCanIndex().first())

        profiler.simBatteryPercent = 10
        profiler.simIsCharging = false
        assertFalse(governor.observeCanIndex().first())
    }

    @Test
    fun `observeCanRunLlm emits true when healthy and false when constrained`() = runBlocking {
        assertTrue(governor.observeCanRunLlm().first())

        profiler.simThermalStatus = 3
        assertFalse(governor.observeCanRunLlm().first())
    }
}
