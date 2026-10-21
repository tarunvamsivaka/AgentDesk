package com.agentdesk.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [Redactor].
 *
 * Verifies that sensitive PII (phone numbers, email addresses, credit cards,
 * URLs with credentials, auth tokens/secrets) is reliably redacted before
 * persistence or logging, and benign non-sensitive text passes through unchanged.
 */
class RedactorTest {

    private lateinit var redactor: Redactor

    @Before
    fun setUp() {
        redactor = Redactor()
    }

    @Test
    fun `redacts standard US phone numbers`() {
        val input = "Call me at 555-123-4567 tomorrow"
        val result = redactor.redact(input)
        assertFalse("Should not contain raw phone number", result.contains("555-123-4567"))
        assertTrue("Should contain [PHONE] placeholder", result.contains("[PHONE]"))
        assertEquals("Call me at [PHONE] tomorrow", result)
    }

    @Test
    fun `redacts international phone numbers with country code`() {
        val input = "Dial +1 555-867-5309 or +44 20 7946 0958"
        val result = redactor.redact(input)
        assertFalse(result.contains("+1 555-867-5309"))
        assertFalse(result.contains("+44 20 7946 0958"))
        assertTrue(result.contains("[PHONE]"))
    }

    @Test
    fun `redacts phone numbers with dots or spaces`() {
        val dotted = "Contact 555.234.5678 immediately"
        val spaced = "Office number 555 345 6789"
        assertEquals("Contact [PHONE] immediately", redactor.redact(dotted))
        assertEquals("Office number [PHONE]", redactor.redact(spaced))
    }

    @Test
    fun `redacts email addresses`() {
        val input = "Send feedback to user@example.com for assistance"
        val result = redactor.redact(input)
        assertFalse("Raw email must not remain", result.contains("user@example.com"))
        assertEquals("Send feedback to [EMAIL] for assistance", result)
    }

    @Test
    fun `redacts complex email addresses with tags and subdomains`() {
        val input = "Reports sent to john.doe+audit@mail.sub.domain.co.uk"
        val result = redactor.redact(input)
        assertFalse(result.contains("john.doe+audit@mail.sub.domain.co.uk"))
        assertEquals("Reports sent to [EMAIL]", result)
    }

    @Test
    fun `redacts URLs containing embedded credentials`() {
        val input = "Repository URL: https://admin:superSecret123@git.internal.corp/repo.git"
        val result = redactor.redact(input)
        assertFalse(result.contains("superSecret123"))
        assertEquals("Repository URL: [URL+CREDS]", result)
    }

    @Test
    fun `redacts 16-digit credit card with spaces and dashes`() {
        val spacedCard = "Payment method: 4111 2222 3333 4444"
        val dashedCard = "Charged to 5500-0000-0000-0004"
        val continuousCard = "Card digits 4111222233334444"

        assertEquals("Payment method: [CARD]", redactor.redact(spacedCard))
        assertEquals("Charged to [CARD]", redactor.redact(dashedCard))
        assertEquals("Card digits [CARD]", redactor.redact(continuousCard))
    }

    @Test
    fun `redacts 15-digit Amex credit card`() {
        val amex = "Amex card: 3782 822463 10005"
        assertEquals("Amex card: [CARD]", redactor.redact(amex))
    }

    @Test
    fun `redacts tokens, API keys, and passwords`() {
        val token = "api_key: sk_live_9876543210fedcba"
        val secret = "secret=shhh_dont_tell_anyone"
        val pass = "password: MyP@ssw0rd!"
        val bearer = "bearer: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"

        assertEquals("[TOKEN]", redactor.redact(token))
        assertEquals("[TOKEN]", redactor.redact(secret))
        assertEquals("[TOKEN]", redactor.redact(pass))
        assertEquals("[TOKEN]", redactor.redact(bearer))
    }

    @Test
    fun `redacts case-insensitive tokens and keys`() {
        val upperToken = "TOKEN: my-token-value"
        val mixedKey = "Api_Key = 1234567890"
        val upperSecret = "SECRET = my_secret"

        assertEquals("[TOKEN]", redactor.redact(upperToken))
        assertEquals("[TOKEN]", redactor.redact(mixedKey))
        assertEquals("[TOKEN]", redactor.redact(upperSecret))
    }

    @Test
    fun `safe pass-through of benign text`() {
        val notes = listOf(
            "Buy 2 apples and 3 bananas at the grocery store",
            "Meeting scheduled for tomorrow at 10:00 AM",
            "The quick brown fox jumps over the lazy dog",
            "Project AgentDesk is an on-device AI copilot for Android",
            "Version 2.3.4 was released on Monday"
        )

        for (text in notes) {
            assertEquals("Benign text should remain unchanged", text, redactor.redact(text))
        }
    }

    @Test
    fun `redacts multiple PII entities in a single composite string`() {
        val input = "Call Alice at +1 555-123-4567 or email alice@example.com with token: secret123 on card 4111 2222 3333 4444"
        val result = redactor.redact(input)

        assertFalse(result.contains("+1 555-123-4567"))
        assertFalse(result.contains("alice@example.com"))
        assertFalse(result.contains("secret123"))
        assertFalse(result.contains("4111 2222 3333 4444"))

        assertTrue(result.contains("[PHONE]"))
        assertTrue(result.contains("[EMAIL]"))
        assertTrue(result.contains("[TOKEN]"))
        assertTrue(result.contains("[CARD]"))
    }

    @Test
    fun `redactForLog produces length-tagged placeholder`() {
        assertEquals("<redacted:11chars>", redactor.redactForLog("secret text"))
        assertEquals("<redacted:0chars>", redactor.redactForLog(""))
        assertEquals("<redacted:5chars>", redactor.redactForLog("12345"))
    }

    @Test
    fun `handles empty and blank strings gracefully`() {
        assertEquals("", redactor.redact(""))
        assertEquals("   ", redactor.redact("   "))
    }

    @Test
    fun `places sensitive tokens across 4000 char boundary in 12000 char string and asserts 100 percent redaction`() {
        // Place secret.user@example.com spanning across the 3995..4018 index boundary
        val prefix = "a".repeat(3995)
        val email = "secret.user@example.com"
        val middle = " " + "b".repeat(3980) + " "
        val phone = "+1-555-867-5309"
        val remaining = 12_000 - (prefix.length + email.length + middle.length + phone.length)
        val suffix = " " + "c".repeat(remaining - 1)

        val input = prefix + email + middle + phone + suffix
        assertEquals(12_000, input.length)

        // Verify that the email spans across index 4000
        assertTrue("Email must span index 4000", prefix.length < 4000 && (prefix.length + email.length) > 4000)

        val result = redactor.redact(input)

        assertFalse("Result must not contain raw email", result.contains(email))
        assertFalse("Result must not contain raw phone", result.contains(phone))
        assertTrue("Result must contain [EMAIL] placeholder", result.contains("[EMAIL]"))
        assertTrue("Result must contain [PHONE] placeholder", result.contains("[PHONE]"))
    }
}
