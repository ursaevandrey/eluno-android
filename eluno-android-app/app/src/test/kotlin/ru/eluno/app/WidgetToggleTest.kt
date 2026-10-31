package ru.eluno.app

import ru.eluno.feature.widgets.*

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.eluno.core.domain.*

class WidgetToggleTest {
    private val id = DeviceId("light")
    private class FakeRepository(var read: Result<Unit>, var snapshot: DeviceSnapshot?) : DeviceRepository {
        val commands = mutableListOf<DeviceCommand>()
        override fun observeDevices(): Flow<List<Device>> = flowOf(emptyList())
        override fun observeDevice(id: DeviceId): Flow<Device?> = flowOf(null)
        override fun observeSnapshot(id: DeviceId) = flowOf(snapshot)
        override suspend fun refresh(id: DeviceId) = read
        override suspend fun submit(id: DeviceId, command: DeviceCommand): Result<CommandId> {
            commands += command
            return Result.success(CommandId("command"))
        }
        override suspend fun togglePower(id: DeviceId, stillAllowed: suspend () -> Boolean): Result<CommandId> {
            if (read.isFailure) return Result.failure(read.exceptionOrNull()!!)
            val state = snapshot
            val on = state?.reported?.isOn
            if (state?.freshness != Freshness.Fresh || on == null || !stillAllowed()) return Result.failure(IllegalStateException())
            return submit(id, DeviceCommand.SetPower(!on))
        }
        override suspend fun rename(id: DeviceId, name: String) = Result.success(Unit)
        override suspend fun setIcon(id: DeviceId, icon: DeviceIcon) = Result.success(Unit)
    }

    @Test fun `switches the state returned by a successful read`() = runBlocking {
        val repository = FakeRepository(Result.success(Unit), DeviceSnapshot(id, ReportedState(isOn = true), Freshness.Fresh))
        assertTrue(executeWidgetToggle(repository, id).isSuccess)
        assertEquals(listOf(DeviceCommand.SetPower(false)), repository.commands)
    }

    @Test fun `does not send a command if read fails`() = runBlocking {
        val repository = FakeRepository(Result.failure(IllegalStateException("offline")), DeviceSnapshot(id, ReportedState(isOn = true), Freshness.Stale))
        assertTrue(executeWidgetToggle(repository, id).isFailure)
        assertTrue(repository.commands.isEmpty())
    }

    @Test fun `does not invert unknown or stale state`() = runBlocking {
        for (snapshot in listOf(DeviceSnapshot(id, ReportedState(isOn = false), Freshness.Stale), DeviceSnapshot(id, ReportedState(), Freshness.Fresh))) {
            val repository = FakeRepository(Result.success(Unit), snapshot)
            assertTrue(executeWidgetToggle(repository, id).isFailure)
            assertTrue(repository.commands.isEmpty())
        }
    }
    @Test fun `does not send if widget binding changed during read`() = runBlocking {
        val repository = FakeRepository(Result.success(Unit), DeviceSnapshot(id, ReportedState(isOn = true), Freshness.Fresh))
        assertTrue(executeWidgetToggle(repository, id) { false }.isFailure)
        assertTrue(repository.commands.isEmpty())
    }

}
