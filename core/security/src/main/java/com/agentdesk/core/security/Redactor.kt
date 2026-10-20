package com.agentdesk.core.security

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Redacts sensitive information from strings before they are stored in logs or audit events.
 *
 * This is a best-effort, deterministic redactor. It does NOT guarantee perfect anonymization
 * but prevents accidental leakage of common PII patterns in audit trails and debug output.
 *
 * Patterns redacted:
 * - Phone numbers (10-15 digit sequences)
 * - Email addresses
 * - URLs with credentials
 * - Credit/debit card patterns (13-19 digits)
 * - Common tokens and secrets
 */
@Singleton
class Redactor @Inject constructor() {

    fun redact(input: String): String {
        if (input.isEmpty()) return input

        // Fast-path sentinel checks to bypass regex matching when target markers are absent
        val hasAt = '@' in input
        val hasColonOrEqual = ':' in input || '=' in input
        val hasDigit = input.any { it in '0'..'9' }

        if (!hasAt && !hasColonOrEqual && !hasDigit) {
            return input
        }

        // Bounded window scanning for extreme inputs to eliminate ReDoS overhead
        if (input.length > 10_000) {
            val sb = StringBuilder(input.length)
            var start = 0
            val chunkSize = 4000
            while (start < input.length) {
                val end = (start + chunkSize).coerceAtMost(input.length)
                val chunk = input.substring(start, end)
                sb.append(redactChunk(chunk, hasAt, hasColonOrEqual, hasDigit))
                start = end
            }
            return sb.toString()
        }

        return redactChunk(input, hasAt, hasColonOrEqual, hasDigit)
    }

    private fun redactChunk(
        chunk: String,
        hasAt: Boolean,
        hasColonOrEqual: Boolean,
        hasDigit: Boolean
    ): String {
        var result = chunk
        if (hasColonOrEqual) {
            result = URL_WITH_CREDS.replace(result, "[URL+CREDS]")
            result = TOKEN_PATTERN.replace(result, "[TOKEN]")
        }
        if (hasDigit) {
            result = CARD_PATTERN.replace(result, "[CARD]")
        }
        if (hasAt) {
            result = EMAIL_PATTERN.replace(result, "[EMAIL]")
        }
        if (hasDigit) {
            result = PHONE_PATTERN.replace(result, "[PHONE]")
        }
        return result
    }

    /**
     * Redact for log output -- shorter placeholder.
     */
    fun redactForLog(input: String): String = "<redacted:${input.length}chars>"

    companion object {
        private val PHONE_PATTERN = Regex("""(\+?\d[\d\s\-\.]{8,14}\d)""")
        private val EMAIL_PATTERN = Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""")
        private val URL_WITH_CREDS = Regex("""https?://[^@\s]+:[^@\s]+@[^\s]+""")
        private val CARD_PATTERN = Regex("""\b(?:\d[ -]?){13,19}\b""")
        private val TOKEN_PATTERN = Regex("""(?i)(token|secret|key|password|bearer|api_key)\s*[:=]\s*\S+""")
    }
}
