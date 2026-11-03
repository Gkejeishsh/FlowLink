package com.flowlink.drop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowlink.drop.R

/**
 * Dark-first, one accent, generous radii. The palette is intentionally tiny: this app has one job
 * and every extra colour is a decision someone has to make twice.
 */
object Palette {
    val Primary = Color(0xFF255CE8)
    val PrimaryBright = Color(0xFF5B8CFF)
    val Violet = Color(0xFF8B5CF6)

    val DarkBackground = Color(0xFF070A11)
    val DarkSurface = Color(0xFF0E131C)
    val DarkSurfaceHigh = Color(0xFF161D2B)
    val DarkSurfaceHigher = Color(0xFF1D2536)
    val DarkOnSurface = Color(0xFFF2F5FF)
    val DarkOnSurfaceVariant = Color(0xFFAEB7CC)
    val DarkOutline = Color(0xFF2A3244)

    val LightBackground = Color(0xFFF6F7FB)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceHigh = Color(0xFFEDF0F9)
    val LightOnSurface = Color(0xFF0D1220)
    val LightOnSurfaceVariant = Color(0xFF4A5265)
    val LightOutline = Color(0xFFD6DBEA)

    val Success = Color(0xFF4ADE80)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFFF6B6B)

    val Brand = Brush.linearGradient(listOf(Primary, Violet))
}

private val DarkScheme = darkColorScheme(
    primary = Palette.PrimaryBright,
    onPrimary = Color(0xFF06122E),
    primaryContainer = Palette.Primary,
    onPrimaryContainer = Color.White,
    secondary = Palette.Violet,
    background = Palette.DarkBackground,
    onBackground = Palette.DarkOnSurface,
    surface = Palette.DarkSurface,
    onSurface = Palette.DarkOnSurface,
    surfaceVariant = Palette.DarkSurfaceHigh,
    onSurfaceVariant = Palette.DarkOnSurfaceVariant,
    surfaceContainer = Palette.DarkSurface,
    surfaceContainerHigh = Palette.DarkSurfaceHigh,
    surfaceContainerHighest = Palette.DarkSurfaceHigher,
    outline = Palette.DarkOutline,
    error = Palette.Danger
)

private val LightScheme = lightColorScheme(
    primary = Palette.Primary,
    onPrimary = Color.White,
    secondary = Palette.Violet,
    background = Palette.LightBackground,
    onBackground = Palette.LightOnSurface,
    surface = Palette.LightSurface,
    onSurface = Palette.LightOnSurface,
    surfaceVariant = Palette.LightSurfaceHigh,
    onSurfaceVariant = Palette.LightOnSurfaceVariant,
    surfaceContainer = Palette.LightSurface,
    surfaceContainerHigh = Palette.LightSurfaceHigh,
    outline = Palette.LightOutline,
    error = Color(0xFFB3261E)
)

private val AppFont = FontFamily(Font(R.font.public_sans, FontWeight.Normal))

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 34.sp,
        lineHeight = 38.sp, letterSpacing = (-0.6).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 26.sp,
        lineHeight = 30.sp, letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
        lineHeight = 26.sp, letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp
    )
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun DropTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
