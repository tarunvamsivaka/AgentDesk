package com.agentdesk.core.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class VaultBackupManagerTest {

    private lateinit var vaultBackupManager: VaultBackupManager

    @Before
    fun setup() {
        vaultBackupManager = VaultBackupManager()
    }

    @Test
    fun encryptAndDecrypt_validPassword_returnsOriginalPayload() = runBlocking {
        val originalText = "AgentDesk Secure Offline Vault Backup: Notes, Audits, Preferences"
        val payload = originalText.toByteArray(Charsets.UTF_8)
        val password = "SuperSecretMasterPassword2026!".toCharArray()

        val vaultData = vaultBackupManager.encryptVault(payload, password)
        assertNotNull(vaultData)
        assertTrue(vaultData.size > payload.size)

        val decrypted = vaultBackupManager.decryptVault(vaultData, password)
        val decryptedText = String(decrypted, Charsets.UTF_8)

        assertEquals(originalText, decryptedText)
    }

    @Test(expected = SecurityException::class)
    fun decrypt_wrongPassword_throwsSecurityException(): Unit = runBlocking {
        val payload = "Confidential Data".toByteArray(Charsets.UTF_8)
        val correctPassword = "CorrectPassword123".toCharArray()
        val wrongPassword = "WrongPassword456".toCharArray()

        val vaultData = vaultBackupManager.encryptVault(payload, correctPassword)
        vaultBackupManager.decryptVault(vaultData, wrongPassword)
    }

    @Test(expected = SecurityException::class)
    fun decrypt_tamperedCiphertext_throwsSecurityException(): Unit = runBlocking {
        val payload = "Critical Financial Notes".toByteArray(Charsets.UTF_8)
        val password = "StrongPassword789".toCharArray()

        val vaultData = vaultBackupManager.encryptVault(payload, password)
        // Tamper with the last byte of the ciphertext / GCM auth tag
        vaultData[vaultData.size - 1] = (vaultData[vaultData.size - 1].toInt() xor 0xFF).toByte()

        vaultBackupManager.decryptVault(vaultData, password)
    }

    @Test(expected = SecurityException::class)
    fun decrypt_tamperedHeader_throwsSecurityException(): Unit = runBlocking {
        val payload = "Important User Records".toByteArray(Charsets.UTF_8)
        val password = "SecureKey2026".toCharArray()

        val vaultData = vaultBackupManager.encryptVault(payload, password)
        // Corrupt magic bytes
        vaultData[0] = 0x00

        vaultBackupManager.decryptVault(vaultData, password)
    }

    @Test(expected = SecurityException::class)
    fun decrypt_corruptedHmac_throwsSecurityException(): Unit = runBlocking {
        val payload = "Integrity Protected Data".toByteArray(Charsets.UTF_8)
        val password = "MyVaultPassword".toCharArray()

        val vaultData = vaultBackupManager.encryptVault(payload, password)
        // Tamper with byte in the HMAC region (offset: 4 magic + 1 version + 16 salt + 12 IV = 33)
        vaultData[35] = (vaultData[35].toInt() xor 0xAA).toByte()

        vaultBackupManager.decryptVault(vaultData, password)
    }

    @Test
    fun streamingEncryptAndDecrypt_roundTripSucceeds() = runBlocking {
        val originalText = "Streamed Vault Backup Content - large scale on-device verification"
        val inputBytes = originalText.toByteArray(Charsets.UTF_8)
        val password = "StreamingPassword2026!".toCharArray()

        val outputBaos = ByteArrayOutputStream()
        vaultBackupManager.encryptVaultStream(ByteArrayInputStream(inputBytes), outputBaos, password)

        val encryptedBytes = outputBaos.toByteArray()
        assertTrue(encryptedBytes.isNotEmpty())

        val decryptedBaos = ByteArrayOutputStream()
        vaultBackupManager.decryptVaultStream(ByteArrayInputStream(encryptedBytes), decryptedBaos, password)

        val resultText = String(decryptedBaos.toByteArray(), Charsets.UTF_8)
        assertEquals(originalText, resultText)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encryptVaultStream_exceedsMaxSizeGuardrail_throwsException(): Unit = runBlocking {
        val inputBytes = ByteArray(1024)
        val password = "Password123".toCharArray()
        // Force small guardrail limit to trigger guardrail exception
        vaultBackupManager.encryptVaultStream(
            ByteArrayInputStream(inputBytes),
            ByteArrayOutputStream(),
            password,
            maxSizeBytes = 512L
        )
    }
}
