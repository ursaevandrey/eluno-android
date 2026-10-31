package ru.eluno.core.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eluno.core.database.*
import ru.eluno.core.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class ElunoAuditRegressionTest {
    private val device = Device(DeviceId("audit"), "Audit", DeviceCategory.Light, DeviceBinding("0000000000000001", 1u), setOf(Capability.OnOff))
    private fun state() = CommandResult.Success(DeviceSnapshot(device.id, ReportedState(true)))

    @Test fun `cancellation after pending commit must clear pending`() = runTest {
        val base = MemoryDao().apply { upsertDevice(device.toEntity()) }
        val dao = object : ElunoDao by base {
            override suspend fun saveCommandState(command: CommandEntity, snapshot: SnapshotEntity) {
                base.saveCommandState(command, snapshot)
                if (command.status == "Running") {
                    currentCoroutineContext().cancel()
                    yield() // cancellation delivered after durable commit, before caller resumes
                }
            }
        }
        val port = object : MatterPort {
            override suspend fun read(device: Device) = state()
            override suspend fun execute(device: Device, command: DeviceCommand) = state()
            override fun updates(device: Device) = emptyFlow<DeviceSnapshot>()
        }
        val repo = DeviceRepositoryImpl(dao, port)
        launch { repo.submit(device.id, DeviceCommand.SetPower(true)) }.join()
        assertNull("Pending must not survive a cancelled operation in a live process", base.getSnapshot(device.id.value)?.pendingId)
    }

    @Test fun `request waiting over fifteen seconds must expire before dispatch`() = runTest {
        val base = MemoryDao().apply { upsertDevice(device.toEntity()) }
        val sent = mutableListOf<Long>()
        val port = object : MatterPort {
            override suspend fun read(device: Device) = state()
            override suspend fun execute(device: Device, command: DeviceCommand): CommandResult {
                sent += testScheduler.currentTime
                if (sent.size == 1) delay(20_000)
                return state()
            }
            override fun updates(device: Device) = emptyFlow<DeviceSnapshot>()
        }
        val repo = DeviceRepositoryImpl(base, port)
        launch { repo.submit(device.id, DeviceCommand.SetPower(true)) }
        runCurrent()
        launch { repo.submit(device.id, DeviceCommand.SetPower(false)) }
        advanceUntilIdle()
        assertEquals("Second request exceeded architectural deadline before dispatch: $sent", 1, sent.size)
    }
}
