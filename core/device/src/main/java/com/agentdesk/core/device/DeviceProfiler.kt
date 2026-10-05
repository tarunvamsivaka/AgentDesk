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
open class DeviceProfiler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // Secondary constructor for testing/subclassing without Android system services
    constructor() : this(android.content.ContextWrapper(null))

    private val activityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo =
        ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }

    private fun statFs(): StatFs = StatFs(Environment.getDataDirectory().path)

    open fun getTotalRamMb(): Long = memoryInfo().totalMem / (1024 * 1024)

    open fun getAvailableRamMb(): Long = memoryInfo().availMem / (1024 * 1024)

    open fun isLowMemory(): Boolean = memoryInfo().lowMemory

    open fun getCpuCores(): Int = Runtime.getRuntime().availableProcessors()

    open fun getTotalStorageMb(): Long = statFs().let { (it.blockCountLong * it.blockSizeLong) / (1024 * 1024) }

    open fun getAvailableStorageMb(): Long = statFs().let { (it.availableBlocksLong * it.blockSizeLong) / (1024 * 1024) }

    open fun getThermalStatus(): Int {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.currentThermalStatus
    }

    open fun classifyTier(): DeviceTier {
        val mem = memoryInfo()
        val availRam = mem.availMem / (1024 * 1024)
        val totalRam = mem.totalMem / (1024 * 1024)
        val availStorage = getAvailableStorageMb()
        val cores = getCpuCores()

        return classifyTier(availRam, totalRam, availStorage, cores)
    }

    fun classifyTier(
        availRamMb: Long,
        totalRamMb: Long,
        availStorageMb: Long,
        cores: Int
    ): DeviceTier = Companion.classifyTier(availRamMb, totalRamMb, availStorageMb, cores)

    /** Builds a DeviceCapabilitySnapshotEntity representing the current device state. */
    fun buildSnapshot(): com.agentdesk.core.persistence.entity.DeviceCapabilitySnapshotEntity {
        val mem = memoryInfo()
        val totalRam = mem.totalMem / (1024 * 1024)
        val availRam = mem.availMem / (1024 * 1024)
        val stat = statFs()
        val blockSize = stat.blockSizeLong
        val availStorage = (stat.availableBlocksLong * blockSize) / (1024 * 1024)
        val totalStorage = (stat.blockCountLong * blockSize) / (1024 * 1024)
        val cores = getCpuCores()
        val tier = classifyTier(availRam, totalRam, availStorage, cores)
        return com.agentdesk.core.persistence.entity.DeviceCapabilitySnapshotEntity(
            totalRamMb = totalRam,
            availableRamMb = availRam,
            availableStorageMb = availStorage,
            totalStorageMb = totalStorage,
            cpuCores = cores,
            deviceTier = tier,
            capturedAt = System.currentTimeMillis()
        )
    }

    companion object {
        fun classifyTier(
            availRamMb: Long,
            totalRamMb: Long,
            availStorageMb: Long,
            cores: Int
        ): DeviceTier {
            return when {
                availRamMb < 256 || availStorageMb < 200 -> DeviceTier.EMERGENCY
                totalRamMb < 2048 || cores <= 2 -> DeviceTier.LITE
                totalRamMb < 4096 || cores <= 4 -> DeviceTier.BALANCED
                else -> DeviceTier.FULL
            }
        }
    }
}
