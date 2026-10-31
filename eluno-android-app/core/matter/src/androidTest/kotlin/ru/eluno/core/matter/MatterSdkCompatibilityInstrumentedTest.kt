package ru.eluno.core.matter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import matter.jsontlv.fromJsonString
import matter.jsontlv.toJsonString
import matter.tlv.TlvReader
import matter.tlv.TlvWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MatterSdkCompatibilityInstrumentedTest {
    @Test fun updatedBindingsLoadJniAndProductionTrustRootsOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "ru.eluno.core.matter.test")
        val controller = ChipRuntime(context).controller
        try {
            assertTrue(controller.fabricIndex > 0)
            assertTrue(context.assets.list("paa").orEmpty().isNotEmpty())
            val json = """{"0:BOOL":true,"1:UINT":"18446744073709551615","2:BYTES":"AQID"}"""
            assertEquals(json, TlvReader(TlvWriter().fromJsonString(json)).toJsonString())
        } finally {
            controller.close()
        }
    }
}
