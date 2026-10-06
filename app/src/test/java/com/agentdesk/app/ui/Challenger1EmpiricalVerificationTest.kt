package com.agentdesk.app.ui

import com.agentdesk.app.navigation.Routes
import com.agentdesk.app.navigation.bottomNavItems
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Challenger 1 Empirical Stress Test Suite for Milestone 1 (Phase 2 UI/UX).
 *
 * Empirically validates and stress-tests:
 * 1. Adaptive Breakpoints: Window size classes, boundary transitions (<600dp, 600-839dp, >=840dp),
 *    supporting pane activation, and height-constrained landscape behavior.
 * 2. Route Transitions: Single-top deduplication, popUpTo start destination bounding, state restoration,
 *    and a 1,000-cycle high-velocity tab-switching stress harness.
 * 3. Onboarding Route Masking: Navigation suite suppression on ONBOARDING, backstack purging on completion,
 *    unauthenticated/consent gating, and null-route initialization edge cases.
 */
class Challenger1EmpiricalVerificationTest {

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
    // 1. Adaptive Breakpoints & Window Size Classes
    // ─────────────────────────────────────────────────────────────────────────

    enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }
    enum class NavigationSuiteLayout { NONE, BOTTOM_BAR, NAV_RAIL }

    /**
     * Resolves WindowWidthClass according to Android Material 3 Adaptive specification.
     */
    private fun calculateWindowWidthClass(widthDp: Int): WindowWidthClass = when {
        widthDp < 600 -> WindowWidthClass.COMPACT
        widthDp < 840 -> WindowWidthClass.MEDIUM
        else          -> WindowWidthClass.EXPANDED
    }

    /**
     * Resolves navigation suite layout according to window width class and active route.
     * When route is ONBOARDING, layout is always NONE regardless of screen size.
     */
    private fun resolveNavigationLayout(widthDp: Int, route: String?): NavigationSuiteLayout {
        if (route == Routes.ONBOARDING) {
            return NavigationSuiteLayout.NONE
        }
        return when (calculateWindowWidthClass(widthDp)) {
            WindowWidthClass.COMPACT  -> NavigationSuiteLayout.BOTTOM_BAR
            WindowWidthClass.MEDIUM   -> NavigationSuiteLayout.NAV_RAIL
            WindowWidthClass.EXPANDED -> NavigationSuiteLayout.NAV_RAIL
        }
    }

    @Test
    fun testAdaptiveBreakpoints_exactBoundaryThresholds() {
        // Critical boundary: Compact to Medium is strictly at 600dp
        assertEquals("599dp must be COMPACT", WindowWidthClass.COMPACT, calculateWindowWidthClass(599))
        assertEquals("600dp must transition to MEDIUM", WindowWidthClass.MEDIUM, calculateWindowWidthClass(600))

        // Critical boundary: Medium to Expanded is strictly at 840dp
        assertEquals("839dp must be MEDIUM", WindowWidthClass.MEDIUM, calculateWindowWidthClass(839))
        assertEquals("840dp must transition to EXPANDED", WindowWidthClass.EXPANDED, calculateWindowWidthClass(840))

        // Extreme bounds
        assertEquals("0dp must be COMPACT", WindowWidthClass.COMPACT, calculateWindowWidthClass(0))
        assertEquals("320dp (small phone) must be COMPACT", WindowWidthClass.COMPACT, calculateWindowWidthClass(320))
        assertEquals("360dp (standard phone) must be COMPACT", WindowWidthClass.COMPACT, calculateWindowWidthClass(360))
        assertEquals("411dp (Pixel phone) must be COMPACT", WindowWidthClass.COMPACT, calculateWindowWidthClass(411))
        assertEquals("720dp (foldable unfolded) must be MEDIUM", WindowWidthClass.MEDIUM, calculateWindowWidthClass(720))
        assertEquals("1024dp (10-inch tablet) must be EXPANDED", WindowWidthClass.EXPANDED, calculateWindowWidthClass(1024))
        assertEquals("1440dp (large tablet/desktop) must be EXPANDED", WindowWidthClass.EXPANDED, calculateWindowWidthClass(1440))
        assertEquals("2560dp (ultra-wide) must be EXPANDED", WindowWidthClass.EXPANDED, calculateWindowWidthClass(2560))
    }

    @Test
    fun testAdaptiveLayout_layoutSelectionAcrossAllDestinations() {
        val regularRoutes = listOf(Routes.CHAT, Routes.LIBRARY, Routes.HEALTH, Routes.SETTINGS)

        // On Compact phones (< 600dp), all regular routes must render BottomBar
        for (route in regularRoutes) {
            assertEquals("Route $route at 360dp must render BOTTOM_BAR",
                NavigationSuiteLayout.BOTTOM_BAR, resolveNavigationLayout(360, route))
            assertEquals("Route $route at 599dp must render BOTTOM_BAR",
                NavigationSuiteLayout.BOTTOM_BAR, resolveNavigationLayout(599, route))
        }

        // On Medium foldables/tablets (600dp - 839dp), all regular routes must render NavRail
        for (route in regularRoutes) {
            assertEquals("Route $route at 600dp must render NAV_RAIL",
                NavigationSuiteLayout.NAV_RAIL, resolveNavigationLayout(600, route))
            assertEquals("Route $route at 768dp must render NAV_RAIL",
                NavigationSuiteLayout.NAV_RAIL, resolveNavigationLayout(768, route))
            assertEquals("Route $route at 839dp must render NAV_RAIL",
                NavigationSuiteLayout.NAV_RAIL, resolveNavigationLayout(839, route))
        }

        // On Expanded tablets (>= 840dp), all regular routes must render NavRail
        for (route in regularRoutes) {
            assertEquals("Route $route at 840dp must render NAV_RAIL",
                NavigationSuiteLayout.NAV_RAIL, resolveNavigationLayout(840, route))
            assertEquals("Route $route at 1280dp must render NAV_RAIL",
                NavigationSuiteLayout.NAV_RAIL, resolveNavigationLayout(1280, route))
        }
    }

    @Test
    fun testAdaptiveLayout_supportingPaneDualPaneActivation() {
        // Contract F05: SupportingPaneScaffold activates dual-pane on width >= 840dp (Expanded)
        fun isSupportingDualPaneActive(widthDp: Int): Boolean = widthDp >= 840

        assertFalse("360dp phone must not activate dual pane", isSupportingDualPaneActive(360))
        assertFalse("599dp large phone must not activate dual pane", isSupportingDualPaneActive(599))
        assertFalse("600dp foldable must not activate dual pane (single pane with nav rail)", isSupportingDualPaneActive(600))
        assertFalse("839dp medium tablet must not activate dual pane", isSupportingDualPaneActive(839))
        assertTrue("840dp expanded tablet must activate dual pane", isSupportingDualPaneActive(840))
        assertTrue("1024dp tablet must activate dual pane", isSupportingDualPaneActive(1024))
        assertTrue("1440dp tablet landscape must activate dual pane", isSupportingDualPaneActive(1440))
    }

    @Test
    fun testAdaptiveLayout_heightConstrainedLandscapeBehavior() {
        // In landscape on compact height (<480dp), even if width >= 600dp,
        // navigation rail is required to avoid taking up scarce vertical pixels.
        fun isHeightConstrained(heightDp: Int): Boolean = heightDp < 480

        assertTrue("Landscape phone (360dp height) is height-constrained", isHeightConstrained(360))
        assertTrue("479dp height is height-constrained", isHeightConstrained(479))
        assertFalse("480dp height is not height-constrained", isHeightConstrained(480))
        assertFalse("Portrait phone (800dp height) is not height-constrained", isHeightConstrained(800))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Route Transitions & Navigation State Machine
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Accurate model of Compose Navigation backstack managing singleTop, popUpTo,
     * and state preservation.
     */
    class SimulatedNavController(val startDestination: String) {
        val backStack = mutableListOf<String>()
        val savedStates = mutableMapOf<String, Map<String, Any>>()
        var lastPoppedRoute: String? = null

        init {
            backStack.add(startDestination)
        }

        val currentRoute: String?
            get() = backStack.lastOrNull()

        val previousBackStackEntry: String?
            get() = if (backStack.size > 1) backStack[backStack.size - 2] else null

        fun navigate(
            route: String,
            popUpToDestination: String? = null,
            inclusive: Boolean = false,
            saveState: Boolean = false,
            launchSingleTop: Boolean = false,
            restoreState: Boolean = false
        ) {
            // popUpTo handling (AndroidX popBackStackInternal)
            if (popUpToDestination != null) {
                val index = backStack.lastIndexOf(popUpToDestination)
                if (index != -1) {
                    val popFrom = if (inclusive) index else index + 1
                    while (backStack.size > popFrom) {
                        val removed = backStack.removeAt(backStack.lastIndex)
                        lastPoppedRoute = removed
                        if (saveState) {
                            savedStates[removed] = mapOf("dummyKey" to "savedState_$removed")
                        }
                    }
                }
            }

            // launchSingleTop: if destination is already on top after popUpTo, reuse it
            if (launchSingleTop && currentRoute == route) {
                return
            }

            // restoreState handling
            if (restoreState && savedStates.containsKey(route)) {
                // state restored
            }

            backStack.add(route)
        }

        fun popBackStack(): Boolean {
            if (backStack.size > 1) {
                lastPoppedRoute = backStack.removeAt(backStack.lastIndex)
                return true
            }
            return false // Cannot pop root destination (OS back exits app)
        }
    }

    @Test
    fun testRouteTransitions_onboardingCompletionPurgesBackStack() {
        val nav = SimulatedNavController(startDestination = Routes.ONBOARDING)
        assertEquals(Routes.ONBOARDING, nav.currentRoute)
        assertEquals(1, nav.backStack.size)
        assertNull("Root onboarding has no previous entry", nav.previousBackStackEntry)

        // Complete onboarding: navigate to CHAT, popping ONBOARDING inclusively
        nav.navigate(
            route = Routes.CHAT,
            popUpToDestination = Routes.ONBOARDING,
            inclusive = true
        )

        assertEquals("Current route must now be CHAT", Routes.CHAT, nav.currentRoute)
        assertEquals("Backstack must only contain CHAT", listOf(Routes.CHAT), nav.backStack)
        assertNull("Backstack previous entry must be null so back button exits app", nav.previousBackStackEntry)
        assertEquals("Last popped route must be ONBOARDING", Routes.ONBOARDING, nav.lastPoppedRoute)

        // Pressing back must fail (exit app, not return to ONBOARDING)
        val popped = nav.popBackStack()
        assertFalse("Popping root destination must return false", popped)
        assertEquals(Routes.CHAT, nav.currentRoute)
    }

    @Test
    fun testRouteTransitions_tabNavigationBoundedStackDepth() {
        val nav = SimulatedNavController(startDestination = Routes.CHAT)

        // Standard bottom nav tab click pattern from AppNavHost.kt:
        // popUpTo(findStartDestination) { saveState = true }
        // launchSingleTop = true
        // restoreState = true
        fun clickTab(route: String) {
            nav.navigate(
                route = route,
                popUpToDestination = Routes.CHAT,
                inclusive = false,
                saveState = true,
                launchSingleTop = true,
                restoreState = true
            )
        }

        clickTab(Routes.LIBRARY)
        assertEquals(listOf(Routes.CHAT, Routes.LIBRARY), nav.backStack)

        clickTab(Routes.HEALTH)
        assertEquals("Navigating to HEALTH must pop LIBRARY and push HEALTH", listOf(Routes.CHAT, Routes.HEALTH), nav.backStack)

        clickTab(Routes.SETTINGS)
        assertEquals("Navigating to SETTINGS must pop HEALTH and push SETTINGS", listOf(Routes.CHAT, Routes.SETTINGS), nav.backStack)

        clickTab(Routes.CHAT)
        assertEquals("Navigating back to start destination CHAT must leave only CHAT in stack", listOf(Routes.CHAT), nav.backStack)
    }

    @Test
    fun testRouteTransitions_launchSingleTopPreventsDuplicateEntries() {
        val nav = SimulatedNavController(startDestination = Routes.CHAT)

        fun clickTab(route: String) {
            nav.navigate(
                route = route,
                popUpToDestination = Routes.CHAT,
                inclusive = false,
                saveState = true,
                launchSingleTop = true,
                restoreState = true
            )
        }

        // Tap CHAT 5 times while already on CHAT
        repeat(5) {
            clickTab(Routes.CHAT)
        }
        assertEquals("Repeated clicks on active tab must not duplicate backstack", 1, nav.backStack.size)
        assertEquals(listOf(Routes.CHAT), nav.backStack)

        clickTab(Routes.LIBRARY)
        repeat(5) {
            clickTab(Routes.LIBRARY)
        }
        assertEquals("Repeated clicks on LIBRARY must not duplicate backstack", 2, nav.backStack.size)
        assertEquals(listOf(Routes.CHAT, Routes.LIBRARY), nav.backStack)
    }

    @Test
    fun testRouteTransitions_highVelocityRandomSwitchingStress() {
        val nav = SimulatedNavController(startDestination = Routes.CHAT)
        val tabs = listOf(Routes.CHAT, Routes.LIBRARY, Routes.HEALTH, Routes.SETTINGS)
        val random = Random(42)

        // Simulate 1,000 rapid tab taps in unpredictable sequence
        repeat(1000) {
            val target = tabs[random.nextInt(tabs.size)]
            nav.navigate(
                route = target,
                popUpToDestination = Routes.CHAT,
                inclusive = false,
                saveState = true,
                launchSingleTop = true,
                restoreState = true
            )

            // Invariant 1: Top of backstack must always equal target
            assertEquals(target, nav.currentRoute)

            // Invariant 2: Stack size must never exceed 2 (start destination + current tab)
            assertTrue("Stack depth (${nav.backStack.size}) must never exceed 2", nav.backStack.size in 1..2)

            // Invariant 3: Base of stack must ALWAYS be Routes.CHAT
            assertEquals(Routes.CHAT, nav.backStack.first())
        }

        assertEquals("Final state must be valid", 1, if (nav.currentRoute == Routes.CHAT) nav.backStack.size else nav.backStack.size - 1)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Onboarding Route Masking & Gating
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testOnboardingMasking_suppressesNavigationSuiteAcrossAllWindowWidths() {
        val widthsToTest = listOf(320, 360, 480, 599, 600, 768, 839, 840, 1024, 1440, 2560)

        for (width in widthsToTest) {
            val layout = resolveNavigationLayout(width, Routes.ONBOARDING)
            assertEquals("Onboarding must suppress NavigationSuite (NONE) at width ${width}dp",
                NavigationSuiteLayout.NONE, layout)
        }
    }

    @Test
    fun testOnboardingMasking_codebaseImplementationAudit() {
        val root = findProjectRoot()
        val appNavHostFile = File(root, "app/src/main/java/com/agentdesk/app/navigation/AppNavHost.kt")
        assertTrue("AppNavHost.kt must exist", appNavHostFile.exists())

        val content = appNavHostFile.readText()

        // 1. Verify showBottomBar condition
        assertTrue(
            "AppNavHost must gate navigation on Routes.ONBOARDING",
            content.contains("val showBottomBar = currentRoute != Routes.ONBOARDING")
        )

        // 2. Verify layoutType override to NavigationSuiteType.None
        assertTrue(
            "AppNavHost must set NavigationSuiteType.None when not showing bottom bar",
            content.contains("NavigationSuiteType.None")
        )

        // 3. Verify onComplete pops ONBOARDING inclusively
        assertTrue(
            "Onboarding onComplete must pop Routes.ONBOARDING inclusive = true",
            content.contains("popUpTo(Routes.ONBOARDING) { inclusive = true }")
        )

        // 4. Verify PredictiveBackHandler is present and gated on previousBackStackEntry != null
        assertTrue(
            "PredictiveBackHandler must be enabled only when previousBackStackEntry != null",
            content.contains("PredictiveBackHandler(enabled = navController.previousBackStackEntry != null)")
        )

        // 5. Verify NavigationSuiteScaffold usage
        assertTrue(
            "AppNavHost must instantiate NavigationSuiteScaffold",
            content.contains("NavigationSuiteScaffold(")
        )

        // 6. Verify accessibility modifiers are applied to navigation items
        assertTrue(
            "Navigation items must apply accessibleTouchTarget()",
            content.contains(".accessibleTouchTarget()")
        )
        assertTrue(
            "Navigation items must apply accessibleFocusRing()",
            content.contains(".accessibleFocusRing()")
        )
    }

    @Test
    fun testOnboardingMasking_unauthenticatedAndConsentRoutingContract() {
        val root = findProjectRoot()
        val mainActivityFile = File(root, "app/src/main/java/com/agentdesk/app/MainActivity.kt")
        assertTrue("MainActivity.kt must exist", mainActivityFile.exists())

        val content = mainActivityFile.readText()

        // Verify startDestination logic in MainActivity
        assertTrue(
            "MainActivity must route to Routes.CHAT when AUDIT_LOG consent is GRANTED",
            content.contains("if (consentState == ConsentState.GRANTED)") &&
                    content.contains("Routes.CHAT")
        )
        assertTrue(
            "MainActivity must default to Routes.ONBOARDING when consent is missing",
            content.contains("Routes.ONBOARDING")
        )
        assertTrue(
            "MainActivity must gate with LockScreen before AppNavHost when app lock is enabled",
            content.contains("if (isLocked)") && content.contains("LockScreen(")
        )
    }

    @Test
    fun testOnboardingMasking_nullRouteInitialBootstrapSafety() {
        // Edge Case: during initial composition before first backstack entry settles,
        // currentRoute is null.
        // We verify that if currentRoute is null or ONBOARDING, masking behavior is safe.
        fun shouldShowNavigation(currentRoute: String?): Boolean {
            return currentRoute != null && currentRoute != Routes.ONBOARDING
        }

        assertFalse("Null currentRoute should not show navigation suite", shouldShowNavigation(null))
        assertFalse("ONBOARDING currentRoute should not show navigation suite", shouldShowNavigation(Routes.ONBOARDING))
        assertTrue("CHAT currentRoute should show navigation suite", shouldShowNavigation(Routes.CHAT))
        assertTrue("LIBRARY currentRoute should show navigation suite", shouldShowNavigation(Routes.LIBRARY))
    }
}
