package com.agentdesk.tools.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [buildDeviceHealthSummary] — the device_health summary format:
 * "Storage <used>% used (<free> GB free), battery <pct>%[ charging], tier <TIER>".
 */
class HealthSummaryTest {

    @Test
    fun `summary formats storage percentage and free gigabytes`() {
        val summary = buildDeviceHealthSummary(
            freeStorageMb = 1_536L,   // 1.5 GB
            totalStorageMb = 10_240L, // 10 GB
            batteryPercent = 78,
            isCharging = true,
            deviceTier = "BALANCED"
        )
        // used = (10240 - 1536) * 100 / 10240 = 85%
        assertEquals(
            "Storage 85% used (1.5 GB free), battery 78% charging, tier BALANCED",
            summary
        )
    }

    @Test
    fun `summary omits charging when battery is discharging`() {
        val summary = buildDeviceHealthSummary(
            freeStorageMb = 1_536L,
            totalStorageMb = 10_240L,
            batteryPercent = 78,
            isCharging = false,
            deviceTier = "BALANCED"
        )
        assertTrue("Expected 'battery 78%' without charging, got: $summary", summary.contains("battery 78%,"))
        assertTrue(!summary.contains("charging"))
    }

    @Test
    fun `null tier falls back to UNKNOWN`() {
        val summary = buildDeviceHealthSummary(1_536L, 10_240L, 50, false, null)
        assertTrue("Expected 'tier UNKNOWN', got: $summary", summary.endsWith("tier UNKNOWN"))
    }

    @Test
    fun `zero total storage reports zero percent used`() {
        val summary = buildDeviceHealthSummary(0L, 0L, 100, false, "FULL")
        assertTrue(
            "Expected 'Storage 0% used', got: $summary",
            summary.startsWith("Storage 0% used")
        )
    }

    @Test
    fun `fully used storage reports hundred percent`() {
        val summary = buildDeviceHealthSummary(0L, 10_240L, 100, false, "FULL")
        assertTrue(summary.startsWith("Storage 100% used"))
    }

    @Test
    fun `free gigabytes format uses one decimal`() {
        val summary = buildDeviceHealthSummary(1_234L, 10_240L, 50, false, "LITE")
        // 1234 / 1024 = 1.205... -> "1.2 GB free"
        assertTrue(summary.contains("(1.2 GB free)"))
    }
}