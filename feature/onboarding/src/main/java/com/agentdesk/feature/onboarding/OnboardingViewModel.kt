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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
class OnboardingViewModel @Inject constructor(
    private val consentDao: ConsentDao,
    private val deviceDao: DeviceDao,
    private val deviceProfiler: DeviceProfiler
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** User accepts privacy consent — persist record and move to Device Scan step. */
    fun acceptConsent() {
        viewModelScope.launch {
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
    private fun startDeviceScan() {
        viewModelScope.launch {
            for (i in 1..20) {
                delay(75L)
                _uiState.value = _uiState.value.copy(scanProgress = i / 20f)
            }
            val tier = deviceProfiler.classifyTier()
            try {
                val snapshot = deviceProfiler.buildSnapshot()
                deviceDao.insertSnapshot(snapshot)
            } catch (_: Exception) { /* Non-fatal */ }
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
