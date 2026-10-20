package com.agentdesk.feature.health

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdesk.core.common.model.DeviceTier
import com.agentdesk.core.device.DeviceProfiler
import com.agentdesk.core.persistence.dao.DeviceDao
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Live device metrics for the Health screen. */
data class HealthUiState(
    val freeStorageMb: Long = 0,
    val totalStorageMb: Long = 0,
    val batteryPercent: Int = 0,
    val isCharging: Boolean = false,
    val thermalStatus: Int = 0,
    val deviceTier: DeviceTier? = null
)

/**
 * HealthViewModel — reads real device metrics via [DeviceProfiler] (StatFs
 * storage, PowerManager thermal status) and [BatteryManager] (battery level
 * and charging state). Tier comes from the latest
 * DeviceCapabilitySnapshotEntity persisted at onboarding, falling back to a
 * fresh classification when no snapshot exists.
 */
@HiltViewModel
class HealthViewModel(
    private val deviceProfiler: DeviceProfiler,
    private val deviceDao: DeviceDao,
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    coroutineScope: CoroutineScope? = null
) : ViewModel() {

    @Inject
    constructor(
        deviceProfiler: DeviceProfiler,
        deviceDao: DeviceDao,
        @ApplicationContext context: Context
    ) : this(deviceProfiler, deviceDao, context, Dispatchers.IO, null)

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    private val _uiState = MutableStateFlow(HealthUiState())
    val uiState: StateFlow<HealthUiState> = _uiState.asStateFlow()

    init {
        refresh()
        scope.launch {
            deviceProfiler.observeThermalStatus().collect { thermal ->
                _uiState.update { it.copy(thermalStatus = thermal) }
            }
        }
        scope.launch {
            deviceProfiler.observeBatteryState().collect { battery ->
                _uiState.update { it.copy(batteryPercent = battery.percent, isCharging = battery.isCharging) }
            }
        }
    }

    /** Re-reads all metrics. Safe to call repeatedly (e.g. on screen entry). */
    fun refresh(): Job {
        return scope.launch {
            val newState = withContext(ioDispatcher) {
                val tier = deviceDao.latestCapabilitySnapshot()?.deviceTier
                    ?: deviceProfiler.classifyTier()
                HealthUiState(
                    freeStorageMb = deviceProfiler.getAvailableStorageMb(),
                    totalStorageMb = deviceProfiler.getTotalStorageMb(),
                    batteryPercent = deviceProfiler.getBatteryPercent(),
                    isCharging = deviceProfiler.isCharging(),
                    thermalStatus = deviceProfiler.getThermalStatus(),
                    deviceTier = tier
                )
            }
            _uiState.value = newState
        }
    }
}