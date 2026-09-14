package com.agentdesk.core.device

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.agentdesk.core.common.model.DeviceTier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profiles device hardware and classifies it into a [DeviceTier].
 *
 * Tier classification logic:
 * - EMERGENCY: available RAM < 256 MB OR available storage < 200 MB
 * - LITE: total RAM < 2 GB OR CPU cores <= 2
 * - BALANCED: total RAM < 4 GB OR CPU cores <= 4
 * - FULL: capable device
 *
 * Call [profileDevice] once on startup and cache the result in the DB via DeviceDao.
 */
@Singleton
class DeviceProfiler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val activityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    fun getTotalRamMb(): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    fun getAvailableRamMb(): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    fun isLowMemory(): Boolean {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.lowMemory
    }

    fun getCpuCores(): Int = Runtime.getRuntime().availableProcessors()

    fun getTotalStorageMb(): Long {
        val stat = StatFs(Environment.getDataDirectory().path)
        return (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024)
    }

    fun getAvailableStorageMb(): Long {
        val stat = StatFs(Environment.getDataDirectory().path)
        return (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
    }

    fun getThermalStatus(): Int {
        // currentThermalStatus is available since API 29 — the project minSdk —
        // so no SDK_INT guard is needed.
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.currentThermalStatus
    }

    fun classifyTier(): DeviceTier {
        val availRam = getAvailableRamMb()
        val totalRam = getTotalRamMb()
        val availStorage = getAvailableStorageMb()
        val cores = getCpuCores()

        return when {
            availRam < 256 || availStorage < 200 -> DeviceTier.EMERGENCY
            totalRam < 2048 || cores <= 2 -> DeviceTier.LITE
            totalRam < 4096 || cores <= 4 -> DeviceTier.BALANCED
            else -> DeviceTier.FULL
        }
    }

    /** Builds a DeviceCapabilitySnapshotEntity representing the current device state. */
    fun buildSnapshot(): com.agentdesk.core.persistence.entity.DeviceCapabilitySnapshotEntity {
        return com.agentdesk.core.persistence.entity.DeviceCapabilitySnapshotEntity(
            totalRamMb = getTotalRamMb(),
            availableRamMb = getAvailableRamMb(),
            availableStorageMb = getAvailableStorageMb(),
            totalStorageMb = android.os.Environment.getDataDirectory().totalSpace / (1024 * 1024),
            cpuCores = getCpuCores(),
            deviceTier = classifyTier(),
            capturedAt = System.currentTimeMillis()
        )
    }
}
