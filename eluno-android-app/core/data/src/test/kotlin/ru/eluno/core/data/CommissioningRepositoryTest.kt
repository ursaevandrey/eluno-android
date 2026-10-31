package ru.eluno.core.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eluno.core.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommissioningRepositoryTest {
    @Test fun `only a fully inspected device is persisted`() = runTest {
        val dao = MemoryDao()
        val port = CommissioningFake()
        val repo = CommissioningRepositoryImpl(dao, port, StandardTestDispatcher(testScheduler))
        repo.commission(CommissioningRequest("test-code", "Лампа"))
        assertEquals(CommissioningStage.Ready, repo.state.value.stage)
        assertEquals(1, dao.deviceCount())
        assertEquals(1, port.pairs)
    }
    @Test fun `selected icon is persisted with commissioned device`() = runTest {
        val dao = MemoryDao()
        val repo = CommissioningRepositoryImpl(dao, CommissioningFake(), StandardTestDispatcher(testScheduler))
        repo.commission(CommissioningRequest("test-code", "Лента", icon = DeviceIcon.LightStrip))
        val saved = dao.observeDevices().first().single()
        assertEquals(DeviceIcon.LightStrip.name, saved.icon)
    }
    @Test fun `failed pairing leaves the catalog empty`() = runTest {
        val dao = MemoryDao()
        val repo = CommissioningRepositoryImpl(dao, CommissioningFake().apply { failPairing = true }, StandardTestDispatcher(testScheduler))
        repo.commission(CommissioningRequest("test-code", "Лампа"))
        assertEquals(CommissioningStage.Failed, repo.state.value.stage)
        assertEquals(0, dao.deviceCount())
    }
    @Test fun `failed inspection persists no draft and a fresh explicit attempt can succeed`() = runTest {
        val dao = MemoryDao()
        val port = CommissioningFake().apply { failInspection = true; existingNode = "0000000000000007" }
        val repo = CommissioningRepositoryImpl(dao, port, StandardTestDispatcher(testScheduler))
        repo.commission(CommissioningRequest("test-code", "Лампа"))
        assertEquals(CommissioningStage.Failed, repo.state.value.stage)
        assertEquals(0, dao.deviceCount())
        port.failInspection = false
        repo.commission(CommissioningRequest("new-code", "Лампа"))
        assertEquals(CommissioningStage.Ready, repo.state.value.stage)
        assertEquals(1, dao.deviceCount())
    }
    @Test fun `matching fabric is inspected using actual node rather than new proposed id`() = runTest {
        val dao = MemoryDao()
        val actual = "0000000000000007"
        val port = CommissioningFake().apply { existingNode = actual }
        val repo = CommissioningRepositoryImpl(dao, port, StandardTestDispatcher(testScheduler))
        repo.commission(CommissioningRequest("test-code", "Лампа"))
        assertEquals(listOf(actual), port.inspected)
        assertEquals(actual, dao.observeDevices().first().single().nodeIdHex)
    }
    @Test fun `adding an existing node again preserves identity and user name`() = runTest {
        val dao = MemoryDao()
        val port = CommissioningFake().apply { existingNode = "0000000000000007" }
        val repo = CommissioningRepositoryImpl(dao, port, StandardTestDispatcher(testScheduler))
        repo.commission(CommissioningRequest("test-code", "Лампа"))
        val id = dao.observeDevices().first().single().id
        dao.rename(id, "Моё имя")
        repo.commission(CommissioningRequest("new-code", "Другое имя"))
        assertEquals(1, dao.deviceCount())
        assertEquals("Моё имя", dao.getDevice(id)!!.name)
    }
    @Test fun `validation failure creates no device and secrets are redacted`() = runTest {
        val dao = MemoryDao()
        val port = CommissioningFake().apply { invalid = true }
        val repo = CommissioningRepositoryImpl(dao, port, StandardTestDispatcher(testScheduler))
        val request = CommissioningRequest("secret-code", "Лампа", "ssid", "secret-password")
        repo.commission(request)
        assertEquals(0, port.pairs)
        assertEquals(0, dao.deviceCount())
        assertFalse(request.toString().contains("secret"))
    }
    @Test fun `cancelling an attempt persists no device and unlocks next attempt`() = runTest {
        val dao = MemoryDao()
        val port = CommissioningFake().apply { waitInPairing = true }
        val repo = CommissioningRepositoryImpl(dao, port, StandardTestDispatcher(testScheduler))
        val job = launch { repo.commission(CommissioningRequest("test-code", "Лампа")) }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(CommissioningStage.Cancelled, repo.state.value.stage)
        assertEquals(0, dao.deviceCount())
        port.waitInPairing = false
        repo.commission(CommissioningRequest("new-code", "Лампа"))
        assertEquals(CommissioningStage.Ready, repo.state.value.stage)
    }
}

private class CommissioningFake : CommissioningPort {
    var pairs = 0
    var invalid = false
    var failPairing = false
    var failInspection = false
    var waitInPairing = false
    var existingNode: String? = null
    val inspected = mutableListOf<String>()
    override fun validate(request: CommissioningRequest) { require(!invalid) { "invalid" } }
    override suspend fun pair(nodeIdHex: String, request: CommissioningRequest, onStage: (CommissioningStage) -> Unit): String {
        pairs++
        if (waitInPairing) awaitCancellation()
        check(!failPairing) { "pairing failed" }
        return existingNode ?: nodeIdHex
    }
    override suspend fun inspect(nodeIdHex: String, name: String): List<Device> {
        inspected += nodeIdHex
        check(!failInspection) { "offline" }
        return listOf(Device(DeviceId("matter-$nodeIdHex-1"), name, DeviceCategory.Outlet, DeviceBinding(nodeIdHex, 1u), setOf(Capability.OnOff)))
    }
}
