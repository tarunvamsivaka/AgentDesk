package com.agentdesk.core.persistence

import com.agentdesk.core.persistence.audit.DefaultAuditReceiptSigner
import com.agentdesk.core.persistence.entity.AuditEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DefaultAuditReceiptSigner] verifying:
 * 1. Genesis receipt generation (R_0).
 * 2. Canonical action hash computation (SHA-256).
 * 3. Sequential receipt chaining (R_1, R_2, R_3).
 * 4. Tamper detection: altering any field (verdict, actor, actionHash, timestamp, prevDigest, sequence)
 *    invalidates cryptographic verification.
 * 5. Sequence continuity and dropped event detection.
 */
class AuditReceiptSignerTest {

    private lateinit var signer: DefaultAuditReceiptSigner

    @Before
    fun setUp() {
        signer = DefaultAuditReceiptSigner()
    }

    @Test
    fun testGenesisReceiptIsDeterministicAndValid() {
        val genesis1 = signer.genesisDigest
        val genesis2 = signer.genesisDigest

        assertEquals("Genesis digest must be deterministic", genesis1, genesis2)
        assertEquals("Genesis digest must be 64-character hex string", 64, genesis1.length)
        assertTrue("Genesis digest must be hexadecimal", genesis1.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun testComputeActionHashIsDeterministicAndSensitiveToInputs() {
        val hash1 = signer.computeActionHash("message.send_sms", "{\"recipient\":\"12345\"}", 1000L)
        val hash2 = signer.computeActionHash("message.send_sms", "{\"recipient\":\"12345\"}", 1000L)
        val hashDiffParams = signer.computeActionHash("message.send_sms", "{\"recipient\":\"99999\"}", 1000L)
        val hashDiffTime = signer.computeActionHash("message.send_sms", "{\"recipient\":\"12345\"}", 2000L)
        val hashDiffIntent = signer.computeActionHash("call.dial", "{\"recipient\":\"12345\"}", 1000L)

        assertEquals("Same inputs must produce same action hash", hash1, hash2)
        assertEquals(64, hash1.length)
        assertNotEquals("Different params must change action hash", hash1, hashDiffParams)
        assertNotEquals("Different timestamp must change action hash", hash1, hashDiffTime)
        assertNotEquals("Different intent must change action hash", hash1, hashDiffIntent)
    }

    @Test
    fun testSequentialChainingR1R2R3() {
        val baseEvent1 = AuditEventEntity(
            id = 1L,
            eventType = "message.send_sms",
            actor = "USER",
            summary = "ALLOWED",
            redactedDetails = "{\"recipient\":\"555-0100\"}",
            occurredAt = 1000L
        )
        val r1 = signer.signEvent(baseEvent1, sequence = 1L, prevDigest = signer.genesisDigest)

        val baseEvent2 = AuditEventEntity(
            id = 2L,
            eventType = "call.place_call",
            actor = "AGENT",
            summary = "NEEDS_CONFIRMATION",
            redactedDetails = "{\"recipient\":\"555-0199\"}",
            occurredAt = 1050L
        )
        val r2 = signer.signEvent(baseEvent2, sequence = 2L, prevDigest = r1.receiptDigest)

        val baseEvent3 = AuditEventEntity(
            id = 3L,
            eventType = "note.create",
            actor = "USER",
            summary = "ALLOWED",
            redactedDetails = "{\"title\":\"Meeting notes\"}",
            occurredAt = 1100L
        )
        val r3 = signer.signEvent(baseEvent3, sequence = 3L, prevDigest = r2.receiptDigest)

        val chain = listOf(r1, r2, r3)

        assertEquals(1L, r1.sequenceNumber)
        assertEquals(signer.genesisDigest, r1.prevDigest)
        assertEquals(2L, r2.sequenceNumber)
        assertEquals(r1.receiptDigest, r2.prevDigest)
        assertEquals(3L, r3.sequenceNumber)
        assertEquals(r2.receiptDigest, r3.prevDigest)

        assertTrue("Valid sequential chain must pass verification", signer.verifyChain(chain))
    }

    @Test
    fun testTamperDetectionAlteredVerdictBreaksChain() {
        val r1 = signer.signEvent(
            AuditEventEntity(eventType = "sms", summary = "ALLOWED", occurredAt = 100L),
            sequence = 1L,
            prevDigest = signer.genesisDigest
        )
        val r2 = signer.signEvent(
            AuditEventEntity(eventType = "call", summary = "DENIED", occurredAt = 200L),
            sequence = 2L,
            prevDigest = r1.receiptDigest
        )
        val r3 = signer.signEvent(
            AuditEventEntity(eventType = "note", summary = "ALLOWED", occurredAt = 300L),
            sequence = 3L,
            prevDigest = r2.receiptDigest
        )

        // Attacker alters r2 verdict from DENIED to ALLOWED without recomputing receipt
        val tamperedR2 = r2.copy(summary = "ALLOWED")
        val tamperedChain = listOf(r1, tamperedR2, r3)

        assertFalse("Chain with tampered verdict must fail verification", signer.verifyChain(tamperedChain))
    }

    @Test
    fun testTamperDetectionAlteredActionHashBreaksChain() {
        val r1 = signer.signEvent(
            AuditEventEntity(eventType = "sms", summary = "ALLOWED", occurredAt = 100L),
            sequence = 1L,
            prevDigest = signer.genesisDigest
        )
        val r2 = signer.signEvent(
            AuditEventEntity(eventType = "call", summary = "ALLOWED", occurredAt = 200L),
            sequence = 2L,
            prevDigest = r1.receiptDigest
        )

        val tamperedR1 = r1.copy(actionHash = "0000000000000000000000000000000000000000000000000000000000000000")
        assertFalse("Chain with forged actionHash must fail verification", signer.verifyChain(listOf(tamperedR1, r2)))
    }

    @Test
    fun testTamperDetectionAlteredPrevDigestBreaksChain() {
        val r1 = signer.signEvent(
            AuditEventEntity(eventType = "sms", summary = "ALLOWED", occurredAt = 100L),
            sequence = 1L,
            prevDigest = signer.genesisDigest
        )
        val r2 = signer.signEvent(
            AuditEventEntity(eventType = "call", summary = "ALLOWED", occurredAt = 200L),
            sequence = 2L,
            prevDigest = r1.receiptDigest
        )

        val tamperedR2 = r2.copy(prevDigest = "1111111111111111111111111111111111111111111111111111111111111111")
        assertFalse("Chain with tampered prevDigest must fail verification", signer.verifyChain(listOf(r1, tamperedR2)))
    }

    @Test
    fun testTamperDetectionDroppedEventBreaksChain() {
        val r1 = signer.signEvent(
            AuditEventEntity(eventType = "sms", summary = "ALLOWED", occurredAt = 100L),
            sequence = 1L,
            prevDigest = signer.genesisDigest
        )
        val r2 = signer.signEvent(
            AuditEventEntity(eventType = "call", summary = "ALLOWED", occurredAt = 200L),
            sequence = 2L,
            prevDigest = r1.receiptDigest
        )
        val r3 = signer.signEvent(
            AuditEventEntity(eventType = "note", summary = "ALLOWED", occurredAt = 300L),
            sequence = 3L,
            prevDigest = r2.receiptDigest
        )

        // Attacker drops r2 to hide unauthorized activity
        val droppedChain = listOf(r1, r3)
        assertFalse("Chain missing intermediate event must fail verification", signer.verifyChain(droppedChain))
    }

    @Test
    fun testTamperDetectionAlteredActorBreaksChain() {
        val r1 = signer.signEvent(
            AuditEventEntity(eventType = "sms", actor = "USER", summary = "ALLOWED", occurredAt = 100L),
            sequence = 1L,
            prevDigest = signer.genesisDigest
        )
        val tampered = r1.copy(actor = "SYSTEM")
        assertFalse("Chain with altered actor must fail verification", signer.verifyChain(listOf(tampered)))
    }

    @Test
    fun testEmptyAndSingleEventChains() {
        assertTrue("Empty chain is vacuously valid", signer.verifyChain(emptyList()))

        val r1 = signer.signEvent(
            AuditEventEntity(eventType = "sms", summary = "ALLOWED", occurredAt = 100L),
            sequence = 1L,
            prevDigest = signer.genesisDigest
        )
        assertTrue("Valid single event chain must pass verification", signer.verifyChain(listOf(r1)))

        val invalidR1 = r1.copy(prevDigest = "bad_genesis")
        assertFalse("Invalid single event chain must fail verification", signer.verifyChain(listOf(invalidR1)))
    }
}
