package com.anymate.controller.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anymate.controller.R

val Ink950 = Color(0xFF0E0F11)
val Ink900 = Color(0xFF141518)
val Ink850 = Color(0xFF1A1C20)
val Ink800 = Color(0xFF212429)
val Ink750 = Color(0xFF282C31)
val Ink700 = Color(0xFF32363C)

val Violet = Color(0xFF8AB4F8)
val Indigo = Color(0xFF7DD3FC)
val Cyan = Color(0xFF7DD3FC)
val Teal = Color(0xFFC4B5FD)

val TextPrimary = Color(0xFFF2F4F7)
val TextSecondary = Color(0xFFAEB4BE)
val TextMuted = Color(0xFF7A818D)
val Hairline = Color(0x1FFFFFFF)
val HairlineStrong = Color(0x33FFFFFF)
val Success = Color(0xFF4ADE80)
val Danger = Color(0xFFF87171)
val Amber = Color(0xFFFBBF24)

val AccentGradient = Brush.linearGradient(listOf(Violet, Indigo))

val Inter = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold),
    Font(R.font.inter, FontWeight.Bold)
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.SemiBold)
)

private val ControllerTypography = Typography(
    headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 12.5.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 9.sp, letterSpacing = 1.sp)
)

private val ControllerShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

private val ControllerColors = darkColorScheme(
    primary = Violet,
    onPrimary = Ink950,
    primaryContainer = Violet.copy(alpha = 0.2f),
    onPrimaryContainer = TextPrimary,
    secondary = Indigo,
    onSecondary = Ink950,
    tertiary = Teal,
    onTertiary = Ink950,
    background = Ink950,
    onBackground = TextPrimary,
    surface = Ink850,
    onSurface = TextPrimary,
    surfaceVariant = Ink800,
    onSurfaceVariant = TextSecondary,
    outline = Ink700,
    outlineVariant = Hairline,
    error = Danger,
    onError = Ink950
)

@Composable
fun ControllerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ControllerColors,
        typography = ControllerTypography,
        shapes = ControllerShapes,
        content = content
    )
}
