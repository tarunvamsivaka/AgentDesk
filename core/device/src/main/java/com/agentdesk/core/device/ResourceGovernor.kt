package com.agentdesk.core.device

import com.agentdesk.core.common.model.DeviceTier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ResourceGovernor decides whether heavy operations are permitted based on:
 * - Current device tier
 * - Available RAM and storage
 * - Thermal status
 * - Feature flag overrides
 *
 * All heavy operations (indexing, embedding, LLM inference) MUST check this governor
 * before starting. The governor never crashes -- it defaults to safe denial.
 */
@Singleton
class ResourceGovernor @Inject constructor(
    private val profiler: DeviceProfiler
) {

    /**
     * Returns true if background indexing is currently permitted.
     * Indexing pauses under low storage, low RAM, high thermal, or low battery (< 20% unless charging).
     */
    fun canIndex(): Boolean {
        if (profiler.isLowMemory()) return false
        if (profiler.getAvailableStorageMb() < MIN_STORAGE_FOR_INDEX_MB) return false
        if (profiler.getThermalStatus() >= THERMAL_THRESHOLD_PAUSE) return false
        if (profiler.getBatteryPercent() < BATTERY_THRESHOLD_PAUSE && !profiler.isCharging()) return false
        return true
    }

    /**
     * Returns true if on-device LLM inference is currently permitted.
     * Only allowed on BALANCED or FULL tier devices with sufficient RAM, safe thermal, and battery.
     */
    fun canRunLlm(): Boolean {
        val tier = profiler.classifyTier()
        if (tier == DeviceTier.EMERGENCY || tier == DeviceTier.LITE) return false
        if (profiler.getAvailableRamMb() < MIN_RAM_FOR_LLM_MB) return false
        if (profiler.getThermalStatus() >= THERMAL_THRESHOLD_PAUSE) return false
        if (profiler.getBatteryPercent() < BATTERY_THRESHOLD_PAUSE && !profiler.isCharging()) return false
        return true
    }

    /**
     * Reactive flow emitting whether background indexing is currently permitted.
     */
    fun observeCanIndex(): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.combine(
        profiler.observeThermalStatus(),
        profiler.observeBatteryState()
    ) { thermal, battery ->
        !profiler.isLowMemory() &&
        profiler.getAvailableStorageMb() >= MIN_STORAGE_FOR_INDEX_MB &&
        thermal < THERMAL_THRESHOLD_PAUSE &&
        (battery.percent >= BATTERY_THRESHOLD_PAUSE || battery.isCharging)
    }

    /**
     * Reactive flow emitting whether LLM inference is currently permitted.
     */
    fun observeCanRunLlm(): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.combine(
        profiler.observeThermalStatus(),
        profiler.observeBatteryState()
    ) { thermal, battery ->
        val tier = profiler.classifyTier()
        tier != DeviceTier.EMERGENCY &&
        tier != DeviceTier.LITE &&
        profiler.getAvailableRamMb() >= MIN_RAM_FOR_LLM_MB &&
        thermal < THERMAL_THRESHOLD_PAUSE &&
        (battery.percent >= BATTERY_THRESHOLD_PAUSE || battery.isCharging)
    }

    /**
     * Returns true if heavy UI animations and rich media should be enabled.
     * Disabled on EMERGENCY tier to preserve resources.
     */
    fun canUseRichUi(): Boolean {
        return profiler.classifyTier() != DeviceTier.EMERGENCY
    }

    /**
     * Calculates the optimal batch size for vector indexing and embedding generation
     * based on device tier and memory pressure (4 to 16 items).
     */
    fun resolveOptimalBatchSize(): Int {
        val isLowMem = profiler.isLowMemory()
        val tier = profiler.classifyTier()
        return when {
            isLowMem -> 4
            tier == DeviceTier.EMERGENCY -> 4
            tier == DeviceTier.LITE -> 8
            tier == DeviceTier.BALANCED -> 12
            else -> 16
        }
    }

    /**
     * Returns a human-readable reason for the current resource constraint, or null if healthy.
     */
    fun currentConstraintReason(): String? {
        return when {
            profiler.isLowMemory() -> "Device is low on memory."
            profiler.getAvailableStorageMb() < MIN_STORAGE_FOR_INDEX_MB ->
                "Available storage is below ${MIN_STORAGE_FOR_INDEX_MB}MB."
            profiler.getThermalStatus() >= THERMAL_THRESHOLD_PAUSE ->
                "Device is too hot. Pausing heavy operations."
            profiler.getBatteryPercent() < BATTERY_THRESHOLD_PAUSE && !profiler.isCharging() ->
                "Battery level is below ${BATTERY_THRESHOLD_PAUSE}%."
            else -> null
        }
    }

    companion object {
        const val MIN_STORAGE_FOR_INDEX_MB = 500L
        const val MIN_RAM_FOR_LLM_MB = 1024L
        const val THERMAL_THRESHOLD_PAUSE = 3 // THERMAL_STATUS_SEVERE
        const val BATTERY_THRESHOLD_PAUSE = 20
    }
}
