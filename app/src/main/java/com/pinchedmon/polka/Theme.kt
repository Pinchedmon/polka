package com.pinchedmon.polka

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class Palette(
    val paper: Color,
    val card: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val ember: Color,
    val moss: Color,
    val screen: Color,
)

private val Light = Palette(
    paper = Color(0xFFF2EDE3),
    card = Color(0xFFFBF8F2),
    ink = Color(0xFF1C1A17),
    muted = Color(0xFF8A8378),
    line = Color(0xFFE2DBCD),
    ember = Color(0xFFE4572E),
    moss = Color(0xFF5E7D4F),
    screen = Color(0xFFEDE8DC),
)

private val Dark = Palette(
    paper = Color(0xFF141311),
    card = Color(0xFF1E1C19),
    ink = Color(0xFFEFE9DD),
    muted = Color(0xFF8E877B),
    line = Color(0xFF2C2925),
    ember = Color(0xFFFF6B3D),
    moss = Color(0xFF8DB27A),
    screen = Color(0xFFD9D3C6),
)

val LocalPalette = staticCompositionLocalOf { Light }

val Serif = FontFamily.Serif

@Composable
fun PolkaTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = p.ember, background = p.paper, surface = p.card, onSurface = p.ink, onBackground = p.ink)
    } else {
        lightColorScheme(primary = p.ember, background = p.paper, surface = p.card, onSurface = p.ink, onBackground = p.ink)
    }
    val type = Typography(
        displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = (-1).sp),
        titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
        titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
