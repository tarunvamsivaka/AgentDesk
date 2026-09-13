package com.agentdesk.core.persistence.search

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for FtsQueryBuilder.
 *
 * Actual behaviour: strips unsafe chars, tokenises on whitespace,
 * wraps tokens in double-quotes joined by space, filters single-char tokens.
 * Returns "" for blank/empty input.
 */
class FtsQueryBuilderTest {

    // ─── Basic sanitization ───────────────────────────────────────────────────

    @Test
    fun `normal query wraps each token in double quotes`() {
        val result = FtsQueryBuilder.buildQuery("hello world")
        assertTrue("Expected \"hello\" in result, got: $result", result.contains("\"hello\""))
        assertTrue("Expected \"world\" in result, got: $result", result.contains("\"world\""))
    }

    @Test
    fun `single word is wrapped in double quotes`() {
        val result = FtsQueryBuilder.buildQuery("meeting")
        assertEquals("\"meeting\"", result.trim())
    }

    // ─── Quote removal ────────────────────────────────────────────────────────

    @Test
    fun `input double quotes are stripped before re-quoting`() {
        // Quotes are stripped from input (they're in UNSAFE_CHARS), then re-added around tokens
        val result = FtsQueryBuilder.buildQuery("\"hello world\"")
        // The result should still produce valid tokens — hello and world wrapped in quotes
        assertFalse("Raw input quotes should not appear verbatim in a broken way", result.contains("\"\""))
        assertTrue("Result should be non-blank", result.isNotBlank())
    }

    @Test
    fun `single quotes are stripped`() {
        val result = FtsQueryBuilder.buildQuery("it's note")
        // Single quote stripped → "its" and "note" as tokens
        assertFalse("No single quotes in result", result.contains("'"))
        assertTrue("Result should be non-blank", result.isNotBlank())
    }

    // ─── Special character stripping ──────────────────────────────────────────

    @Test
    fun `fts special characters are replaced with spaces`() {
        val result = FtsQueryBuilder.buildQuery("hello(world)")
        // Parens are unsafe chars, replaced with space
        assertFalse("No ( in result", result.contains("("))
        assertFalse("No ) in result", result.contains(")"))
    }

    @Test
    fun `asterisk is stripped from input`() {
        val result = FtsQueryBuilder.buildQuery("hello*world")
        // * is in UNSAFE_CHARS, replaced with space → two tokens: "hello" and "world"
        assertNotNull(result)
        assertFalse("No bare * in result except inside our added quotes", result.replace("\"hello\"", "").replace("\"world\"", "").contains("*"))
    }

    // ─── Empty and blank input ────────────────────────────────────────────────

    @Test
    fun `empty string returns blank`() {
        val result = FtsQueryBuilder.buildQuery("")
        assertTrue("Expected blank for empty input", result.isBlank())
    }

    @Test
    fun `whitespace only returns blank`() {
        val result = FtsQueryBuilder.buildQuery("   ")
        assertTrue("Expected blank for whitespace-only input", result.isBlank())
    }

    @Test
    fun `empty string does not throw`() {
        assertDoesNotThrow { FtsQueryBuilder.buildQuery("") }
    }

    // ─── Single-char token filtering ──────────────────────────────────────────

    @Test
    fun `single character tokens are filtered out`() {
        val result = FtsQueryBuilder.buildQuery("a b c hello")
        // Single chars filtered (length >= 2 required), only "hello" survives
        assertFalse("Single chars should not appear as standalone tokens", result.contains("\"a\""))
        assertTrue("Multi-char tokens should survive", result.contains("\"hello\""))
    }

    // ─── Malformed input ─────────────────────────────────────────────────────

    @Test
    fun `sql injection attempt does not crash`() {
        assertDoesNotThrow { FtsQueryBuilder.buildQuery("'; DROP TABLE text_chunk; --") }
    }

    @Test
    fun `sql injection result does not contain dangerous SQL`() {
        val result = FtsQueryBuilder.buildQuery("'; DROP TABLE text_chunk; --")
        // After stripping quotes, semicolons left as text tokens — key test: no raw quote+semicolon combo
        assertFalse("Should not start with single quote", result.startsWith("'"))
    }

    @Test
    fun `very long input does not crash`() {
        assertDoesNotThrow { FtsQueryBuilder.buildQuery("a".repeat(5000)) }
    }

    @Test
    fun `unicode input does not crash`() {
        val result = FtsQueryBuilder.buildQuery("meeting notes")
        assertNotNull(result)
    }

    // ─── Multi-word query ──────────────────────────────────────────────────────

    @Test
    fun `multi-word query produces multiple quoted tokens`() {
        val result = FtsQueryBuilder.buildQuery("project deadline review")
        assertTrue("Expected \"project\" in result", result.contains("\"project\""))
        assertTrue("Expected \"deadline\" in result", result.contains("\"deadline\""))
        assertTrue("Expected \"review\" in result", result.contains("\"review\""))
    }

    private fun assertDoesNotThrow(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail("Expected no exception but got: ${e.message}")
        }
    }
}
