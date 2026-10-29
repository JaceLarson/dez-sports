package com.dez.sports.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography
import kotlin.math.min

object Dez {
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF151517)
    val Card2 = Color(0xFF1E1E21)
    val Hairline = Color(0xFF2A2A2E)
    val Text = Color(0xFFF5F5F7)
    val Sub = Color(0xFF9A9AA2)
    val Faint = Color(0xFF5E5E66)
    val Live = Color(0xFFFF3B30)
    val Win = Color(0xFF34C759)
    val Gold = Color(0xFFFFD60A)
    val Accent = Color(0xFF0A84FF)
}

/** Team colours from ESPN are sometimes near-black or near-white; nudge them into a range that reads on a dark UI. */
fun teamColor(primary: Long, alt: Long): Color {
    val p = Color(primary); val a = Color(alt)
    val pl = p.luminance()
    return when {
        pl < 0.015f && a.luminance() in 0.03f..0.7f -> a
        pl < 0.015f -> Color(0xFF3A3A40)
        pl > 0.8f && a.luminance() < 0.7f && a.luminance() > 0.015f -> a
        pl > 0.8f -> Color(0xFF55555C)
        else -> p
    }
}

fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)
fun Color.lighten(f: Float) = Color(min(1f, red + (1 - red) * f), min(1f, green + (1 - green) * f), min(1f, blue + (1 - blue) * f), alpha)
fun Color.readableOnDark(): Color = if (luminance() < 0.08f) lighten(0.45f) else this

private val Display = FontFamily.Default

val DezTypography = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 44.sp, letterSpacing = (-1.5).sp),
    headlineLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 32.sp, letterSpacing = (-1).sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = Display, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Display, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Display, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp),
)

@Composable
fun DezTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Dez.Bg, surface = Dez.Card, surfaceVariant = Dez.Card2, primary = Dez.Accent,
            onBackground = Dez.Text, onSurface = Dez.Text, onSurfaceVariant = Dez.Sub, outline = Dez.Hairline,
        ),
        typography = DezTypography,
        content = content,
    )
}
