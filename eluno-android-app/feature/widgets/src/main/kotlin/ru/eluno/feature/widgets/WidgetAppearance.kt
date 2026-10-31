package ru.eluno.feature.widgets

const val MIN_WIDGET_OPACITY = 20
const val MAX_WIDGET_OPACITY = 100
const val MID_WIDGET_OPACITY = 60

/** Map earlier continuous values to the nearest of the three available positions. */
fun normalizeWidgetOpacity(opacity: Int): Int =
    listOf(MIN_WIDGET_OPACITY, MID_WIDGET_OPACITY, MAX_WIDGET_OPACITY).minBy { kotlin.math.abs(it.toLong() - opacity.toLong()) }

/** Disabling the background is separate from the lowest visible opacity. */
fun widgetBackgroundAlpha(enabled: Boolean, opacity: Int): Float =
    if (enabled) opacity.coerceIn(MIN_WIDGET_OPACITY, MAX_WIDGET_OPACITY) / 100f else 0f

enum class WidgetColorMode {
    System, Light, Dark;

    companion object {
        fun fromStorage(value: String?): WidgetColorMode = entries.firstOrNull { it.name == value } ?: System
    }
}

fun widgetUsesDarkColors(mode: WidgetColorMode, systemDark: Boolean): Boolean = when (mode) {
    WidgetColorMode.System -> systemDark
    WidgetColorMode.Light -> false
    WidgetColorMode.Dark -> true
}
