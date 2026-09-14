package com.agentdesk.core.security

import android.content.Context
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages app lock state using device biometrics or PIN fallback.
 *
 * SECURITY: The PIN is never stored or logged in raw form. On [enableLock] a
 * random 16-byte salt is generated and the PIN is hashed with
 * PBKDF2WithHmacSHA256 (10,000 iterations, 256-bit key). The salt and hash
 * are persisted (Base64) in a private DataStore. [verifyPin] recomputes the
 * hash and compares it in constant time via [MessageDigest.isEqual].
 *
 * SAFETY: isLockEnabled() is false until the user explicitly enables the lock.
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _lockState = MutableStateFlow(AppLockState.UNLOCKED)
    val lockState: StateFlow<AppLockState> = _lockState.asStateFlow()

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ) { context.preferencesDataStoreFile(APP_LOCK_STORE_FILE) }

    private var appLockEnabled: Boolean = runBlocking {
        readEnabledPref()
    }

    fun isLockEnabled(): Boolean = appLockEnabled

    suspend fun enableLock(pin: String) {
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        dataStore.edit { prefs ->
            prefs[SALT_KEY] = Base64.encodeToString(salt, Base64.NO_WRAP)
            prefs[HASH_KEY] = Base64.encodeToString(hash, Base64.NO_WRAP)
            prefs[ENABLED_KEY] = "true"
        }
        appLockEnabled = true
        _lockState.value = AppLockState.LOCKED
    }

    suspend fun disableLock() {
        dataStore.edit { prefs ->
            prefs.remove(SALT_KEY)
            prefs.remove(HASH_KEY)
            prefs[ENABLED_KEY] = "false"
        }
        appLockEnabled = false
        _lockState.value = AppLockState.UNLOCKED
    }

    fun lock() { _lockState.value = AppLockState.LOCKED }
    fun unlock() { _lockState.value = AppLockState.UNLOCKED }
    fun isLocked(): Boolean = _lockState.value == AppLockState.LOCKED

    suspend fun verifyPin(pin: String): Boolean {
        val (salt, storedHash) = readSaltAndHash() ?: return false
        val computed = pbkdf2(pin, salt)
        if (!java.security.MessageDigest.isEqual(computed, storedHash)) return false
        unlock()
        return true
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

    private suspend fun readEnabledPref(): Boolean {
        return try {
            dataStore.data.first()[ENABLED_KEY] == "true"
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun readSaltAndHash(): Pair<ByteArray, ByteArray>? {
        return try {
            val prefs = dataStore.data.first()
            val saltB64 = prefs[SALT_KEY] ?: return null
            val hashB64 = prefs[HASH_KEY] ?: return null
            Base64.decode(saltB64, Base64.NO_WRAP) to Base64.decode(hashB64, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    companion object {
        private const val APP_LOCK_STORE_FILE = "app_lock_security"
        private val SALT_KEY = stringPreferencesKey("pin_salt_b64")
        private val HASH_KEY = stringPreferencesKey("pin_hash_b64")
        private val ENABLED_KEY = stringPreferencesKey("app_lock_enabled")

        private const val SALT_BYTES = 16
        private const val PBKDF2_ITERATIONS = 10_000
        private const val KEY_BITS = 256

        private val secureRandom = SecureRandom()
    }
}

enum class AppLockState { LOCKED, UNLOCKED }
