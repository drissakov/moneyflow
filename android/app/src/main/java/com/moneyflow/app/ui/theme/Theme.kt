package com.moneyflow.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneyflow.app.R

// Set every Material role so dialogs, menus and navigation stay monochrome too.
private val LightColors = lightColorScheme(
    primary = Color(0xFF161616),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECECEC),
    onPrimaryContainer = Color(0xFF161616),
    inversePrimary = Color(0xFFD4D4D4),
    secondary = Color(0xFF555555),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF242424),
    tertiary = Color(0xFF606060),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8E8E8),
    onTertiaryContainer = Color(0xFF242424),
    background = Color(0xFFF4F4F4),
    onBackground = Color(0xFF161616),
    surface = Color.White,
    onSurface = Color(0xFF161616),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF606060),
    surfaceTint = Color(0xFF161616),
    inverseSurface = Color(0xFF202020),
    inverseOnSurface = Color(0xFFF7F7F7),
    error = Color(0xFF242424),
    onError = Color.White,
    errorContainer = Color(0xFFE8E8E8),
    onErrorContainer = Color(0xFF161616),
    outline = Color(0xFF858585),
    outlineVariant = Color(0xFFE0E0E0),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE4E4E4),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFEBEBEB),
    surfaceContainerHighest = Color(0xFFE5E5E5),
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainerLowest = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF5F5F5),
    onPrimary = Color(0xFF141414),
    primaryContainer = Color(0xFF303030),
    onPrimaryContainer = Color(0xFFF5F5F5),
    inversePrimary = Color(0xFF303030),
    secondary = Color(0xFFC2C2C2),
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = Color(0xFF292929),
    onSecondaryContainer = Color(0xFFE8E8E8),
    tertiary = Color(0xFFB8B8B8),
    onTertiary = Color(0xFF1A1A1A),
    tertiaryContainer = Color(0xFF343434),
    onTertiaryContainer = Color(0xFFF0F0F0),
    background = Color(0xFF111111),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF303030),
    onSurfaceVariant = Color(0xFFB8B8B8),
    surfaceTint = Color(0xFFF5F5F5),
    inverseSurface = Color(0xFFE8E8E8),
    inverseOnSurface = Color(0xFF202020),
    error = Color(0xFFF0F0F0),
    onError = Color(0xFF161616),
    errorContainer = Color(0xFF373737),
    onErrorContainer = Color(0xFFF5F5F5),
    outline = Color(0xFF808080),
    outlineVariant = Color(0xFF383838),
    scrim = Color.Black,
    surfaceBright = Color(0xFF393939),
    surfaceDim = Color(0xFF111111),
    surfaceContainer = Color(0xFF202020),
    surfaceContainerHigh = Color(0xFF292929),
    surfaceContainerHighest = Color(0xFF333333),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainerLowest = Color(0xFF0C0C0C),
)

// Offline fonts contain Russian, the full Kazakh Cyrillic alphabet and the tenge sign.
private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val InterDisplay = FontFamily(
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
)

private val MoneyFlowTypography = Typography(
    displayLarge = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold,
        fontSize = 52.sp, lineHeight = 60.sp, letterSpacing = (-1.4).sp,
        fontFeatureSettings = "tnum"),
    displayMedium = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold,
        fontSize = 42.sp, lineHeight = 50.sp, letterSpacing = (-1.1).sp,
        fontFeatureSettings = "tnum"),
    displaySmall = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.9).sp,
        fontFeatureSettings = "tnum"),
    headlineLarge = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.7).sp,
        fontFeatureSettings = "tnum"),
    headlineMedium = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum"),
    headlineSmall = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp,
        fontFeatureSettings = "tnum"),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp,
        fontFeatureSettings = "tnum"),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp, fontFeatureSettings = "tnum"),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum"),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp),
)

@Composable
fun MoneyFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MoneyFlowTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 6.dp),
            large = RoundedCornerShape(topStart = 28.dp, topEnd = 8.dp, bottomEnd = 28.dp, bottomStart = 8.dp),
            extraLarge = RoundedCornerShape(topStart = 32.dp, topEnd = 10.dp, bottomEnd = 32.dp, bottomStart = 10.dp),
        ),
        content = content,
    )
}
