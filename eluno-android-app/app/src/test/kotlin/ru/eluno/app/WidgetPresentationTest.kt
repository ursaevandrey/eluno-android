package ru.eluno.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import ru.eluno.core.domain.*
import ru.eluno.core.database.WidgetBindingEntity

class WidgetPresentationTest {
    private val id = DeviceId("light")
    private val device = Device(id, "Лампа", DeviceCategory.Light, DeviceBinding("1", 1u), setOf(Capability.OnOff))
    private class FakeRepository(val device: Flow<Device?>, val snapshot: Flow<DeviceSnapshot?>) : DeviceRepository {
        override fun observeDevices(): Flow<List<Device>> = flowOf(emptyList())
        override fun observeDevice(id: DeviceId) = device
        override fun observeSnapshot(id: DeviceId) = snapshot
        override suspend fun refresh(id: DeviceId) = Result.success(Unit)
        override suspend fun submit(id: DeviceId, command: DeviceCommand) = Result.success(CommandId("id"))
        override suspend fun rename(id: DeviceId, name: String) = Result.success(Unit)
        override suspend fun setIcon(id: DeviceId, icon: DeviceIcon) = Result.success(Unit)
    }

    @Test fun `stored binding waits for device and state before first presentation`() = runBlocking {
        val deviceLoaded = CompletableDeferred<Device?>()
        val stateLoaded = CompletableDeferred<DeviceSnapshot?>()
        val repository = FakeRepository(flow { emit(deviceLoaded.await()) }, flow { emit(stateLoaded.await()) })
        val first = async { observeWidgetPresentation(flowOf(WidgetBindingEntity(1, id.value)), repository).first() }
        yield()
        assertFalse(first.isCompleted)
        deviceLoaded.complete(device)
        yield()
        assertFalse(first.isCompleted)
        stateLoaded.complete(null)
        assertEquals(WidgetPresentation(id, device, null), first.await())
    }

    @Test fun `a missing binding is still shown after it is actually loaded`() = runBlocking {
        val repository = FakeRepository(flowOf(device), flowOf(null))
        assertEquals(WidgetPresentation(null, null, null), observeWidgetPresentation(flowOf(null), repository).first())
    }

    @Test fun `binding change replaces the displayed device`() = runBlocking {
        val bindings = MutableStateFlow<WidgetBindingEntity?>(null)
        val repository = FakeRepository(flowOf(device), flowOf(null))
        val initialSeen = CompletableDeferred<Unit>()
        val seen = async { observeWidgetPresentation(bindings, repository).onEach { initialSeen.complete(Unit) }.take(2).toList() }
        initialSeen.await()
        bindings.value = WidgetBindingEntity(1, id.value)
        val values = seen.await()
        assertEquals(null, values[0].device)
        assertEquals(device, values[1].device)
    }
    @Test fun `changing appearance updates the same widget without losing its device`() = runBlocking {
        val bindings = MutableStateFlow(WidgetBindingEntity(1, id.value))
        val repository = FakeRepository(flowOf(device), flowOf(null))
        val initialSeen = CompletableDeferred<Unit>()
        val seen = async { observeWidgetPresentation(bindings, repository).onEach { initialSeen.complete(Unit) }.take(2).toList() }
        initialSeen.await()
        bindings.value = WidgetBindingEntity(1, id.value, backgroundEnabled = false, backgroundOpacity = 45, colorMode = "Dark")
        val values = seen.await()
        assertEquals(device, values[1].device)
        assertEquals(false, values[1].backgroundEnabled)
        assertEquals(45, values[1].backgroundOpacity)
        assertEquals(WidgetColorMode.Dark, values[1].colorMode)
    }

}
