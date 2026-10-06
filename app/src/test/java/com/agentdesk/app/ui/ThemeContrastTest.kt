package com.agentdesk.app.ui

import androidx.compose.ui.graphics.Color
import com.agentdesk.app.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Validates the Wispr Flow dark developer console color tokens and verifies
 * mathematical WCAG 2.2 AA contrast ratios for accessibility.
 */
class ThemeContrastTest {

    private fun linearize(channel: Float): Double {
        return if (channel <= 0.04045f) {
            channel / 12.92
        } else {
            ((channel + 0.055) / 1.055).pow(2.4)
        }
    }

    private fun relativeLuminance(color: Color): Double {
        val r = linearize(color.red)
        val g = linearize(color.green)
        val b = linearize(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrastRatio(fg: Color, bg: Color): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun testWisprFlowPaletteTokens() {
        assertEquals(Color(0xFF0F172A), WisprBackground)
        assertEquals(Color(0xFF1E293B), WisprSurface)
        assertEquals(Color(0xFF22C55E), WisprAccent)
        assertEquals(Color(0xFFF8FAFC), WisprTextPrimary)
        assertEquals(Color(0xFF475569), WisprBorder)
    }

    @Test
    fun testWcagContrastTextPrimaryOnBackground() {
        val ratio = contrastRatio(WisprTextPrimary, WisprBackground)
        // WCAG 2.2 AA requires at least 4.5:1 for normal body text
        assertTrue("Expected contrast ratio >= 4.5, but was $ratio", ratio >= 4.5)
        // High contrast developer console delivers > 15:1
        assertTrue("Expected high-contrast >= 15.0, but was $ratio", ratio >= 15.0)
    }

    @Test
    fun testWcagContrastTextSecondaryOnBackground() {
        val ratio = contrastRatio(WisprTextSecondary, WisprBackground)
        // Muted secondary text must also meet 4.5:1
        assertTrue("Expected contrast ratio >= 4.5, but was $ratio", ratio >= 4.5)
    }

    @Test
    fun testWcagContrastAccentOnBackground() {
        val ratio = contrastRatio(WisprAccent, WisprBackground)
        // WCAG 2.2 AA requires at least 3.0:1 for graphical objects & user interface components
        assertTrue("Expected UI component contrast ratio >= 3.0, but was $ratio", ratio >= 3.0)
        // Terminal green accent on slate delivers > 7.5:1 (exceeding even 4.5:1 text requirement)
        assertTrue("Expected accent ratio >= 7.0, but was $ratio", ratio >= 7.0)
    }

    @Test
    fun testDarkColorSchemeMapping() {
        val scheme = WisprDarkColorScheme
        assertEquals(WisprBackground, scheme.background)
        assertEquals(WisprBackground, scheme.surface)
        assertEquals(WisprSurface, scheme.surfaceContainer)
        assertEquals(WisprAccent, scheme.primary)
        assertEquals(WisprTextPrimary, scheme.onBackground)
        assertEquals(WisprTextPrimary, scheme.onSurface)
        assertEquals(WisprBorder, scheme.outline)
    }
}
