package com.agentdesk.core.device

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.agentdesk.core.common.model.DeviceTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real-time battery status model.
 */
data class BatteryState(
    val percent: Int = 0,
    val isCharging: Boolean = false
)

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

    open fun getBatteryPercent(): Int {
        val intent = try {
            context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else 0
    }

    open fun isCharging(): Boolean {
        val intent = try {
            context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    open fun isBatteryLow(): Boolean = getBatteryPercent() < 20 && !isCharging()

    open fun getThermalStatus(): Int {
        val powerManager = try {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        } catch (_: Exception) {
            null
        }
        return powerManager?.currentThermalStatus ?: 0
    }

    /**
     * Cold Flow emitting reactive event-driven updates of thermal status.
     * Registers a listener on Android 10+ (API 29+) with defensive null checks.
     */
    open fun observeThermalStatus(): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.callbackFlow {
        trySend(getThermalStatus())

        val powerManager = try {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        } catch (_: Exception) {
            null
        }

        if (powerManager == null) {
            awaitClose { }
            return@callbackFlow
        }

        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            trySend(status)
        }

        try {
            powerManager.addThermalStatusListener(listener)
        } catch (_: Exception) { }

        awaitClose {
            try {
                powerManager.removeThermalStatusListener(listener)
            } catch (_: Exception) { }
        }
    }

    /**
     * Cold Flow emitting reactive event-driven updates of battery level and charging state.
     * Registers a BroadcastReceiver for Intent.ACTION_BATTERY_CHANGED.
     */
    open fun observeBatteryState(): kotlinx.coroutines.flow.Flow<BatteryState> = kotlinx.coroutines.flow.callbackFlow {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 0
                val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == android.os.BatteryManager.BATTERY_STATUS_FULL
                trySend(BatteryState(percent = percent, isCharging = isCharging))
            }
        }

        val intentFilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        val sticky = try {
            context.registerReceiver(receiver, intentFilter)
        } catch (_: Exception) {
            null
        }

        if (sticky != null) {
            val level = sticky.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 0
            val status = sticky.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
            trySend(BatteryState(percent = percent, isCharging = isCharging))
        } else {
            trySend(BatteryState(percent = getBatteryPercent(), isCharging = isCharging()))
        }

        awaitClose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) { }
        }
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
    open fun buildSnapshot(): com.agentdesk.core.persistence.entity.DeviceCapabilitySnapshotEntity {
        val totalRam = try { getTotalRamMb() } catch (_: Exception) { 4096L }
        val availRam = try { getAvailableRamMb() } catch (_: Exception) { 2048L }
        val availStorage = try { getAvailableStorageMb() } catch (_: Exception) { 10000L }
        val totalStorage = try { getTotalStorageMb() } catch (_: Exception) { 64000L }
        val cores = try { getCpuCores() } catch (_: Exception) { 4 }
        val tier = try { classifyTier() } catch (_: Exception) { DeviceTier.FULL }
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
