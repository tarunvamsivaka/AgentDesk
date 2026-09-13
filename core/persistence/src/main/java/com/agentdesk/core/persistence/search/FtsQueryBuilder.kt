package com.agentdesk.core.persistence.search

/**
 * Sanitizes and builds safe FTS4 queries from raw user input.
 *
 * FTS4 uses a specific query syntax. Unsanitized input can cause:
 * - SQL injection via unbalanced quotes or special FTS operators
 * - Empty/no-op queries crashing on some SQLite versions
 *
 * This builder:
 * 1. Strips characters that break FTS4 syntax
 * 2. Tokenizes on whitespace
 * 3. Wraps tokens in double-quotes for exact term matching
 * 4. Joins tokens with AND for multi-word queries
 */
object FtsQueryBuilder {

    private val UNSAFE_CHARS = Regex("[\"'*^()\\[\\]{};\\\\]") // chars that break FTS4
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_TOKENS = 10
    private const val MAX_TOKEN_LENGTH = 50

    /**
     * Build a safe FTS4 MATCH expression from raw user input.
     * Returns empty string if no valid tokens remain after sanitization.
     */
    fun buildQuery(rawInput: String): String {
        val cleaned = rawInput
            .replace(UNSAFE_CHARS, " ")
            .trim()

        val tokens = cleaned
            .split(WHITESPACE)
            .filter { it.length >= 2 } // skip single-char noise
            .map { it.take(MAX_TOKEN_LENGTH) }
            .take(MAX_TOKENS)

        if (tokens.isEmpty()) return ""

        // Wrap each token in double-quotes for FTS4 literal term search
        return tokens.joinToString(" ") { "\"$it\"" }
    }
}
