package com.agentdesk.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdesk.core.common.model.ConsentState
import com.agentdesk.core.common.model.DeviceTier
import com.agentdesk.core.device.DeviceProfiler
import com.agentdesk.core.persistence.dao.ConsentDao
import com.agentdesk.core.persistence.dao.DeviceDao
import com.agentdesk.core.persistence.entity.ConsentRecordEntity
import com.agentdesk.core.settings.ConsentKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class OnboardingStep { PRIVACY_CONSENT, DEVICE_SCAN, TIER_DISPLAY, DONE }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.PRIVACY_CONSENT,
    val scanProgress: Float = 0f,
    val deviceTier: DeviceTier? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class OnboardingViewModel(
    private val consentDao: ConsentDao,
    private val deviceDao: DeviceDao,
    private val deviceProfiler: DeviceProfiler,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    coroutineScope: CoroutineScope? = null,
    private val scanDelayMs: Long = 75L
) : ViewModel() {

    @Inject
    constructor(
        consentDao: ConsentDao,
        deviceDao: DeviceDao,
        deviceProfiler: DeviceProfiler
    ) : this(consentDao, deviceDao, deviceProfiler, Dispatchers.IO, null, 75L)

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** User accepts privacy consent — persist record and move to Device Scan step. */
    fun acceptConsent(): Job {
        return scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            consentDao.upsert(
                ConsentRecordEntity(
                    consentKey = ConsentKeys.AUDIT_LOG,
                    state = ConsentState.GRANTED,
                    grantedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                step = OnboardingStep.DEVICE_SCAN
            )
            startDeviceScan()
        }
    }

    /** Animates a progress indicator while classifying the device tier and persisting the snapshot. */
    internal fun startDeviceScan(): Job {
        return scope.launch {
            for (i in 1..20) {
                if (scanDelayMs > 0) delay(scanDelayMs)
                _uiState.value = _uiState.value.copy(scanProgress = i / 20f)
            }
            val tier = withContext(ioDispatcher) {
                val snapshot = try {
                    deviceProfiler.buildSnapshot().also { deviceDao.insertSnapshot(it) }
                } catch (_: Exception) { null }
                snapshot?.deviceTier ?: deviceProfiler.classifyTier()
            }
            _uiState.value = _uiState.value.copy(
                deviceTier = tier,
                step = OnboardingStep.TIER_DISPLAY
            )
        }
    }

    /** User acknowledges their device tier — signal navigation to home. */
    fun confirmTierAndProceed() {
        _uiState.value = _uiState.value.copy(step = OnboardingStep.DONE)
    }
}
