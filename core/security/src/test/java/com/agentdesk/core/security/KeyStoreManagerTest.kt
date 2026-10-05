package com.agentdesk.core.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.agentdesk.core.security.keystore.KeyStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Unit tests for [KeyStoreManager] and [AppLockManager] cryptographic binding:
 * 1. Master key creation with AES-256 algorithm.
 * 2. Real AES-256-GCM cipher encryption and decryption round-trip.
 * 3. Graceful fallback logic when StrongBox generation throws hardware errors.
 * 4. Verification of KeyStore constants and security parameters.
 * 5. AppLockManager BiometricPrompt.CryptoObject binding.
 */
class KeyStoreManagerTest {

    private lateinit var keyStoreManager: KeyStoreManager
    private lateinit var tempFile: File

    @Before
    fun setUp() {
        keyStoreManager = KeyStoreManager(context = null)
        tempFile = File.createTempFile("test_app_lock_ks", ".preferences_pb")
    }

    @After
    fun tearDown() {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    @Test
    fun testKeyStoreConstantsAndParameters() {
        assertEquals("AgentDeskMasterKey", KeyStoreManager.MASTER_KEY_ALIAS)
        assertEquals("AndroidKeyStore", KeyStoreManager.ANDROID_KEYSTORE_PROVIDER)
        assertEquals("AES/GCM/NoPadding", KeyStoreManager.AES_GCM_TRANSFORMATION)
        assertEquals(256, KeyStoreManager.KEY_SIZE_BITS)
        assertEquals(128, KeyStoreManager.GCM_TAG_LENGTH_BITS)
    }

    @Test
    fun testMasterKeyCreationProducesValidAesKey() {
        val key = keyStoreManager.getOrCreateMasterKey()
        assertNotNull("Master key must not be null", key)
        assertEquals("AES", key.algorithm)
        // 256 bits = 32 bytes
        assertEquals(32, key.encoded.size)
    }

    @Test
    fun testCipherInitializationAndEncryptDecryptRoundTrip() {
        val key = keyStoreManager.getOrCreateMasterKey()
        val encryptCipher = keyStoreManager.createEncryptCipher(key)
        assertNotNull(encryptCipher)
        assertTrue(encryptCipher.algorithm.contains("AES") && encryptCipher.algorithm.contains("GCM"))

        val plaintext = "AgentDesk Zero Cloud Egress Master Secret 2026".toByteArray(Charsets.UTF_8)
        val ciphertext = encryptCipher.doFinal(plaintext)
        assertNotNull(ciphertext)

        val iv = encryptCipher.iv
        assertNotNull(iv)
        assertEquals("GCM IV should be 12 bytes", 12, iv.size)

        val decryptCipher = keyStoreManager.createDecryptCipher(iv, key)
        val decrypted = decryptCipher.doFinal(ciphertext)

        assertArrayEquals("Decrypted plaintext must match original payload", plaintext, decrypted)
    }

    @Test
    fun testGracefulFallbackWhenStrongBoxFails() {
        var strongBoxAttempted = false
        var fallbackAttempted = false

        val testManager = object : KeyStoreManager(context = null) {
            override fun isStrongBoxSupported(): Boolean = true

            override fun generateAesKey(useStrongBox: Boolean): SecretKey {
                if (useStrongBox) {
                    strongBoxAttempted = true
                    throw IllegalStateException("StrongBox hardware security module unavailable")
                } else {
                    fallbackAttempted = true
                    val kg = KeyGenerator.getInstance("AES")
                    kg.init(256)
                    return kg.generateKey()
                }
            }
        }

        val key = testManager.generateMasterKey()
        assertNotNull(key)
        assertTrue("StrongBox generation should have been attempted first", strongBoxAttempted)
        assertTrue("Fallback to TEE/standard key should have been executed upon StrongBox error", fallbackAttempted)
        assertEquals("AES", key.algorithm)
    }

    @Test
    fun testAppLockManagerBiometricCryptoObjectBinding() {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ) { tempFile }

        val appLockManager = AppLockManager(
            dataStore = dataStore,
            coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            keyStoreManager = keyStoreManager
        )

        val cryptoObject = appLockManager.createBiometricCryptoObject()
        assertNotNull("CryptoObject must not be null", cryptoObject)
        val cipher = cryptoObject.cipher
        assertNotNull("Cipher bound inside CryptoObject must not be null", cipher)
        assertTrue("Bound cipher must be AES/GCM", cipher!!.algorithm.contains("AES") && cipher.algorithm.contains("GCM"))
    }
}
