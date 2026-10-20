package com.agentdesk.core.security

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VaultBackupManager provides zero-cloud, hardware-grade AES-256-GCM encryption
 * and tamper-evident authentication for AgentDesk vault archives (.agentdesk).
 *
 * Cryptographic Specifications:
 * - Key Derivation: PBKDF2WithHmacSHA256, 100,000 iterations, 16-byte random salt.
 *   Derives 512 bits: 256 bits for AES-256-GCM, 256 bits for HMAC-SHA256 integrity verification.
 * - Cipher: AES/GCM/NoPadding, 12-byte random IV, 128-bit authentication tag.
 * - Integrity Pre-Check: HMAC-SHA256 digest verified via MessageDigest.isEqual before GCM decryption.
 * - Tamper Response: Throws SecurityException on bad password, tampered header, or corrupted ciphertext.
 */
@Singleton
class VaultBackupManager @Inject constructor() {

    companion object {
        val MAGIC_BYTES = byteArrayOf(0x41, 0x47, 0x44, 0x4B) // "AGDK"
        const val FORMAT_VERSION: Byte = 0x01
        const val SALT_LENGTH = 16
        const val IV_LENGTH = 12
        const val HMAC_LENGTH = 32
        const val GCM_TAG_BITS = 128
        const val PBKDF2_ITERATIONS = 100_000
        const val TOTAL_KEY_BITS = 512
        const val AES_KEY_BYTES = 32
        const val HMAC_KEY_BYTES = 32
    }

    private val secureRandom = SecureRandom()

    /**
     * Encrypts the provided raw payload into an authenticated .agentdesk vault package.
     */
    fun encryptVault(payload: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        secureRandom.nextBytes(salt)

        val iv = ByteArray(IV_LENGTH)
        secureRandom.nextBytes(iv)

        val (aesKey, hmacKey) = deriveKeys(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), gcmSpec)
        val ciphertext = cipher.doFinal(payload)

        // Compute HMAC-SHA256 over (salt + iv + ciphertext)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        mac.update(salt)
        mac.update(iv)
        val hmac = mac.doFinal(ciphertext)

        // Assemble package: [MAGIC 4B][VERSION 1B][SALT 16B][IV 12B][HMAC 32B][CIPHERTEXT]
        val totalSize = MAGIC_BYTES.size + 1 + SALT_LENGTH + IV_LENGTH + HMAC_LENGTH + ciphertext.size
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.put(MAGIC_BYTES)
        buffer.put(FORMAT_VERSION)
        buffer.put(salt)
        buffer.put(iv)
        buffer.put(hmac)
        buffer.put(ciphertext)

        return buffer.array()
    }

    /**
     * Decrypts an authenticated .agentdesk vault package, verifying integrity before decryption.
     * Throws [SecurityException] if format is invalid, password is wrong, or payload has been tampered with.
     */
    fun decryptVault(vaultData: ByteArray, password: CharArray): ByteArray {
        val minHeaderSize = MAGIC_BYTES.size + 1 + SALT_LENGTH + IV_LENGTH + HMAC_LENGTH
        if (vaultData.size < minHeaderSize) {
            throw SecurityException("Vault integrity compromised or corrupted: invalid header size")
        }

        val buffer = ByteBuffer.wrap(vaultData)
        val magic = ByteArray(MAGIC_BYTES.size)
        buffer.get(magic)
        if (!MessageDigest.isEqual(magic, MAGIC_BYTES)) {
            throw SecurityException("Vault integrity compromised or corrupted: invalid magic header")
        }

        val version = buffer.get()
        if (version != FORMAT_VERSION) {
            throw SecurityException("Vault integrity compromised or corrupted: unsupported version $version")
        }

        val salt = ByteArray(SALT_LENGTH)
        buffer.get(salt)

        val iv = ByteArray(IV_LENGTH)
        buffer.get(iv)

        val expectedHmac = ByteArray(HMAC_LENGTH)
        buffer.get(expectedHmac)

        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val (aesKey, hmacKey) = deriveKeys(password, salt)

        // Verify HMAC-SHA256 header digest
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        mac.update(salt)
        mac.update(iv)
        val computedHmac = mac.doFinal(ciphertext)

        if (!MessageDigest.isEqual(expectedHmac, computedHmac)) {
            throw SecurityException("Vault integrity compromised or corrupted: HMAC authentication failure")
        }

        // Decrypt AES-256-GCM
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), gcmSpec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw SecurityException("Vault integrity compromised or corrupted", e)
        }
    }

    private fun deriveKeys(password: CharArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val keySpec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, TOTAL_KEY_BITS)
        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derivedKeyBytes = keyFactory.generateSecret(keySpec).encoded

        val aesKey = derivedKeyBytes.copyOfRange(0, AES_KEY_BYTES)
        val hmacKey = derivedKeyBytes.copyOfRange(AES_KEY_BYTES, AES_KEY_BYTES + HMAC_KEY_BYTES)
        return Pair(aesKey, hmacKey)
    }
}
