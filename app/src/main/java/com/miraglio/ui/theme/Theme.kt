package com.miraglio.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = GreenDark,
    onPrimary = Cream,
    primaryContainer = GreenLight,
    secondary = OrangeDark,
    // Espresso su OrangeDark: contrasto ~4,85 (WCAG AA); Cream era ~3,6.
    onSecondary = Espresso,
    secondaryContainer = Orange80,
    background = BackgroundLight,
    surface = Cream,
    onBackground = BrownDark,
    onSurface = BrownDark
)

private val DarkColorScheme = darkColorScheme(
    primary = Green80,
    onPrimary = BrownDark,
    primaryContainer = GreenDark,
    secondary = Orange80,
    onSecondary = BrownDark,
    secondaryContainer = OrangeDark
)

@Composable
fun MiraglioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
