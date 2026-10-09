package com.moneyflow.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF126F54),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCF5E8),
    onPrimaryContainer = Color(0xFF064834),
    secondary = Color(0xFF506A60),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF172E29),
    surface = Color.White,
    onSurface = Color(0xFF172E29),
    surfaceVariant = Color(0xFFE8EFEB),
    onSurfaceVariant = Color(0xFF576C64),
    outline = Color(0xFF8A9D94),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ADBBB),
    onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF164F3C),
    onPrimaryContainer = Color(0xFFB6F6D9),
    background = Color(0xFF101C18),
    onBackground = Color(0xFFE0EFE6),
    surface = Color(0xFF172620),
    onSurface = Color(0xFFE0EFE6),
    surfaceVariant = Color(0xFF2B3D34),
    onSurfaceVariant = Color(0xFFBCCEC3),
    outline = Color(0xFF82978B),
)

@Composable
fun MoneyFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
