package com.agentdesk.core.persistence.audit

import com.agentdesk.core.persistence.entity.AuditEventEntity

/**
 * Interface for sequential cryptographic audit receipt generation and chain verification.
 *
 * Implements sequential HMAC-SHA256 audit chaining:
 * - Genesis Block (R_0): HMAC-SHA256(K_audit, "AGENTDESK_GENESIS_BLOCK")
 * - Canonical Action Hash: SHA-256(intent || canonicalParams || timestamp)
 * - Sequential Receipt (R_i): HMAC-SHA256(K_audit, seq || timestamp || actor || intent || verdict || actionHash || R_{i-1})
 */
interface AuditReceiptSigner {

    /**
     * The fixed genesis receipt digest (R_0) computed over "AGENTDESK_GENESIS_BLOCK".
     */
    val genesisDigest: String

    /**
     * Computes the deterministic SHA-256 action hash over canonical intent and parameter data.
     *
     * @param intentName The identifier of the intent or event type.
     * @param canonicalParams Canonical serialized or redacted parameter payload.
     * @param timestamp Epoch millisecond timestamp of the action.
     * @return Lowercase 64-character hexadecimal SHA-256 hash.
     */
    fun computeActionHash(intentName: String, canonicalParams: String, timestamp: Long): String

    /**
     * Computes the sequential HMAC-SHA256 receipt for an audit entry.
     *
     * @param sequence Monotonically increasing sequence number.
     * @param timestamp Epoch millisecond timestamp.
     * @param actor The initiating actor (e.g., "USER", "AGENT").
     * @param intent The target intent or event type.
     * @param verdict Outcome or policy verdict (e.g., "ALLOWED", "DENIED", "NEEDS_CONFIRMATION").
     * @param actionHash The SHA-256 action hash computed via [computeActionHash].
     * @param prevDigest The receipt digest of the preceding block (R_{i-1}), or [genesisDigest] for R_1.
     * @return Lowercase 64-character hexadecimal HMAC-SHA256 digest.
     */
    fun computeReceipt(
        sequence: Long,
        timestamp: Long,
        actor: String,
        intent: String,
        verdict: String,
        actionHash: String,
        prevDigest: String
    ): String

    /**
     * Verifies the cryptographic integrity of an ordered sequence of [AuditEventEntity] records.
     *
     * Checks:
     * 1. Monotonic sequence continuity (seq_{k+1} == seq_k + 1).
     * 2. Hash chaining linkage (prev_digest_{k} == receipt_digest_{k-1}, and R_1.prev_digest == R_0).
     * 3. Mathematical validity of receipt_digest against HMAC-SHA256 recalculation.
     *
     * @param events List of audit events in ascending sequence order.
     * @return True if and only if all links in the chain are cryptographically valid and untampered.
     */
    fun verifyChain(events: List<AuditEventEntity>): Boolean

    /**
     * Convenience method to sign an [AuditEventEntity], populating sequence_number, action_hash,
     * prev_digest, and receipt_digest.
     */
    fun signEvent(
        event: AuditEventEntity,
        sequence: Long,
        prevDigest: String,
        canonicalParams: String = event.redactedDetails
    ): AuditEventEntity
}
