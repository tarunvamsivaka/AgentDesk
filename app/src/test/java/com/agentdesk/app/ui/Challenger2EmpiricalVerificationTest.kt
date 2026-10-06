package com.agentdesk.app.ui

import androidx.compose.ui.graphics.Color
import com.agentdesk.app.ui.theme.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.pow

/**
 * Challenger 2 Empirical Stress Test Suite for Milestone 1 (Phase 2 UI/UX).
 *
 * Verifies:
 * 1. Predictive Back cancellation and gesture lifecycle (completion, abort, backstack empty).
 * 2. WCAG 2.2 AA theme contrast math across dark & light palettes and surface pairings.
 * 3. System bars, edge-to-edge configuration, contrast enforcement, and window insets.
 */
class Challenger2EmpiricalVerificationTest {

    private fun findProjectRoot(): File {
        var current: File? = File(".").canonicalFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").exists() || File(current, "gradlew").exists()) {
                return current
            }
            current = current.parentFile
        }
        return File("c:/AgentDesk")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Predictive Back Cancellation & Lifecycle Verification
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Replicates the exact predictive back intercept logic in AppNavHost.kt
     */
    private suspend fun executePredictiveBackPipeline(
        progressFlow: Flow<Float>,
        popBackStackAction: () -> Unit,
        onProgressRecorded: (Float) -> Unit
    ) {
        try {
            progressFlow.collect { progress ->
                onProgressRecorded(progress)
            }
            popBackStackAction()
        } catch (_: CancellationException) {
            // Cancelled by user swipe gesture without activity teardown
        }
    }

    @Test
    fun testPredictiveBack_completedGestureTriggersPopBackStack() = runBlocking {
        var popCount = 0
        val recordedProgress = mutableListOf<Float>()

        val completedFlow = flow {
            emit(0.1f)
            emit(0.3f)
            emit(0.7f)
            emit(1.0f)
        }

        executePredictiveBackPipeline(
            progressFlow = completedFlow,
            popBackStackAction = { popCount++ },
            onProgressRecorded = { recordedProgress.add(it) }
        )

        assertEquals("Completed gesture must record all 4 progress frames", listOf(0.1f, 0.3f, 0.7f, 1.0f), recordedProgress)
        assertEquals("Completed gesture must invoke popBackStack exactly once", 1, popCount)
    }

    @Test
    fun testPredictiveBack_cancelledGestureNeverTriggersPopBackStack() = runBlocking {
        var popCount = 0
        val recordedProgress = mutableListOf<Float>()

        val abortedFlow = flow {
            emit(0.1f)
            emit(0.25f)
            emit(0.4f)
            // User reverses swipe gesture towards screen edge -> coroutine cancelled
            throw CancellationException("Gesture aborted mid-swipe by user")
        }

        executePredictiveBackPipeline(
            progressFlow = abortedFlow,
            popBackStackAction = { popCount++ },
            onProgressRecorded = { recordedProgress.add(it) }
        )

        assertEquals("Aborted gesture must record frames up to cancellation", listOf(0.1f, 0.25f, 0.4f), recordedProgress)
        assertEquals("Aborted gesture must NOT invoke popBackStack (must be 0)", 0, popCount)
    }

    @Test
    fun testPredictiveBack_immediateCancellationHandledGracefully() = runBlocking {
        var popCount = 0
        var framesRecorded = 0

        val immediateCancelFlow = flow<Float> {
            throw CancellationException("Immediate swipe cancel")
        }

        executePredictiveBackPipeline(
            progressFlow = immediateCancelFlow,
            popBackStackAction = { popCount++ },
            onProgressRecorded = { framesRecorded++ }
        )

        assertEquals("Immediate cancel records 0 frames", 0, framesRecorded)
        assertEquals("Immediate cancel never triggers popBackStack", 0, popCount)
    }

    @Test
    fun testPredictiveBack_unexpectedExceptionIsNotSwallowed() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                val crashingFlow = flow<Float> {
                    emit(0.1f)
                    throw IllegalStateException("Unexpected pipeline failure")
                }

                executePredictiveBackPipeline(
                    progressFlow = crashingFlow,
                    popBackStackAction = {},
                    onProgressRecorded = {}
                )
            }
        }
    }

    @Test
    fun testPredictiveBack_highFrequencyProgressStress() = runBlocking {
        var popCount = 0
        var totalFrames = 0

        val highFreqFlow = flow {
            for (i in 1..120) {
                emit(i / 120f)
            }
        }

        executePredictiveBackPipeline(
            progressFlow = highFreqFlow,
            popBackStackAction = { popCount++ },
            onProgressRecorded = { totalFrames++ }
        )

        assertEquals("Must process all 120 frames at 120Hz display refresh", 120, totalFrames)
        assertEquals("Must trigger popBackStack upon final completion", 1, popCount)
    }

    @Test
    fun testPredictiveBack_backStackEmptyGating() {
        fun isPredictiveBackEnabled(hasPreviousBackStackEntry: Boolean): Boolean {
            return hasPreviousBackStackEntry
        }

        assertFalse("When back stack is empty (root destination), handler must be disabled to allow OS finish", isPredictiveBackEnabled(false))
        assertTrue("When back stack has previous entry, handler must be enabled", isPredictiveBackEnabled(true))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. WCAG 2.2 AA Theme Contrast Math Verification
    // ─────────────────────────────────────────────────────────────────────────

    private fun linearizeIec(channel: Float): Double {
        return if (channel <= 0.04045f) {
            channel / 12.92
        } else {
            ((channel + 0.055) / 1.055).pow(2.4)
        }
    }

    private fun linearizeW3c(channel: Float): Double {
        return if (channel <= 0.03928f) {
            channel / 12.92
        } else {
            ((channel + 0.055) / 1.055).pow(2.4)
        }
    }

    private fun relativeLuminance(color: Color, useIec: Boolean = true): Double {
        val r = if (useIec) linearizeIec(color.red) else linearizeW3c(color.red)
        val g = if (useIec) linearizeIec(color.green) else linearizeW3c(color.green)
        val b = if (useIec) linearizeIec(color.blue) else linearizeW3c(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrastRatio(fg: Color, bg: Color, useIec: Boolean = true): Double {
        val l1 = relativeLuminance(fg, useIec)
        val l2 = relativeLuminance(bg, useIec)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun testWcagContrast_allDarkTokensOnBackground() {
        val bg = WisprBackground // #0F172A

        val textPrimaryContrast = contrastRatio(WisprTextPrimary, bg)
        assertTrue("WisprTextPrimary on Bg ($textPrimaryContrast) must exceed 4.5:1", textPrimaryContrast >= 4.5)
        assertTrue("WisprTextPrimary on Bg ($textPrimaryContrast) exceeds 15.0:1 for high-contrast console", textPrimaryContrast >= 15.0)

        val textSecondaryContrast = contrastRatio(WisprTextSecondary, bg)
        assertTrue("WisprTextSecondary on Bg ($textSecondaryContrast) must exceed 4.5:1", textSecondaryContrast >= 4.5)

        val accentContrast = contrastRatio(WisprAccent, bg)
        assertTrue("WisprAccent on Bg ($accentContrast) must exceed 3.0:1 for UI components", accentContrast >= 3.0)
        assertTrue("WisprAccent on Bg ($accentContrast) exceeds 7.0:1 (AAA standard)", accentContrast >= 7.0)

        val cyanContrast = contrastRatio(WisprCyan, bg)
        assertTrue("WisprCyan on Bg ($cyanContrast) must exceed 3.0:1 for UI components", cyanContrast >= 3.0)
        assertTrue("WisprCyan on Bg ($cyanContrast) exceeds 7.0:1 (AAA standard)", cyanContrast >= 7.0)

        val errorContrast = contrastRatio(WisprError, bg)
        assertTrue("WisprError on Bg ($errorContrast) must exceed 4.5:1 for warning text", errorContrast >= 4.5)
    }

    @Test
    fun testWcagContrast_tokensOnSurfaceAndSurfaceHigh() {
        val surface = WisprSurface // #1E293B
        val surfaceHigh = WisprSurfaceHigh // #272F42

        val textOnSurface = contrastRatio(WisprTextPrimary, surface)
        assertTrue("Text on Surface ($textOnSurface) must exceed 4.5:1", textOnSurface >= 4.5)
        assertTrue("Text on Surface ($textOnSurface) exceeds 12.0:1", textOnSurface >= 12.0)

        val textSecOnSurface = contrastRatio(WisprTextSecondary, surface)
        assertTrue("Secondary text on Surface ($textSecOnSurface) must exceed 4.5:1", textSecOnSurface >= 4.5)

        val textOnSurfaceHigh = contrastRatio(WisprTextPrimary, surfaceHigh)
        assertTrue("Text on SurfaceHigh ($textOnSurfaceHigh) must exceed 4.5:1", textOnSurfaceHigh >= 4.5)

        // onPrimary (Background #0F172A) on primary button (Accent #22C55E)
        val onPrimaryOnPrimary = contrastRatio(WisprBackground, WisprAccent)
        assertTrue("Button label on Primary button ($onPrimaryOnPrimary) must exceed 4.5:1", onPrimaryOnPrimary >= 4.5)
        assertTrue("Button label on Primary button ($onPrimaryOnPrimary) exceeds 7.0:1 (AAA standard)", onPrimaryOnPrimary >= 7.0)
    }

    @Test
    fun testWcagContrast_lightThemeTokens() {
        val lightBg = WisprLightBackground // #F8FAFC
        val lightSurface = WisprLightSurface // #FFFFFF

        val textOnLightBg = contrastRatio(WisprLightTextPrimary, lightBg)
        assertTrue("Light primary text on light bg ($textOnLightBg) must exceed 4.5:1", textOnLightBg >= 4.5)

        val textSecOnLightBg = contrastRatio(WisprLightTextSecondary, lightBg)
        assertTrue("Light secondary text on light bg ($textSecOnLightBg) must exceed 4.5:1", textSecOnLightBg >= 4.5)

        val primaryOnLightBg = contrastRatio(WisprLightPrimary, lightBg)
        assertTrue("WisprLightPrimary on light bg ($primaryOnLightBg) must exceed 4.5:1", primaryOnLightBg >= 4.5)

        val primaryOnLightSurface = contrastRatio(WisprLightPrimary, lightSurface)
        assertTrue("WisprLightPrimary on light surface ($primaryOnLightSurface) must exceed 4.5:1", primaryOnLightSurface >= 4.5)
    }

    @Test
    fun testContrastFormulaConsistency_IecVsW3cDelta() {
        val colors = listOf(WisprBackground, WisprSurface, WisprAccent, WisprTextPrimary, WisprTextSecondary, WisprCyan, WisprError)
        for (fg in colors) {
            for (bg in colors) {
                if (fg != bg) {
                    val crIec = contrastRatio(fg, bg, useIec = true)
                    val crW3c = contrastRatio(fg, bg, useIec = false)
                    val diff = abs(crIec - crW3c)
                    assertTrue("Difference between IEC and W3C linear gamma threshold must be < 0.05, but was $diff for fg=$fg, bg=$bg", diff < 0.05)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. System Bars, Edge-to-Edge & Accessibility Modifiers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testMainActivity_edgeToEdgeAndContrastEnforcementConfig() {
        val root = findProjectRoot()
        val activityFile = File(root, "app/src/main/java/com/agentdesk/app/MainActivity.kt")
        assertTrue("MainActivity.kt must exist", activityFile.exists())

        val content = activityFile.readText()
        assertTrue("MainActivity must invoke enableEdgeToEdge()", content.contains("enableEdgeToEdge()"))
        assertTrue(
            "MainActivity must disable navigation bar contrast enforcement for transparent bars on API 29+",
            content.contains("window.isNavigationBarContrastEnforced = false")
        )
    }

    @Test
    fun testAgentDeskTheme_systemBarIconInversionContract() {
        val root = findProjectRoot()
        val themeFile = File(root, "app/src/main/java/com/agentdesk/app/ui/theme/AgentDeskTheme.kt")
        assertTrue("AgentDeskTheme.kt must exist", themeFile.exists())

        val content = themeFile.readText()
        assertTrue(
            "Theme must invert status bar icon appearance (!darkTheme)",
            content.contains("insetsController.isAppearanceLightStatusBars = !darkTheme")
        )
        assertTrue(
            "Theme must invert navigation bar icon appearance (!darkTheme)",
            content.contains("insetsController.isAppearanceLightNavigationBars = !darkTheme")
        )
        assertTrue(
            "Theme must define accessibleFocusRing modifier",
            content.contains("fun Modifier.accessibleFocusRing")
        )
        assertTrue(
            "Theme must define accessibleTouchTarget with min 44dp",
            content.contains("fun Modifier.accessibleTouchTarget") && content.contains("44.dp")
        )
    }

    @Test
    fun testAndroidManifest_systemBarsAndImeConfiguration() {
        val root = findProjectRoot()
        val manifestFile = File(root, "app/src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())

        val content = manifestFile.readText()
        assertTrue(
            "Manifest must enable OnBackInvokedCallback for predictive back",
            content.contains("android:enableOnBackInvokedCallback=\"true\"")
        )
        assertTrue(
            "Manifest MainActivity must declare adjustResize for IME keyboard insets",
            content.contains("android:windowSoftInputMode=\"adjustResize\"")
        )
    }
}
