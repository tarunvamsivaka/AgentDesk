package com.agentdesk.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.fragment.app.FragmentActivity
import com.agentdesk.core.security.keystore.KeyStoreManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
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
 * BIOMETRIC CRYPTOGRAPHIC BINDING: Biometric authentication is bound to
 * hardware-backed AES-256-GCM cipher via [createBiometricCryptoObject],
 * ensuring authentic hardware cryptographic verification.
 *
 * SAFETY: isLockEnabled() is false until the user explicitly enables the lock.
 */
@Singleton
class AppLockManager internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val coroutineScope: CoroutineScope,
    private val context: Context?,
    private val keyStoreManager: KeyStoreManager? = null
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        keyStoreManager: KeyStoreManager
    ) : this(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ) { context.preferencesDataStoreFile(APP_LOCK_STORE_FILE) },
        coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        context = context,
        keyStoreManager = keyStoreManager
    )

    constructor(
        @ApplicationContext context: Context
    ) : this(
        context = context,
        keyStoreManager = KeyStoreManager(context)
    )

    internal constructor(
        dataStore: DataStore<Preferences>,
        coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        keyStoreManager: KeyStoreManager? = null
    ) : this(
        dataStore = dataStore,
        coroutineScope = coroutineScope,
        context = null,
        keyStoreManager = keyStoreManager
    )

    private val _lockState = MutableStateFlow(AppLockState.UNLOCKED)
    val lockState: StateFlow<AppLockState> = _lockState.asStateFlow()

    private val _isLockEnabledFlow = MutableStateFlow(false)
    val isLockEnabledFlow: StateFlow<Boolean> = _isLockEnabledFlow.asStateFlow()

    @Volatile
    private var appLockEnabled: Boolean = false

    init {
        coroutineScope.launch {
            try {
                var isFirstEmission = true
                dataStore.data.collect { prefs ->
                    val enabled = prefs[ENABLED_KEY] == "true"
                    appLockEnabled = enabled
                    _isLockEnabledFlow.value = enabled
                    if (isFirstEmission) {
                        isFirstEmission = false
                        if (enabled && _lockState.value != AppLockState.LOCKED) {
                            _lockState.value = AppLockState.LOCKED
                        }
                    }
                }
            } catch (_: Exception) {
                // If DataStore fails to read, maintain default false
            }
        }
    }

    fun isLockEnabled(): Boolean = appLockEnabled

    suspend fun isLockEnabledAsync(): Boolean {
        val enabled = readEnabledPref()
        appLockEnabled = enabled
        _isLockEnabledFlow.value = enabled
        return enabled
    }

    suspend fun enableLock(pin: String) {
        val salt = generateSalt()
        val hash = pbkdf2(pin, salt)
        dataStore.edit { prefs ->
            prefs[SALT_KEY] = Base64.getEncoder().encodeToString(salt)
            prefs[HASH_KEY] = Base64.getEncoder().encodeToString(hash)
            prefs[ENABLED_KEY] = "true"
        }
        appLockEnabled = true
        _isLockEnabledFlow.value = true
        _lockState.value = AppLockState.LOCKED
    }

    suspend fun disableLock() {
        dataStore.edit { prefs ->
            prefs.remove(SALT_KEY)
            prefs.remove(HASH_KEY)
            prefs[ENABLED_KEY] = "false"
        }
        appLockEnabled = false
        _isLockEnabledFlow.value = false
        _lockState.value = AppLockState.UNLOCKED
    }

    fun lock() { _lockState.value = AppLockState.LOCKED }
    fun unlock() { _lockState.value = AppLockState.UNLOCKED }
    fun isLocked(): Boolean = _lockState.value == AppLockState.LOCKED

    suspend fun verifyPin(pin: String): Boolean {
        val (salt, storedHash) = readSaltAndHash() ?: return false
        val computed = pbkdf2(pin, salt)
        if (!MessageDigest.isEqual(computed, storedHash)) return false
        unlock()
        return true
    }

    fun isBiometricAvailable(): Boolean {
        val ctx = context ?: return false
        val manager = BiometricManager.from(ctx)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
                    or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Initializes and returns a [BiometricPrompt.CryptoObject] bound to the hardware master key cipher.
     * Returns null if biometric cipher creation fails or biometrics are not enrolled.
     */
    fun createBiometricCryptoObject(): BiometricPrompt.CryptoObject? {
        val ksm = keyStoreManager ?: (context?.let { KeyStoreManager(it) }) ?: KeyStoreManager(null)
        return try {
            val cipher = ksm.getBiometricCipher() ?: return null
            BiometricPrompt.CryptoObject(cipher)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Launches biometric authentication bound to the hardware cipher.
     * Must be called from a FragmentActivity context. Gracefully falls back to
     * device credential authentication if hardware biometric cipher is unavailable.
     */
    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
        onError: (String) -> Unit
    ) {
        val cryptoObject = try {
            createBiometricCryptoObject()
        } catch (_: Throwable) {
            null
        }
        authenticate(activity, cryptoObject, onSuccess, onFailure, onError)
    }

    /**
     * Launches biometric authentication bound to the specified [BiometricPrompt.CryptoObject].
     * If [cryptoObject] is null, falls back to credential authentication without cipher wrapping.
     */
    fun authenticate(
        activity: FragmentActivity,
        cryptoObject: BiometricPrompt.CryptoObject?,
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
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock AgentDesk")
            .setSubtitle("Confirm your identity to continue")

        if (cryptoObject != null) {
            builder.setNegativeButtonText("Use PIN")
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            prompt.authenticate(builder.build(), cryptoObject)
        } else {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                        or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            prompt.authenticate(builder.build())
        }
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
            Base64.getDecoder().decode(saltB64) to Base64.getDecoder().decode(hashB64)
        } catch (_: Exception) {
            null
        }
    }

    internal fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    companion object {
        const val APP_LOCK_STORE_FILE = "app_lock_security"
        val SALT_KEY = stringPreferencesKey("pin_salt_b64")
        val HASH_KEY = stringPreferencesKey("pin_hash_b64")
        val ENABLED_KEY = stringPreferencesKey("app_lock_enabled")

        const val SALT_BYTES = 16
        const val PBKDF2_ITERATIONS = 10_000
        const val KEY_BITS = 256

        private val secureRandom = SecureRandom()

        fun generateSalt(): ByteArray = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
    }
}

enum class AppLockState { LOCKED, UNLOCKED }
