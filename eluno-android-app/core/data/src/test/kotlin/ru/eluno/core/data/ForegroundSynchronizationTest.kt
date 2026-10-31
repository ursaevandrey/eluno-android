package ru.eluno.core.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSynchronizationTest {
    @Test fun `resume during suspended cleanup waits and retains the new owner`() = runTest {
        var active = 0
        var peak = 0
        var starts = 0
        var stopped = 0
        val cleanup = CompletableDeferred<Unit>()
        val coordinator = ForegroundSynchronization(backgroundScope, {}, {
            active++; starts++; peak = maxOf(peak, active)
        }, { cleanup.await(); active--; stopped++ })
        coordinator.foreground(); runCurrent()
        assertEquals(1, active)
        coordinator.background(); advanceTimeBy(2000); runCurrent()
        coordinator.foreground(); runCurrent()
        assertEquals(1, starts)
        cleanup.complete(Unit); runCurrent()
        assertEquals(2, starts)
        assertEquals(1, active)
        coordinator.background(); advanceTimeBy(2000); runCurrent()
        assertEquals(0, active)
        assertEquals(2, stopped)
        assertEquals(1, peak)
        coordinator.close()
    }
    @Test fun `short background transitions keep one collector and final background stops it`() = runTest {
        var starts = 0
        var stops = 0
        val coordinator = ForegroundSynchronization(backgroundScope, {}, { starts++ }, { stops++ })
        coordinator.foreground(); runCurrent()
        repeat(100) {
            coordinator.background(); runCurrent(); advanceTimeBy(1999)
            coordinator.foreground(); runCurrent()
        }
        assertEquals(1, starts)
        assertEquals(0, stops)
        coordinator.background(); advanceTimeBy(2000); runCurrent()
        assertEquals(1, stops)
        coordinator.close()
    }
}
