package ru.eluno.app

import ru.eluno.feature.widgets.*

import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider
import androidx.glance.color.ColorProvider as DayNightColors

/** The widget palette is independent of whether the device is on or off. */
internal data class WidgetPalette(val background: Color, val text: Color, val button: Color, val power: Color)

internal val LightWidgetPalette = WidgetPalette(Color(0xFFEADDFF), Color(0xFF211F26), Color(0xFF6750A4), Color.White)
internal val DarkWidgetPalette = WidgetPalette(Color(0xFF443260), Color(0xFFF1ECF4), Color(0xFFD0BCFF), Color(0xFF381E72))

internal fun widgetPalette(mode: WidgetColorMode, systemDark: Boolean): WidgetPalette =
    if (widgetUsesDarkColors(mode, systemDark)) DarkWidgetPalette else LightWidgetPalette

internal fun widgetColorProvider(mode: WidgetColorMode, light: Color, dark: Color): ColorProvider = when (mode) {
    WidgetColorMode.System -> DayNightColors(day = light, night = dark)
    WidgetColorMode.Light -> ColorProvider(light)
    WidgetColorMode.Dark -> ColorProvider(dark)
}

internal fun widgetBackgroundProvider(mode: WidgetColorMode, enabled: Boolean, opacity: Int): ColorProvider {
    if (!enabled) return ColorProvider(Color.Transparent)
    val normalized = normalizeWidgetOpacity(opacity)
    val alpha = widgetBackgroundAlpha(true, normalized)
    return widgetColorProvider(mode, LightWidgetPalette.background.copy(alpha = alpha), DarkWidgetPalette.background.copy(alpha = alpha))
}
