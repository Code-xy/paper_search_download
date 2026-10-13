package com.seagull.paper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF0E9F94),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB6F0E8),
    onPrimaryContainer = Color(0xFF032E2A),
    secondary = Color(0xFF4A635D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCEDE8),
    onSecondaryContainer = Color(0xFF10201C),
    tertiary = Color(0xFFFF8A65),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6FBFA),
    onBackground = Color(0xFF101817),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101817),
    surfaceVariant = Color(0xFFE7F2F0),
    onSurfaceVariant = Color(0xFF48605B),
    outline = Color(0xFFA8BFBA),
    outlineVariant = Color(0xFFD8E7E4),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6FDCCF),
    onPrimary = Color(0xFF00352F),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFB6F0E8),
    secondary = Color(0xFFB9CCC6),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF2B403B),
    onSecondaryContainer = Color(0xFFDCEDE8),
    tertiary = Color(0xFFFFB59B),
    onTertiary = Color(0xFF3A1103),
    background = Color(0xFF0E1514),
    onBackground = Color(0xFFE4EEEC),
    surface = Color(0xFF141C1B),
    onSurface = Color(0xFFE4EEEC),
    surfaceVariant = Color(0xFF22302E),
    onSurfaceVariant = Color(0xFFB2C7C2),
    outline = Color(0xFF6B807B),
    outlineVariant = Color(0xFF33443F),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC)
)

@Composable
fun PaperTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
