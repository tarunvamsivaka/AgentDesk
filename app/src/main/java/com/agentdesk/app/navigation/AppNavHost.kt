package com.agentdesk.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agentdesk.feature.chat.ChatScreen
import com.agentdesk.feature.health.HealthScreen
import com.agentdesk.feature.library.LibraryScreen
import com.agentdesk.feature.onboarding.OnboardingScreen
import com.agentdesk.feature.settings.SettingsScreen

/** Route constants for the NavHost. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT = "chat"
    const val LIBRARY = "library"
    const val HEALTH = "health"
    const val SETTINGS = "settings"
}

/** Bottom nav destinations (shown after onboarding). */
private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private val bottomNavItems = listOf(
    BottomNavItem(Routes.CHAT, "Chat", Icons.AutoMirrored.Filled.Chat),
    BottomNavItem(Routes.LIBRARY, "Library", Icons.AutoMirrored.Filled.MenuBook),
    BottomNavItem(Routes.HEALTH, "Health", Icons.Default.Favorite),
    BottomNavItem(Routes.SETTINGS, "Settings", Icons.Default.Settings)
)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute != Routes.ONBOARDING

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = navBackStackEntry?.destination?.hierarchy
                            ?.any { it.route == item.route } == true
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.ONBOARDING,
            modifier = Modifier.padding(innerPadding)
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
