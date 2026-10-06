package com.agentdesk.app.navigation

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agentdesk.app.ui.theme.accessibleFocusRing
import com.agentdesk.app.ui.theme.accessibleTouchTarget
import com.agentdesk.feature.chat.ChatScreen
import com.agentdesk.feature.health.HealthScreen
import com.agentdesk.feature.library.LibraryScreen
import com.agentdesk.feature.onboarding.OnboardingScreen
import com.agentdesk.feature.settings.SettingsScreen
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/** Route constants for the NavHost. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT = "chat"
    const val LIBRARY = "library"
    const val HEALTH = "health"
    const val SETTINGS = "settings"
}

/** Bottom nav destinations (shown after onboarding). */
data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(Routes.CHAT, "Chat", Icons.AutoMirrored.Filled.Chat),
    BottomNavItem(Routes.LIBRARY, "Library", Icons.AutoMirrored.Filled.MenuBook),
    BottomNavItem(Routes.HEALTH, "Health", Icons.Default.Favorite),
    BottomNavItem(Routes.SETTINGS, "Settings", Icons.Default.Settings)
)

@Composable
fun AppNavHost(
    startDestination: String = Routes.ONBOARDING
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute != Routes.ONBOARDING

    // Predictive Back Navigation: Intercept back gesture when navigating backstack
    PredictiveBackHandler(enabled = navController.previousBackStackEntry != null) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { _ ->
                // Predictive back progress tracking without activity teardown
            }
            navController.popBackStack()
        } catch (_: CancellationException) {
            // Cancelled by user swipe gesture
        }
    }

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val layoutType = if (!showBottomBar) {
        NavigationSuiteType.None
    } else {
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
    }

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            if (showBottomBar) {
                bottomNavItems.forEach { item ->
                    val selected = navBackStackEntry?.destination?.hierarchy
                        ?.any { it.route == item.route } == true
                    item(
                        selected = selected,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label
                            )
                        },
                        label = {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier
                            .accessibleTouchTarget()
                            .accessibleFocusRing()
                    )
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onComplete = {
                        navController.navigate(Routes.CHAT) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.CHAT) { ChatScreen() }
            composable(Routes.LIBRARY) { LibraryScreen() }
            composable(Routes.HEALTH) { HealthScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
        }
    }
}
