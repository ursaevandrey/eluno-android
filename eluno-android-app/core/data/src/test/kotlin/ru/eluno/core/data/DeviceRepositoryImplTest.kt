package ru.eluno.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.eluno.core.database.*
import ru.eluno.core.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow

class DeviceRepositoryImplTest {
    private val device = Device(DeviceId("matter-0000000000000001-1"), "Лампа", DeviceCategory.Light, DeviceBinding("0000000000000001", 1u), setOf(Capability.OnOff))
    private val port = object : MatterPort {
        override suspend fun read(device: Device) = CommandResult.Success(DeviceSnapshot(device.id))
        override suspend fun execute(device: Device, command: DeviceCommand) = CommandResult.Success(DeviceSnapshot(device.id, ReportedState(isOn = (command as DeviceCommand.SetPower).isOn), Freshness.Fresh, Availability.Reachable, java.time.Instant.now()))
        override fun updates(device: Device) = emptyFlow<DeviceSnapshot>()
    }
    @Test fun `new installation leaves the catalog empty`() = runTest {
        val dao = MemoryDao()
        val repository = DeviceRepositoryImpl(dao, port)
        repository.recoverAfterRestart()
        repository.startSynchronization(backgroundScope)
        assertTrue(repository.observeDevices().first().isEmpty())
        repository.stopSynchronization()
        assertTrue(repository.observeDevices().first().isEmpty())
        assertTrue(dao.commands.isEmpty())
    }

    @Test fun `command preserves reported state while pending and stores confirmation`() = runTest {
        val dao = MemoryDao()
        dao.upsertDevice(device.toEntity())
        val repository = DeviceRepositoryImpl(dao, port)
        val id = device.id

        repository.submit(id, DeviceCommand.SetPower(true)).getOrThrow()

        val snapshot = dao.getSnapshot(id.value)!!
        assertEquals(true, snapshot.isOn)
        assertNull(snapshot.pendingId)
        assertEquals("Confirmed", dao.commands.single().status)
    }

    @Test fun `rename is persistent and blank name is rejected`() = runTest {
        val dao = MemoryDao()
        dao.upsertDevice(device.toEntity())
        val repository = DeviceRepositoryImpl(dao, port)
        val id = device.id
        repository.rename(id, "Лампа у окна").getOrThrow()
        assertEquals("Лампа у окна", dao.getDevice(id.value)?.name)
        assertTrue(repository.rename(id, "  ").isFailure)
    }

    @Test fun `icon change is persistent`() = runTest {
        val dao = MemoryDao()
        dao.upsertDevice(device.toEntity())
        val repository = DeviceRepositoryImpl(dao, port)

        repository.setIcon(device.id, DeviceIcon.LightStrip).getOrThrow()

        assertEquals(DeviceIcon.LightStrip.name, dao.getDevice(device.id.value)?.icon)
        assertEquals(DeviceIcon.LightStrip, repository.observeDevice(device.id).first()?.icon)
    }
}

internal class MemoryDao : ElunoDao {
    private val devices = linkedMapOf<String, DeviceEntity>()
    private val snapshots = linkedMapOf<String, SnapshotEntity>()
    val commands = mutableListOf<CommandEntity>()
    private val widgetBindings = mutableMapOf<Int, WidgetBindingEntity>()
    override fun observeWidgetBindings(): Flow<List<WidgetBindingEntity>> = MutableStateFlow(widgetBindings.values.toList())
    override fun observeWidgetBinding(id: Int): Flow<WidgetBindingEntity?> = MutableStateFlow(widgetBindings[id])
    override suspend fun getWidgetBinding(id: Int) = widgetBindings[id]
    override suspend fun upsertWidgetBinding(binding: WidgetBindingEntity) { widgetBindings[binding.appWidgetId] = binding }
    override suspend fun deleteWidgetBinding(id: Int) { widgetBindings.remove(id) }
    private val deviceFlow = MutableStateFlow<List<DeviceEntity>>(emptyList())
    private val snapshotFlows = mutableMapOf<String, MutableStateFlow<SnapshotEntity?>>()
    override fun observeDevices(): Flow<List<DeviceEntity>> = deviceFlow
    override fun observeDevice(id: String): Flow<DeviceEntity?> = MutableStateFlow(devices[id])
    override suspend fun getDevice(id: String) = devices[id]
    override fun observeSnapshot(id: String): Flow<SnapshotEntity?> = snapshotFlows.getOrPut(id) { MutableStateFlow(snapshots[id]) }
    override suspend fun getSnapshot(id: String) = snapshots[id]
    suspend fun deviceCount() = devices.size
    override suspend fun upsertDevice(entity: DeviceEntity) { devices[entity.id] = entity; deviceFlow.value = devices.values.toList() }
    override suspend fun upsertSnapshot(entity: SnapshotEntity) { snapshots[entity.deviceId] = entity; snapshotFlows.getOrPut(entity.deviceId) { MutableStateFlow(null) }.value = entity }
    override suspend fun pruneCommands(before: Long, keep: Int): Int = 0
    override suspend fun upsertCommand(entity: CommandEntity) { commands.removeAll { it.id == entity.id }; commands += entity }
    override suspend fun rename(id: String, name: String): Int { val old = devices[id] ?: return 0; devices[id] = old.copy(name = name); deviceFlow.value = devices.values.toList(); return 1 }
    override suspend fun setIcon(id: String, icon: String): Int { val old = devices[id] ?: return 0; devices[id] = old.copy(icon = icon); deviceFlow.value = devices.values.toList(); return 1 }
}
