package ru.eluno.core.matter

import chip.devicecontroller.ChipDeviceControllerException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PairingAttemptTest {
    @Test fun `successful commissioning does not stop an already released device`() = runTest {
        var cleared = false
        val result = pairingAttempt(
            stop = { throw ChipDeviceControllerException(0x33, "Invalid device descriptor") },
            clearListener = { cleared = true },
        ) { "commissioned" }
        assertEquals("commissioned", result)
        assertTrue(cleared)
    }

    @Test fun `cleanup error preserves original pairing failure and clears listener`() = runTest {
        val original = IllegalStateException("Pairing failed")
        val cleanup = ChipDeviceControllerException(0x33, "Invalid device descriptor")
        var cleared = false
        val error = runCatching {
            pairingAttempt(stop = { throw cleanup }, clearListener = { cleared = true }) { throw original }
        }.exceptionOrNull()
        assertSame(original, error)
        assertSame(cleanup, error!!.suppressed.single())
        assertTrue(cleared)
    }

    @Test fun `timeout survives native cleanup failure`() = runTest {
        var cleared = false
        val error = runCatching {
            pairingAttempt(
                stop = { throw ChipDeviceControllerException(0x33, "Invalid device descriptor") },
                clearListener = { cleared = true },
            ) { withTimeout(10) { awaitCancellation() } }
        }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertTrue(cleared)
    }

    @Test fun `explicit cancellation stops pairing and clears listener without changing cancellation`() = runTest {
        val cancelled = CancellationException("cancelled")
        var stopped = false
        var cleared = false
        val error = runCatching {
            pairingAttempt(stop = { stopped = true }, clearListener = { cleared = true }) { throw cancelled }
        }.exceptionOrNull()
        assertSame(cancelled, error)
        assertTrue(stopped)
        assertTrue(cleared)
    }
}
