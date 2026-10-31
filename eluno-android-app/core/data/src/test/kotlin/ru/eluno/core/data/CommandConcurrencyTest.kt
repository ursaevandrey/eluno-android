package ru.eluno.core.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eluno.core.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommandConcurrencyTest {
    private val device = Device(DeviceId("lamp"), "Лампа", DeviceCategory.Light, DeviceBinding("1", 1u), setOf(Capability.OnOff))
    private inner class Port : MatterPort {
        var power = false
        var failRead = false
        var readGate: CompletableDeferred<Unit>? = null
        val commands = mutableListOf<DeviceCommand>()
        val reports = MutableSharedFlow<DeviceSnapshot>()
        fun snapshot(sequence: Long = 0) = DeviceSnapshot(device.id, ReportedState(power), Freshness.Fresh, Availability.Reachable, java.time.Instant.now(), sourceSequence = sequence)
        override suspend fun read(device: Device): CommandResult {
            readGate?.await()
            return if (failRead) CommandResult.Failure(DeviceError.Unavailable) else CommandResult.Success(snapshot())
        }
        override suspend fun execute(device: Device, command: DeviceCommand): CommandResult {
            commands += command
            power = (command as DeviceCommand.SetPower).isOn
            return CommandResult.Success(snapshot())
        }
        override fun updates(device: Device) = reports
    }
    @Test fun `widget read and command stay together while application command waits`() = runTest {
        val dao = MemoryDao().apply { upsertDevice(device.toEntity()) }
        val port = Port().apply { readGate = CompletableDeferred() }
        val repository = DeviceRepositoryImpl(dao, port)
        val toggle = async { repository.togglePower(device.id) }
        runCurrent()
        val app = async { repository.submit(device.id, DeviceCommand.SetPower(false)) }
        runCurrent()
        assertTrue(port.commands.isEmpty())
        port.readGate!!.complete(Unit)
        toggle.await().getOrThrow(); app.await().getOrThrow()
        assertEquals(listOf(DeviceCommand.SetPower(true), DeviceCommand.SetPower(false)), port.commands)
    }
    @Test fun `failed read or changed binding never dispatches a toggle`() = runTest {
        val dao = MemoryDao().apply { upsertDevice(device.toEntity()) }
        val port = Port().apply { failRead = true }
        val repository = DeviceRepositoryImpl(dao, port)
        assertTrue(repository.togglePower(device.id).isFailure)
        port.failRead = false
        port.readGate = CompletableDeferred()
        var bound = true
        val toggle = async { repository.togglePower(device.id) { bound } }
        runCurrent(); bound = false; port.readGate!!.complete(Unit)
        assertTrue(toggle.await().isFailure)
        assertTrue(port.commands.isEmpty())
        assertNull(dao.getSnapshot(device.id.value)?.pendingId)
    }
    @Test fun `caller timeout cancels toggle read and does not turn into success`() = runTest {
        val dao = MemoryDao().apply { upsertDevice(device.toEntity()) }
        val port = Port().apply { readGate = CompletableDeferred() }
        val repository = DeviceRepositoryImpl(dao, port)
        val error = runCatching { withTimeout(500) { repository.togglePower(device.id) } }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertTrue(port.commands.isEmpty())
    }
    @Test fun `late queued observation cannot overwrite newer command readback`() = runTest {
        val dao = MemoryDao().apply { upsertDevice(device.toEntity()) }
        val port = Port()
        val repository = DeviceRepositoryImpl(dao, port)
        repository.startSynchronization(backgroundScope); runCurrent()
        port.power = true
        port.reports.emit(port.snapshot(3)); runCurrent()
        port.power = false
        port.reports.emit(port.snapshot(2)); runCurrent()
        assertEquals(true, dao.getSnapshot(device.id.value)?.isOn)
        repository.stopSynchronization()
    }
}
