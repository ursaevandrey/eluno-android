package ru.eluno.app

internal const val MIN_WIDGET_OPACITY = 20
internal const val MAX_WIDGET_OPACITY = 100
internal const val MID_WIDGET_OPACITY = 60

/** Map earlier continuous values to the nearest of the three available positions. */
internal fun normalizeWidgetOpacity(opacity: Int): Int =
    listOf(MIN_WIDGET_OPACITY, MID_WIDGET_OPACITY, MAX_WIDGET_OPACITY).minBy { kotlin.math.abs(it.toLong() - opacity.toLong()) }

/** Disabling the background is separate from the lowest visible opacity. */
internal fun widgetBackgroundAlpha(enabled: Boolean, opacity: Int): Float =
    if (enabled) opacity.coerceIn(MIN_WIDGET_OPACITY, MAX_WIDGET_OPACITY) / 100f else 0f

internal enum class WidgetColorMode {
    System, Light, Dark;

    companion object {
        fun fromStorage(value: String?): WidgetColorMode = entries.firstOrNull { it.name == value } ?: System
    }
}

internal fun widgetUsesDarkColors(mode: WidgetColorMode, systemDark: Boolean): Boolean = when (mode) {
    WidgetColorMode.System -> systemDark
    WidgetColorMode.Light -> false
    WidgetColorMode.Dark -> true
}
