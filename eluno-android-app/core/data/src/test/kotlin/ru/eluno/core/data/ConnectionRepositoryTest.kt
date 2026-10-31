package ru.eluno.core.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eluno.core.database.*
import ru.eluno.core.domain.*
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRepositoryTest {
    private val device = Device(DeviceId("real"), "Лампа", DeviceCategory.Light, DeviceBinding("0000000000000001", 1u), setOf(Capability.OnOff))
    private fun report(on: Boolean, timestamp: Long = 1) = DeviceSnapshot(device.id, ReportedState(on), Freshness.Fresh, Availability.Reachable, Instant.ofEpochMilli(timestamp))

    @Test fun `reports are persisted without UI collectors and subscription is unique`() = runTest {
        val dao = MemoryDao().apply { upsertDevice(device.toEntity()) }
        val port = ReportingPort(report(false))
        val repo = DeviceRepositoryImpl(dao, port)
        repo.startSynchronization(backgroundScope)
        repo.startSynchronization(backgroundScope)
        runCurrent()
        port.reports.emit(report(true)); runCurrent()
        assertEquals(true, dao.getSnapshot("real")?.isOn)
        assertEquals(1, port.active)
        repo.stopSynchronization()
        assertEquals(0, port.active)
        assertEquals("Stale", dao.getSnapshot("real")?.freshness)
    }

    @Test fun `subscription and refresh preserve pending while command is running`() = runTest {
        val dao = MemoryDao().apply { upsertDevice(device.toEntity()) }
        val port = ReportingPort(report(false)).apply { gate = CompletableDeferred() }
        val repo = DeviceRepositoryImpl(dao, port)
        repo.startSynchronization(backgroundScope); runCurrent()
        val command = async { repo.submit(device.id, DeviceCommand.SetPower(true)) }
        runCurrent()
        val pending = dao.getSnapshot("real")!!.pendingId
        port.reports.emit(report(false, 2)); runCurrent()
        repo.refresh(device.id)
        assertEquals(pending, dao.getSnapshot("real")!!.pendingId)
        assertNotNull(pending)
        port.gate!!.complete(Unit)
        command.await().getOrThrow()
        assertNull(dao.getSnapshot("real")!!.pendingId)
        assertEquals("Confirmed", dao.commands.single().status)
        repo.stopSynchronization()
    }

    @Test fun `command failure preserves a newer report and returns failure`() = runTest {
        val dao = MemoryDao().apply { upsertDevice(device.toEntity()) }
        val port = ReportingPort(report(false)).apply { gate = CompletableDeferred(); fail = true }
        val repo = DeviceRepositoryImpl(dao, port)
        repo.startSynchronization(backgroundScope); runCurrent()
        val command = async { repo.submit(device.id, DeviceCommand.SetPower(false)) }
        runCurrent()
        port.reports.emit(report(true, 5)); runCurrent()
        port.gate!!.complete(Unit)
        assertTrue(command.await().isFailure)
        assertEquals(true, dao.getSnapshot("real")!!.isOn)
        assertEquals("Stale", dao.getSnapshot("real")!!.freshness)
        assertNull(dao.getSnapshot("real")!!.pendingId)
        assertEquals("Failed", dao.commands.single().status)
        repo.stopSynchronization()
    }

    @Test fun `cancelling command clears pending without replay`() = runTest {
        val dao = MemoryDao().apply { upsertDevice(device.toEntity()) }
        val port = ReportingPort(report(false)).apply { gate = CompletableDeferred() }
        val repo = DeviceRepositoryImpl(dao, port)
        val command = launch { repo.submit(device.id, DeviceCommand.SetPower(true)) }
        runCurrent(); assertNotNull(dao.getSnapshot("real")!!.pendingId)
        command.cancelAndJoin()
        assertNull(dao.getSnapshot("real")!!.pendingId)
        assertEquals("Interrupted", dao.commands.single().status)
        assertEquals(1, port.invokes)
    }

    @Test fun `restart marks cache stale and does not replay pending command`() = runTest {
        val dao = MemoryDao().apply {
            upsertDevice(device.toEntity())
            upsertSnapshot(SnapshotEntity("real", true, null, null, null, "Fresh", "Reachable", 1, "pending", "power", "false", 2, null, null))
        }
        val port = ReportingPort(report(false))
        DeviceRepositoryImpl(dao, port).recoverAfterRestart()
        assertEquals(true, dao.getSnapshot("real")!!.isOn)
        assertNull(dao.getSnapshot("real")!!.pendingId)
        assertEquals("Stale", dao.getSnapshot("real")!!.freshness)
        assertEquals("Unknown", dao.getSnapshot("real")!!.availability)
        assertEquals("Interrupted", dao.commands.single().status)
        assertEquals(0, port.invokes)
    }
}

private class ReportingPort(private val initial: DeviceSnapshot) : MatterPort {
    var gate: CompletableDeferred<Unit>? = null
    var fail = false
    var active = 0
    var invokes = 0
    val reports = MutableSharedFlow<DeviceSnapshot>()
    override suspend fun read(device: Device) = CommandResult.Success(initial)
    override suspend fun execute(device: Device, command: DeviceCommand): CommandResult {
        invokes++; gate?.await()
        return if (fail) CommandResult.Failure(DeviceError.Unavailable) else CommandResult.Success(initial.copy(reported = ReportedState(true), lastConfirmedAt = Instant.ofEpochMilli(10)))
    }
    override fun updates(device: Device) = flow {
        active++
        try { emit(initial); emitAll(reports) } finally { active-- }
    }
}
