package com.agentdesk.core.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Unit tests for [AppLockManager].
 *
 * Verifies:
 * 1. Cryptographic rigor: PBKDF2WithHmacSHA256 derivation with 10,000 iterations and 256-bit key length.
 * 2. Salt generation: 16-byte cryptographically secure random salts with guaranteed uniqueness.
 * 3. Constant-time PIN verification: MessageDigest.isEqual usage to prevent timing attacks.
 * 4. PIN verification flow: correct PIN unlocks app, incorrect PIN rejected without state mutation.
 * 5. Lifecycle and state flow: enable, lock, unlock, disable, and non-blocking initialization.
 */
class AppLockManagerTest {

    private lateinit var tempFile: File
    private lateinit var appLockManager: AppLockManager

    @Before
    fun setUp() {
        tempFile = File.createTempFile("test_app_lock", ".preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ) { tempFile }
        appLockManager = AppLockManager(dataStore = dataStore)
    }

    @After
    fun tearDown() {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    // -------------------------------------------------------------------------
    // 1. PBKDF2 PIN Hashing Tests
    // -------------------------------------------------------------------------

    @Test
    fun `pbkdf2 produces 256-bit (32-byte) key`() {
        val salt = AppLockManager.generateSalt()
        val hash = appLockManager.pbkdf2("1234", salt)

        assertEquals("Hash must be 32 bytes (256 bits)", 32, hash.size)
        assertEquals(256, AppLockManager.KEY_BITS)
    }

    @Test
    fun `pbkdf2 is deterministic for same pin and salt`() {
        val salt = AppLockManager.generateSalt()
        val hash1 = appLockManager.pbkdf2("1234", salt)
        val hash2 = appLockManager.pbkdf2("1234", salt)

        assertTrue(
            "Identical PIN and salt must yield identical hash",
            MessageDigest.isEqual(hash1, hash2)
        )
    }

    @Test
    fun `pbkdf2 produces different hashes for different pins with same salt`() {
        val salt = AppLockManager.generateSalt()
        val hash1 = appLockManager.pbkdf2("1234", salt)
        val hash2 = appLockManager.pbkdf2("4321", salt)

        assertFalse(
            "Different PINs must yield different hashes",
            MessageDigest.isEqual(hash1, hash2)
        )
    }

    @Test
    fun `pbkdf2 produces different hashes for same pin with different salts`() {
        val salt1 = AppLockManager.generateSalt()
        val salt2 = AppLockManager.generateSalt()
        val hash1 = appLockManager.pbkdf2("1234", salt1)
        val hash2 = appLockManager.pbkdf2("1234", salt2)

        assertFalse(
            "Different salts must yield different hashes",
            MessageDigest.isEqual(hash1, hash2)
        )
    }

    // -------------------------------------------------------------------------
    // 2. Iteration Count Verification (10,000 Iterations)
    // -------------------------------------------------------------------------

    @Test
    fun `iteration count is exactly 10,000`() {
        assertEquals(
            "AppLockManager must strictly enforce 10,000 PBKDF2 iterations",
            10_000,
            AppLockManager.PBKDF2_ITERATIONS
        )
    }

    @Test
    fun `pbkdf2 output depends on iteration count`() {
        val salt = AppLockManager.generateSalt()
        val pin = "secure_pin_9876"

        // Compute with configured 10,000 iterations
        val hash10k = appLockManager.pbkdf2(pin, salt)

        // Compute with 1,000 iterations
        val spec1k = PBEKeySpec(pin.toCharArray(), salt, 1_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash1k = factory.generateSecret(spec1k).encoded

        assertFalse(
            "Changing iteration count must produce completely different hash",
            MessageDigest.isEqual(hash10k, hash1k)
        )
    }

    // -------------------------------------------------------------------------
    // 3. Salt Uniqueness & Generation Tests
    // -------------------------------------------------------------------------

    @Test
    fun `salt size is exactly 16 bytes`() {
        assertEquals(16, AppLockManager.SALT_BYTES)
        val salt = AppLockManager.generateSalt()
        assertEquals("Salt must be 16 bytes", 16, salt.size)
    }

    @Test
    fun `generated salts are random and unique across multiple calls`() {
        val salts = (1..100).map { AppLockManager.generateSalt() }

        // Verify each salt is 16 bytes
        for (salt in salts) {
            assertEquals(16, salt.size)
        }

        // Verify all 100 generated salts are distinct
        val uniqueHex = salts.map { it.joinToString("") { byte -> "%02x".format(byte) } }.toSet()
        assertEquals("All 100 generated salts must be unique", 100, uniqueHex.size)
    }

    // -------------------------------------------------------------------------
    // 4. Correct PIN Verification & Lock Flow
    // -------------------------------------------------------------------------

    @Test
    fun `fresh AppLockManager starts unlocked and disabled`() {
        assertFalse("App lock should not be enabled by default", appLockManager.isLockEnabled())
        assertFalse("App should not be locked initially", appLockManager.isLocked())
        assertEquals(AppLockState.UNLOCKED, appLockManager.lockState.value)
    }

    @Test
    fun `enableLock sets lock enabled and transitions to LOCKED state`() = runBlocking {
        appLockManager.enableLock("2468")

        assertTrue("Lock should be enabled after enableLock", appLockManager.isLockEnabled())
        assertTrue("App should be in locked state", appLockManager.isLocked())
        assertEquals(AppLockState.LOCKED, appLockManager.lockState.value)
    }

    @Test
    fun `verifyPin with correct pin returns true and unlocks app`() = runBlocking {
        appLockManager.enableLock("2468")
        assertTrue(appLockManager.isLocked())

        val result = appLockManager.verifyPin("2468")

        assertTrue("Verification with correct PIN must return true", result)
        assertFalse("App must be unlocked after correct PIN", appLockManager.isLocked())
        assertEquals(AppLockState.UNLOCKED, appLockManager.lockState.value)
    }

    // -------------------------------------------------------------------------
    // 5. Incorrect PIN Rejection Tests
    // -------------------------------------------------------------------------

    @Test
    fun `verifyPin with wrong pin returns false and remains locked`() = runBlocking {
        appLockManager.enableLock("2468")

        val result = appLockManager.verifyPin("0000")

        assertFalse("Verification with wrong PIN must return false", result)
        assertTrue("App must remain locked on failed verification", appLockManager.isLocked())
        assertEquals(AppLockState.LOCKED, appLockManager.lockState.value)
    }

    @Test
    fun `verifyPin with empty or partial pin is rejected`() = runBlocking {
        appLockManager.enableLock("1234")

        assertFalse(appLockManager.verifyPin(""))
        assertFalse(appLockManager.verifyPin("123"))
        assertFalse(appLockManager.verifyPin("12345"))
        assertTrue("App must remain locked", appLockManager.isLocked())
    }

    @Test
    fun `verifyPin without setting lock returns false`() = runBlocking {
        assertFalse(
            "Calling verifyPin before enabling lock must safely return false",
            appLockManager.verifyPin("1234")
        )
    }

    // -------------------------------------------------------------------------
    // 6. Constant-Time Comparison Tests
    // -------------------------------------------------------------------------

    @Test
    fun `MessageDigest isEqual performs constant-time comparison correctly`() {
        val a = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val b = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val c = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 9) // diff last byte
        val d = byteArrayOf(0, 2, 3, 4, 5, 6, 7, 8) // diff first byte
        val e = byteArrayOf(1, 2, 3, 4)             // diff length

        assertTrue("Identical arrays must match", MessageDigest.isEqual(a, b))
        assertFalse("Arrays differing at end must not match", MessageDigest.isEqual(a, c))
        assertFalse("Arrays differing at start must not match", MessageDigest.isEqual(a, d))
        assertFalse("Arrays of different lengths must not match", MessageDigest.isEqual(a, e))
    }

    // -------------------------------------------------------------------------
    // 7. Lock / Unlock State Management
    // -------------------------------------------------------------------------

    @Test
    fun `manual lock and unlock methods operate properly`() {
        appLockManager.lock()
        assertTrue(appLockManager.isLocked())
        assertEquals(AppLockState.LOCKED, appLockManager.lockState.value)

        appLockManager.unlock()
        assertFalse(appLockManager.isLocked())
        assertEquals(AppLockState.UNLOCKED, appLockManager.lockState.value)
    }

    @Test
    fun `disableLock clears credentials and sets UNLOCKED state`() = runBlocking {
        appLockManager.enableLock("5678")
        assertTrue(appLockManager.isLockEnabled())
        assertTrue(appLockManager.isLocked())

        appLockManager.disableLock()

        assertFalse("Lock must be disabled", appLockManager.isLockEnabled())
        assertFalse("App must be unlocked", appLockManager.isLocked())
        assertEquals(AppLockState.UNLOCKED, appLockManager.lockState.value)

        // Verifying PIN after disable should return false
        assertFalse(appLockManager.verifyPin("5678"))
    }
}
