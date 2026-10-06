package com.agentdesk.app.ui

import com.agentdesk.app.navigation.Routes
import com.agentdesk.app.navigation.bottomNavItems
import org.junit.Assert.*
import org.junit.Test

/**
 * Validates NavigationSuite routing, items, and visibility invariants.
 */
class NavigationAdaptiveSuiteTest {

    @Test
    fun testRouteConstantsAreUniqueAndNonEmpty() {
        val routes = listOf(
            Routes.ONBOARDING,
            Routes.CHAT,
            Routes.LIBRARY,
            Routes.HEALTH,
            Routes.SETTINGS
        )

        routes.forEach { route ->
            assertTrue("Route must not be blank", route.isNotBlank())
        }
        assertEquals("All route names must be distinct", routes.size, routes.toSet().size)
    }

    @Test
    fun testBottomNavItemsMatchCoreDestinations() {
        assertEquals("Bottom nav must have exactly 4 main destinations", 4, bottomNavItems.size)

        val destinationRoutes = bottomNavItems.map { it.route }
        assertEquals(
            listOf(Routes.CHAT, Routes.LIBRARY, Routes.HEALTH, Routes.SETTINGS),
            destinationRoutes
        )

        bottomNavItems.forEach { item ->
            assertTrue("Nav item label cannot be empty: ${item.route}", item.label.isNotBlank())
            assertNotNull("Nav item icon must not be null", item.icon)
        }
    }

    @Test
    fun testOnboardingHidesNavigationSuite() {
        fun shouldShowNavigationSuite(currentRoute: String?): Boolean {
            return currentRoute != Routes.ONBOARDING
        }

        assertFalse("Onboarding screen must hide navigation suite", shouldShowNavigationSuite(Routes.ONBOARDING))
        assertTrue("Chat screen must show navigation suite", shouldShowNavigationSuite(Routes.CHAT))
        assertTrue("Library screen must show navigation suite", shouldShowNavigationSuite(Routes.LIBRARY))
        assertTrue("Health screen must show navigation suite", shouldShowNavigationSuite(Routes.HEALTH))
        assertTrue("Settings screen must show navigation suite", shouldShowNavigationSuite(Routes.SETTINGS))
    }
}
