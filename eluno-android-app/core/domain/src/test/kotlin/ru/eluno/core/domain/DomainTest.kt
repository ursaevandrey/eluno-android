package ru.eluno.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTest {
    private val outlet = Device(
        DeviceId("outlet"), "Розетка", DeviceCategory.Unknown,
        DeviceBinding("0000000000000001", 1u), setOf(Capability.OnOff),
    )

    @Test fun `manual formatting is removed without corrupting QR base38 characters`() {
        assertEquals("34970112332", CommissioningRequest("3497-011-2332", "Лампа").setupCode)
        assertEquals("MT:A-.B", CommissioningRequest(" MT:A-.B ", "Лампа").setupCode)
    }

    @Test fun `category does not grant capabilities`() {
        val lightWithoutLevel = outlet.copy(category = DeviceCategory.Light)
        assertTrue(lightWithoutLevel.validate(DeviceCommand.SetBrightness(50)) is DeviceError.UnsupportedCapability)
    }

    @Test fun `supported command is accepted regardless of category`() {
        assertNull(outlet.validate(DeviceCommand.SetPower(true)))
    }

    @Test fun `capability limits are enforced`() {
        val device = outlet.copy(capabilities = setOf(Capability.LevelControl(10, 80)))
        assertTrue(device.validate(DeviceCommand.SetBrightness(5)) is DeviceError.InvalidValue)
        assertNull(device.validate(DeviceCommand.SetBrightness(80)))
    }
}
