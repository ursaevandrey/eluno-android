package ru.eluno.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4), onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF211F26),
    secondary = Color(0xFF625B71), background = Color(0xFFFFFBFE), onBackground = Color(0xFF211F26),
    surface = Color(0xFFFFFBFE), onSurface = Color(0xFF211F26),
    surfaceVariant = Color(0xFFF1EFF2), onSurfaceVariant = Color(0xFF686273),
    outline = Color(0xFF79747E), outlineVariant = Color(0xFFE4DFE8),
    error = Color(0xFFB3261E), onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF443260), onPrimaryContainer = Color(0xFFF1ECF4),
    secondary = Color(0xFFCCC2DC), background = Color(0xFF1C1B20), onBackground = Color(0xFFF1ECF4),
    surface = Color(0xFF1C1B20), onSurface = Color(0xFFF1ECF4),
    surfaceVariant = Color(0xFF2B2930), onSurfaceVariant = Color(0xFFC1B9CA),
    outline = Color(0xFF938F99), outlineVariant = Color(0xFF49434F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

@Immutable data class ElunoColors(val warning: Color, val warningContainer: Color)
val LocalElunoColors = staticCompositionLocalOf { ElunoColors(Color(0xFF946000), Color(0xFFFFF0D4)) }

val ElunoTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.7).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

private val ElunoShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable fun ElunoTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val extras = if (darkTheme) ElunoColors(Color(0xFFF1C167), Color(0xFF3B3020)) else ElunoColors(Color(0xFF946000), Color(0xFFFFF0D4))
    CompositionLocalProvider(LocalElunoColors provides extras) {
        MaterialTheme(colorScheme = colors, typography = ElunoTypography, shapes = ElunoShapes, content = content)
    }
}
