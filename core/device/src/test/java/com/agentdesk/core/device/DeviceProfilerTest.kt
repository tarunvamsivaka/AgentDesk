package com.agentdesk.core.device

import com.agentdesk.core.common.model.DeviceTier
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfilerTest {

    // ─── Direct simulated tier classification ─────────────────────────────────

    @Test
    fun `emergency tier when available RAM is below 256MB`() {
        val tier = DeviceProfiler.classifyTier(
            availRamMb = 255L,
            totalRamMb = 8_192L,
            availStorageMb = 10_000L,
            cores = 8
        )
        assertEquals(DeviceTier.EMERGENCY, tier)
    }

    @Test
    fun `emergency tier when available storage is below 200MB`() {
        val tier = DeviceProfiler.classifyTier(
            availRamMb = 2_048L,
            totalRamMb = 8_192L,
            availStorageMb = 199L,
            cores = 8
        )
        assertEquals(DeviceTier.EMERGENCY, tier)
    }

    @Test
    fun `emergency tier when both available RAM and storage are critical`() {
        val tier = DeviceProfiler.classifyTier(
            availRamMb = 128L,
            totalRamMb = 2_048L,
            availStorageMb = 50L,
            cores = 2
        )
        assertEquals(DeviceTier.EMERGENCY, tier)
    }

    @Test
    fun `lite tier when total RAM is below 2GB`() {
        val tier = DeviceProfiler.classifyTier(
            availRamMb = 1_024L,
            totalRamMb = 2_047L,
            availStorageMb = 5_000L,
            cores = 8
        )
        assertEquals(DeviceTier.LITE, tier)
    }

    @Test
    fun `lite tier when CPU cores is 2 or fewer`() {
        val tier2Cores = DeviceProfiler.classifyTier(
            availRamMb = 2_048L,
            totalRamMb = 8_192L,
            availStorageMb = 10_000L,
            cores = 2
        )
        assertEquals(DeviceTier.LITE, tier2Cores)

        val tier1Core = DeviceProfiler.classifyTier(
            availRamMb = 2_048L,
            totalRamMb = 8_192L,
            availStorageMb = 10_000L,
            cores = 1
        )
        assertEquals(DeviceTier.LITE, tier1Core)
    }

    @Test
    fun `balanced tier when total RAM is between 2GB and 4GB`() {
        val tier = DeviceProfiler.classifyTier(
            availRamMb = 1_500L,
            totalRamMb = 3_072L,
            availStorageMb = 10_000L,
            cores = 8
        )
        assertEquals(DeviceTier.BALANCED, tier)
    }

    @Test
    fun `balanced tier when CPU cores is 4 or fewer and total RAM is sufficient`() {
        val tier4Cores = DeviceProfiler.classifyTier(
            availRamMb = 2_048L,
            totalRamMb = 8_192L,
            availStorageMb = 10_000L,
            cores = 4
        )
        assertEquals(DeviceTier.BALANCED, tier4Cores)

        val tier3Cores = DeviceProfiler.classifyTier(
            availRamMb = 2_048L,
            totalRamMb = 8_192L,
            availStorageMb = 10_000L,
            cores = 3
        )
        assertEquals(DeviceTier.BALANCED, tier3Cores)
    }

    @Test
    fun `full tier when RAM, storage, and CPU cores meet high capability thresholds`() {
        val tier = DeviceProfiler.classifyTier(
            availRamMb = 4_096L,
            totalRamMb = 8_192L,
            availStorageMb = 64_000L,
            cores = 8
        )
        assertEquals(DeviceTier.FULL, tier)
    }

    // ─── Exact Boundary Conditions ────────────────────────────────────────────

    @Test
    fun `boundary check at emergency thresholds`() {
        // Exactly at 256MB RAM and 200MB storage -> promoted out of EMERGENCY
        val tier = DeviceProfiler.classifyTier(
            availRamMb = 256L,
            totalRamMb = 8_192L,
            availStorageMb = 200L,
            cores = 8
        )
        assertEquals(DeviceTier.FULL, tier)
    }

    @Test
    fun `boundary check at lite total RAM threshold`() {
        // Exactly 2048MB RAM with 8 cores -> promoted out of LITE into BALANCED
        val tier = DeviceProfiler.classifyTier(
            availRamMb = 1_024L,
            totalRamMb = 2_048L,
            availStorageMb = 10_000L,
            cores = 8
        )
        assertEquals(DeviceTier.BALANCED, tier)
    }

    @Test
    fun `boundary check at balanced total RAM threshold`() {
        // Exactly 4096MB RAM with 8 cores -> promoted out of BALANCED into FULL
        val tier = DeviceProfiler.classifyTier(
            availRamMb = 2_048L,
            totalRamMb = 4_096L,
            availStorageMb = 10_000L,
            cores = 8
        )
        assertEquals(DeviceTier.FULL, tier)
    }

    // ─── Instance Method Verification via Simulated Profiler ──────────────────

    private class SimulatedDeviceProfiler(
        private val simAvailRam: Long,
        private val simTotalRam: Long,
        private val simAvailStorage: Long,
        private val simCores: Int
    ) : DeviceProfiler() {
        override fun getAvailableRamMb(): Long = simAvailRam
        override fun getTotalRamMb(): Long = simTotalRam
        override fun getAvailableStorageMb(): Long = simAvailStorage
        override fun getCpuCores(): Int = simCores
        override fun classifyTier(): DeviceTier =
            classifyTier(simAvailRam, simTotalRam, simAvailStorage, simCores)
    }

    @Test
    fun `instance method classifyTier uses simulated parameters correctly`() {
        val emergencyProfiler = SimulatedDeviceProfiler(
            simAvailRam = 100L,
            simTotalRam = 4_096L,
            simAvailStorage = 1_000L,
            simCores = 8
        )
        assertEquals(DeviceTier.EMERGENCY, emergencyProfiler.classifyTier())

        val fullProfiler = SimulatedDeviceProfiler(
            simAvailRam = 3_000L,
            simTotalRam = 6_000L,
            simAvailStorage = 20_000L,
            simCores = 8
        )
        assertEquals(DeviceTier.FULL, fullProfiler.classifyTier())
    }
}
