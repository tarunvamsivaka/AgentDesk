package com.agentdesk.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages app lock state using device biometrics or PIN fallback.
 *
 * MVP: PIN is stored in memory only (not persisted). Full keystore-backed
 * encryption with DataStore-persisted PIN hash is deferred to post-MVP.
 *
 * SAFETY: isLockEnabled() returns false by default (MVP: lock is opt-in).
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _lockState = MutableStateFlow(AppLockState.UNLOCKED)
    val lockState: StateFlow<AppLockState> = _lockState.asStateFlow()

    // MVP: Lock is disabled by default. Will be backed by DataStore in post-MVP.
    private var appLockEnabled: Boolean = false

    // MVP: PIN stored in memory only. Post-MVP: store hashed PIN in EncryptedSharedPreferences.
    private var pinHash: Int? = null

    fun isLockEnabled(): Boolean = appLockEnabled

    fun enableLock(pin: String) {
        pinHash = pin.hashCode()
        appLockEnabled = true
        _lockState.value = AppLockState.LOCKED
    }

    fun disableLock() {
        pinHash = null
        appLockEnabled = false
        _lockState.value = AppLockState.UNLOCKED
    }

    fun lock() { _lockState.value = AppLockState.LOCKED }
    fun unlock() { _lockState.value = AppLockState.UNLOCKED }
    fun isLocked(): Boolean = _lockState.value == AppLockState.LOCKED

    fun verifyPin(pin: String): Boolean {
        return if (pinHash != null && pin.hashCode() == pinHash) {
            unlock()
            true
        } else false
    }

    fun isBiometricAvailable(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
                    or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Launches biometric authentication.
     * Must be called from a FragmentActivity context.
     */
    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock()
                    onSuccess()
                }
                override fun onAuthenticationFailed() = onFailure()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock AgentDesk")
            .setSubtitle("Confirm your identity to continue")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                        or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }
}

enum class AppLockState { LOCKED, UNLOCKED }
