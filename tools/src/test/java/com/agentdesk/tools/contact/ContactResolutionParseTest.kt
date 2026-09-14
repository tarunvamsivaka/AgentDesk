package com.agentdesk.tools.contact

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the phone-pattern detection in [looksLikePhoneNumber]
 * (used by ContactResolver.resolve before any ContactsContract access).
 */
class ContactResolutionParseTest {

    @Test
    fun `e164 number is detected as phone`() {
        assertTrue(looksLikePhoneNumber("+15551234567"))
    }

    @Test
    fun `local formatted number is detected as phone`() {
        assertTrue(looksLikePhoneNumber("555-123-4567"))
    }

    @Test
    fun `display name is not a phone`() {
        assertFalse(looksLikePhoneNumber("Mom"))
    }

    @Test
    fun `full name with letters is not a phone`() {
        assertFalse(looksLikePhoneNumber("John Smith"))
    }
}
