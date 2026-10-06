package com.agentdesk.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Wispr Flow Dark Console Color Scheme
 */
val WisprDarkColorScheme = darkColorScheme(
    primary = WisprAccent,
    onPrimary = WisprBackground,
    primaryContainer = WisprSurface,
    onPrimaryContainer = WisprAccent,
    secondary = WisprCyan,
    onSecondary = WisprBackground,
    secondaryContainer = WisprCyanDim,
    onSecondaryContainer = WisprTextPrimary,
    background = WisprBackground,
    onBackground = WisprTextPrimary,
    surface = WisprBackground,
    onSurface = WisprTextPrimary,
    surfaceVariant = WisprSurface,
    onSurfaceVariant = WisprTextSecondary,
    surfaceContainer = WisprSurface,
    surfaceContainerHigh = WisprSurfaceHigh,
    outline = WisprBorder,
    outlineVariant = WisprBorderSubtle,
    error = WisprError,
    onError = WisprBackground,
    errorContainer = WisprErrorContainer,
    onErrorContainer = WisprOnErrorContainer
)

/**
 * Wispr Flow Light Console Color Scheme (high-contrast fallback)
 */
val WisprLightColorScheme = lightColorScheme(
    primary = WisprLightPrimary,
    onPrimary = Color.White,
    primaryContainer = WisprLightSurfaceVariant,
    onPrimaryContainer = WisprLightPrimary,
    secondary = WisprCyanDim,
    onSecondary = Color.White,
    background = WisprLightBackground,
    onBackground = WisprLightTextPrimary,
    surface = WisprLightSurface,
    onSurface = WisprLightTextPrimary,
    surfaceVariant = WisprLightSurfaceVariant,
    onSurfaceVariant = WisprLightTextSecondary,
    surfaceContainer = WisprLightSurface,
    outline = WisprLightBorder,
    error = WisprError,
    onError = Color.White
)

/**
 * Developer console typography featuring monospace elements for code and technical data.
 */
val WisprTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.25.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * AgentDesk theme composable applying Wispr Flow styling and edge-to-edge system bars.
 */
@Composable
fun AgentDeskTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) WisprDarkColorScheme else WisprLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = WisprTypography,
        content = content
    )
}

/**
 * WCAG 2.2 AA compliant visible focus ring for keyboard / DPAD navigation.
 */
fun Modifier.accessibleFocusRing(
    ringColor: Color = WisprAccent,
    shape: Shape = RoundedCornerShape(8.dp)
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { isFocused = it.isFocused }
        .then(if (isFocused) Modifier.border(2.dp, ringColor, shape) else Modifier)
}

/**
 * Guarantees minimum 44x44dp interactive touch target size per WCAG 2.2 AA standards.
 */
fun Modifier.accessibleTouchTarget(): Modifier =
    this.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
