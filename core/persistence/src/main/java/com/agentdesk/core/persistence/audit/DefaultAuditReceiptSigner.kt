package com.agentdesk.core.persistence.audit

import com.agentdesk.core.persistence.entity.AuditEventEntity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [AuditReceiptSigner] providing sequential HMAC-SHA256
 * cryptographic receipt chaining for AgentDesk audit logs.
 */
@Singleton
class DefaultAuditReceiptSigner(
    private val auditKey: ByteArray
) : AuditReceiptSigner {

    @Inject
    constructor() : this(DEFAULT_STATIC_KEY)

    companion object {
        const val GENESIS_PAYLOAD = "AGENTDESK_GENESIS_BLOCK"

        /**
         * 256-bit deterministic static audit seed key for local HMAC signing.
         * Cryptographically isolated from cloud egress.
         */
        private val DEFAULT_STATIC_KEY = "AGENTDESK_AUDIT_CHAIN_HMAC_KEY_V1_2026_SECURE".toByteArray(StandardCharsets.UTF_8)
    }

    override val genesisDigest: String by lazy {
        hmacSha256(GENESIS_PAYLOAD)
    }

    override fun computeActionHash(intentName: String, canonicalParams: String, timestamp: Long): String {
        val input = "$intentName|$canonicalParams|$timestamp"
        val md = MessageDigest.getInstance("SHA-256")
        val digestBytes = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytesToHex(digestBytes)
    }

    override fun computeReceipt(
        sequence: Long,
        timestamp: Long,
        actor: String,
        intent: String,
        verdict: String,
        actionHash: String,
        prevDigest: String
    ): String {
        val payload = "$sequence|$timestamp|$actor|$intent|$verdict|$actionHash|$prevDigest"
        return hmacSha256(payload)
    }

    override fun verifyChain(events: List<AuditEventEntity>): Boolean {
        if (events.isEmpty()) return true

        var expectedPrevDigest = if (events.first().sequenceNumber == 1L) {
            genesisDigest
        } else {
            events.first().prevDigest
        }

        for (i in events.indices) {
            val event = events[i]

            // Sequence continuity check: every subsequent block must have seq == prevSeq + 1
            if (i > 0 && event.sequenceNumber != events[i - 1].sequenceNumber + 1L) {
                return false
            }

            // Digest linkage check: prevDigest must strictly match expected preceding block digest
            if (!constantTimeEquals(event.prevDigest, expectedPrevDigest)) {
                return false
            }

            // Recompute receipt digest and verify in constant time
            val recomputedReceipt = computeReceipt(
                sequence = event.sequenceNumber,
                timestamp = event.occurredAt,
                actor = event.actor,
                intent = event.eventType,
                verdict = event.summary,
                actionHash = event.actionHash,
                prevDigest = event.prevDigest
            )

            if (!constantTimeEquals(event.receiptDigest, recomputedReceipt)) {
                return false
            }

            expectedPrevDigest = event.receiptDigest
        }

        return true
    }

    override fun signEvent(
        event: AuditEventEntity,
        sequence: Long,
        prevDigest: String,
        canonicalParams: String
    ): AuditEventEntity {
        val actionHash = computeActionHash(event.eventType, canonicalParams, event.occurredAt)
        val receipt = computeReceipt(
            sequence = sequence,
            timestamp = event.occurredAt,
            actor = event.actor,
            intent = event.eventType,
            verdict = event.summary,
            actionHash = actionHash,
            prevDigest = prevDigest
        )
        return event.copy(
            sequenceNumber = sequence,
            actionHash = actionHash,
            prevDigest = prevDigest,
            receiptDigest = receipt
        )
    }

    private fun hmacSha256(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(auditKey, "HmacSHA256")
        mac.init(secretKey)
        val digestBytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return bytesToHex(digestBytes)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(StandardCharsets.UTF_8),
            b.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val hexArray = "0123456789abcdef".toCharArray()
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }
}
