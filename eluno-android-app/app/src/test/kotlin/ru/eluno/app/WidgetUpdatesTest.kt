package ru.eluno.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eluno.core.domain.*
import ru.eluno.feature.widgets.*
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdatesTest {
    private class Repository : DeviceRepository {
        var observations = 0
        val device = Device(DeviceId("lamp"), "Лампа", DeviceCategory.Light, DeviceBinding("1", 1u), setOf(Capability.OnOff))
        val snapshot = MutableStateFlow<DeviceSnapshot?>(DeviceSnapshot(device.id))
        override fun observeDevices() = flowOf(listOf(device))
        override fun observeDevice(id: DeviceId) = flow { observations++; emit(device) }
        override fun observeSnapshot(id: DeviceId) = snapshot
        override suspend fun refresh(id: DeviceId) = Result.success(Unit)
        override suspend fun submit(id: DeviceId, command: DeviceCommand) = Result.success(CommandId("id"))
        override suspend fun togglePower(id: DeviceId, stillAllowed: suspend () -> Boolean) = Result.success(CommandId("id"))
        override suspend fun rename(id: DeviceId, name: String) = Result.success(Unit)
        override suspend fun setIcon(id: DeviceId, icon: DeviceIcon) = Result.success(Unit)
    }
    @Test fun `ten minutes of timestamp updates cause no widget refresh and pending targets bindings only`() = runTest {
        val repository = Repository()
        val bindings = MutableStateFlow(listOf(WidgetBinding(11, repository.device.id), WidgetBinding(12, repository.device.id)))
        val changes = mutableListOf<Set<Int>>()
        val job = backgroundScope.launch { observeWidgetUpdates(bindings, repository).toList(changes) }
        runCurrent()
        assertEquals(listOf(setOf(11,12)), changes)
        repeat(40) { step ->
            advanceTimeBy(15_000)
            repository.snapshot.value = DeviceSnapshot(repository.device.id, ReportedState(step % 2 == 0), Freshness.Fresh, Availability.Reachable, Instant.ofEpochMilli(currentTime))
            runCurrent()
        }
        assertEquals(1, changes.size)
        repository.snapshot.value = repository.snapshot.value!!.copy(pendingCommand = PendingCommand(CommandId("c"), DeviceCommand.SetPower(true), Instant.EPOCH))
        runCurrent(); assertEquals(setOf(11,12), changes.last()); assertEquals(2, changes.size)
        bindings.value = listOf(bindings.value[0].copy(backgroundEnabled = false), bindings.value[1])
        runCurrent(); assertEquals(setOf(11), changes.last())
        job.cancelAndJoin()
    }
    @Test fun `no device observers exist without configured widgets`() = runTest {
        val repository = Repository()
        val job = backgroundScope.launch { observeWidgetUpdates(flowOf(emptyList()), repository).collect() }
        runCurrent(); assertEquals(0, repository.observations)
        job.cancelAndJoin()
    }
}
