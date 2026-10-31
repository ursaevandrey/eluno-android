package ru.eluno.feature.commissioning

import org.junit.Assert.*
import org.junit.Test

class CurrentWifiNetworkTest {
    @Test fun `Android unavailable SSID is not used as a network name`() {
        listOf(null, "<unknown ssid>", "", "\"\"").forEach { assertNull(wifiNetworkName(it)) }
    }

    @Test fun `SSID keeps significant spaces unicode and embedded quotes`() {
        assertEquals(" Дом \"Wi-Fi\" ", wifiNetworkName("\" Дом \"Wi-Fi\" \""))
        assertEquals("Home", wifiNetworkName("\"Home\""))
        assertEquals("Home", wifiNetworkName("Home"))
    }
}
