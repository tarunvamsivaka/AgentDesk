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
     * Indexing pauses under low storage, low RAM, or high thermal.
     */
    fun canIndex(): Boolean {
        if (profiler.isLowMemory()) return false
        if (profiler.getAvailableStorageMb() < MIN_STORAGE_FOR_INDEX_MB) return false
        if (profiler.getThermalStatus() >= THERMAL_THRESHOLD_PAUSE) return false
        return true
    }

    /**
     * Returns true if on-device LLM inference is currently permitted.
     * Only allowed on BALANCED or FULL tier devices with sufficient RAM.
     */
    fun canRunLlm(): Boolean {
        val tier = profiler.classifyTier()
        if (tier == DeviceTier.EMERGENCY || tier == DeviceTier.LITE) return false
        if (profiler.getAvailableRamMb() < MIN_RAM_FOR_LLM_MB) return false
        if (profiler.getThermalStatus() >= THERMAL_THRESHOLD_PAUSE) return false
        return true
    }

    /**
     * Returns true if heavy UI animations and rich media should be enabled.
     * Disabled on EMERGENCY tier to preserve resources.
     */
    fun canUseRichUi(): Boolean {
        return profiler.classifyTier() != DeviceTier.EMERGENCY
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
            else -> null
        }
    }

    companion object {
        private const val MIN_STORAGE_FOR_INDEX_MB = 500L
        private const val MIN_RAM_FOR_LLM_MB = 1024L
        private const val THERMAL_THRESHOLD_PAUSE = 3 // THERMAL_STATUS_SEVERE
    }
}
