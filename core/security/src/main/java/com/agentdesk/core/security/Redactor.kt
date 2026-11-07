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
 * - US Social Security Numbers (SSN: \d{3}-\d{2}-\d{4})
 * - Common tokens, bearer auth headers, and secrets
 */
@Singleton
class Redactor @Inject constructor() {

    fun redact(input: String): String {
        if (input.isEmpty()) return input

        // Fast-path sentinel checks to bypass regex matching when target markers are absent
        val hasAt = '@' in input
        val hasColonOrEqual = ':' in input || '=' in input
        val hasDigit = input.any { it in '0'..'9' }
        val hasBearer = input.contains("bearer", ignoreCase = true)

        if (!hasAt && !hasColonOrEqual && !hasDigit && !hasBearer) {
            return input
        }

        // Bounded window scanning for extreme inputs to eliminate ReDoS overhead
        if (input.length > 10_000) {
            val sb = StringBuilder(input.length)
            var start = 0
            val chunkSize = 4000
            while (start < input.length) {
                var end = (start + chunkSize).coerceAtMost(input.length)
                // When end < input.length, advance end up to 128 chars forward to next whitespace
                if (end < input.length) {
                    val maxScan = (end + 128).coerceAtMost(input.length)
                    for (i in end until maxScan) {
                        if (input[i].isWhitespace()) {
                            end = i
                            break
                        }
                    }
                }
                val chunk = input.substring(start, end)
                sb.append(redactChunk(chunk, hasAt, hasColonOrEqual, hasDigit, hasBearer))
                start = end
            }
            return sb.toString()
        }

        return redactChunk(input, hasAt, hasColonOrEqual, hasDigit, hasBearer)
    }

    private fun redactChunk(
        chunk: String,
        hasAt: Boolean,
        hasColonOrEqual: Boolean,
        hasDigit: Boolean,
        hasBearer: Boolean
    ): String {
        var result = chunk
        if (hasColonOrEqual) {
            result = URL_WITH_CREDS.replace(result, "[URL+CREDS]")
            result = TOKEN_PATTERN.replace(result, "[TOKEN]")
        }
        if (hasBearer) {
            result = BEARER_PATTERN.replace(result, "[TOKEN]")
        }
        if (hasDigit) {
            result = CARD_PATTERN.replace(result, "[CARD]")
            result = SSN_PATTERN.replace(result, "[SSN]")
            result = PHONE_PATTERN.replace(result, "[PHONE]")
        }
        if (hasAt) {
            result = EMAIL_PATTERN.replace(result, "[EMAIL]")
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
        private val SSN_PATTERN = Regex("""\b\d{3}-\d{2}-\d{4}\b""")
        private val TOKEN_PATTERN = Regex("""(?i)(token|secret|key|password|bearer|api_key)\s*[:=]\s*\S+""")
        private val BEARER_PATTERN = Regex("""(?i)\bbearer\s+[a-zA-Z0-9_\-\.=]+""")
    }
}
