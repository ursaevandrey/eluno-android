package ru.eluno.core.matter

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chip.devicecontroller.ChipDeviceControllerException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs in the isolated library test APK, never in Eluno's user-data sandbox. */
@RunWith(AndroidJUnit4::class)
class FabricStoreInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "ru.eluno.core.matter.test")
    }

    @Test fun keystoreRoundTripAndAssociatedDataRejectSwappedRecords() {
        val store = FabricStore(context)
        val prefs = context.getSharedPreferences("eluno.fabric", Context.MODE_PRIVATE)
        store.set("audit-a", "synthetic-secret-a")
        store.set("audit-b", "synthetic-secret-b")
        val ciphertext = prefs.getString("audit-a", null)!!
        assertFalse(ciphertext.contains("synthetic-secret-a"))
        assertEquals("synthetic-secret-a", FabricStore(context).get("audit-a"))
        assertTrue(prefs.edit().putString("audit-b", ciphertext).commit())
        assertTrue(runCatching { store.get("audit-b") }.exceptionOrNull() is java.security.GeneralSecurityException)
        assertEquals(ciphertext, prefs.getString("audit-b", null))
        store.delete("audit-a"); store.delete("audit-b")
    }

    @Test fun nativeIssuerRejectsCorruptStoredKeyWithoutReplacingIt() {
        val store = FabricStore(context)
        val prefs = context.getSharedPreferences("eluno.fabric", Context.MODE_PRIVATE)
        val key = "AndroidDeviceControllerKey"
        // Valid AEAD, invalid SDK base64: exercises the real JNI error path.
        store.set(key, "!!!!")
        val before = prefs.getString(key, null)
        val failure = runCatching { ChipRuntime(context).controller }.exceptionOrNull()
        assertTrue("Native controller must reject corrupt issuer storage", failure is ChipDeviceControllerException)
        assertEquals(0x13L, (failure as ChipDeviceControllerException).errorCode)
        assertEquals(before, prefs.getString(key, null))
        assertEquals("!!!!", store.get(key))
        store.delete(key)
    }
}
