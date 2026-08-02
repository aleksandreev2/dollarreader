package com.dollarreader.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = PurpleBright,
    onPrimary = DarkText,
    primaryContainer = PurplePrimary,
    onPrimaryContainer = DarkText,
    background = DarkBackground,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkSurfaceRaised,
    onSurfaceVariant = DarkTextMuted,
    outline = DarkSurfaceRaised,
)

private val LightColorScheme = lightColorScheme(
    primary = PurplePrimary,
    onPrimary = LightSurface,
    primaryContainer = PurpleSoft,
    onPrimaryContainer = LightText,
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceRaised,
    onSurfaceVariant = LightTextMuted,
    outline = PurpleSoft,
)

@Composable
fun DollarReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    remember(context) {
        AppAppearancePreferences.initialize(context, darkTheme)
        Unit
    }
    val persistedDarkTheme by AppAppearancePreferences.darkTheme
    var previousRequestedTheme by remember { mutableStateOf(darkTheme) }

    LaunchedEffect(darkTheme) {
        if (darkTheme != previousRequestedTheme) {
            previousRequestedTheme = darkTheme
            AppAppearancePreferences.setDarkTheme(context, darkTheme)
        }
    }

    val effectiveDarkTheme = persistedDarkTheme ?: darkTheme
    MaterialTheme(
        colorScheme = if (effectiveDarkTheme) DarkColorScheme else LightColorScheme,
        typography = DollarTypography,
        content = content,
    )
}
