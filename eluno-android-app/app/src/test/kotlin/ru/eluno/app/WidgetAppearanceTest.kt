package ru.eluno.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetAppearanceTest {
    @Test fun `disabling background makes it completely transparent regardless of saved opacity`() {
        assertEquals(0f, widgetBackgroundAlpha(false, 20), 0f)
        assertEquals(0f, widgetBackgroundAlpha(false, 100), 0f)
    }

    @Test fun `enabled background retains a visible minimum and supports full opacity`() {
        assertEquals(0.2f, widgetBackgroundAlpha(true, 20), 0f)
        assertEquals(0.6f, widgetBackgroundAlpha(true, 60), 0f)
        assertEquals(1f, widgetBackgroundAlpha(true, 100), 0f)
        assertEquals(0.2f, widgetBackgroundAlpha(true, 0), 0f)
        assertEquals(1f, widgetBackgroundAlpha(true, 200), 0f)
    }
    @Test fun `legacy opacity values map to one of three positions`() {
        assertEquals(20, normalizeWidgetOpacity(20))
        assertEquals(20, normalizeWidgetOpacity(39))
        assertEquals(60, normalizeWidgetOpacity(45))
        assertEquals(60, normalizeWidgetOpacity(60))
        assertEquals(100, normalizeWidgetOpacity(99))
        assertEquals(100, normalizeWidgetOpacity(100))
    }

    @Test fun `system colors follow the phone and explicit choices do not`() {
        assertEquals(false, widgetUsesDarkColors(WidgetColorMode.System, false))
        assertEquals(true, widgetUsesDarkColors(WidgetColorMode.System, true))
        assertEquals(false, widgetUsesDarkColors(WidgetColorMode.Light, true))
        assertEquals(true, widgetUsesDarkColors(WidgetColorMode.Dark, false))
        assertEquals(LightWidgetPalette, widgetPalette(WidgetColorMode.Light, true))
        assertEquals(DarkWidgetPalette, widgetPalette(WidgetColorMode.Dark, false))
    }

    @Test fun `unknown saved color mode falls back to system`() {
        assertEquals(WidgetColorMode.System, WidgetColorMode.fromStorage(null))
        assertEquals(WidgetColorMode.System, WidgetColorMode.fromStorage("unknown"))
        assertEquals(WidgetColorMode.Light, WidgetColorMode.fromStorage("Light"))
        assertEquals(WidgetColorMode.Dark, WidgetColorMode.fromStorage("Dark"))
    }

}
