package ru.eluno.core.matter

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eluno.core.domain.*
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionTest {
    private val device = Device(DeviceId("lamp"), "Лампа", DeviceCategory.Light, DeviceBinding("0000000000000001", 1u), setOf(Capability.OnOff))
    @Test fun `ACK without matching read never confirms and never resends command`() = runTest {
        val transport = TestTransport()
        val port = ConnectedMatterPort(transport, flowOf(LocalNetwork(true)))
        val result = port.execute(device, DeviceCommand.SetPower(true))
        assertTrue(result is CommandResult.Failure)
        assertEquals(1, transport.invokes)
        assertTrue(transport.reads > 1)
    }
    @Test fun `read after invocation confirms actual state`() = runTest {
        val transport = TestTransport().apply { applyCommand = true }
        val result = ConnectedMatterPort(transport, flowOf(LocalNetwork(true))).execute(device, DeviceCommand.SetPower(true))
        assertTrue(result is CommandResult.Success)
        assertEquals(true, (result as CommandResult.Success).snapshot.reported.isOn)
    }
    @Test fun `network loss preserves values and restores subscription without replay`() = runTest {
        val network = MutableStateFlow(LocalNetwork(true, 1))
        val transport = TestTransport().apply { state = ReportedState(true) }
        val reports = mutableListOf<DeviceSnapshot>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ConnectedMatterPort(transport, network, { Instant.ofEpochMilli(testScheduler.currentTime) }).updates(device).toList(reports)
        }
        runCurrent()
        assertEquals(1, transport.active)
        network.value = LocalNetwork(false, 2); runCurrent()
        assertEquals(0, transport.active)
        assertEquals(Availability.Unreachable, reports.last().availability)
        assertEquals(Freshness.Stale, reports.last().freshness)
        assertEquals(true, reports.last().reported.isOn)
        network.value = LocalNetwork(true, 3); runCurrent()
        assertEquals(1, transport.active)
        assertEquals(Availability.Reachable, reports.last().availability)
        assertEquals(0, transport.invokes)
        assertEquals(1, transport.reconnections)
        job.cancelAndJoin()
        assertEquals(0, transport.active)
    }
    @Test fun `failed discovery retries with backoff and can be cancelled`() = runTest {
        val transport = TestTransport().apply { failReads = true }
        val delays = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ConnectedMatterPort(transport, flowOf(LocalNetwork(true)), retryDelay = { delays += it; 1000L shl it }).updates(device).collect()
        }
        runCurrent(); assertEquals(1, transport.reads)
        advanceTimeBy(1000); runCurrent(); assertEquals(2, transport.reads)
        advanceTimeBy(2000); runCurrent(); assertEquals(3, transport.reads)
        assertEquals(listOf(0,1,2), delays)
        job.cancelAndJoin()
        advanceTimeBy(10000)
        assertEquals(3, transport.reads)
    }
    @Test fun `lost subscription retries and releases the old stream`() = runTest {
        val transport = TestTransport()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ConnectedMatterPort(transport, flowOf(LocalNetwork(true)), retryDelay = { 1000 }).updates(device).collect()
        }
        runCurrent()
        transport.failure.emit(Unit); runCurrent()
        assertEquals(0, transport.active)
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, transport.active)
        assertEquals(2, transport.subscriptions)
        job.cancelAndJoin()
    }
    @Test fun `silent subscription is refreshed every fifteen seconds and stops with collector`() = runTest {
        val transport = TestTransport()
        val reports = mutableListOf<DeviceSnapshot>()
        val port = ConnectedMatterPort(transport, flowOf(LocalNetwork(true)), { Instant.ofEpochMilli(testScheduler.currentTime) })
        val job = backgroundScope.launch { port.updates(device).toList(reports) }
        runCurrent()
        assertEquals(1, transport.reads)
        transport.state = ReportedState(true)
        advanceTimeBy(14_999); runCurrent()
        assertEquals(false, reports.last().reported.isOn)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, transport.reads)
        assertEquals(true, reports.last().reported.isOn)
        assertEquals(Freshness.Fresh, reports.last().freshness)
        assertEquals(Instant.ofEpochMilli(15_000), reports.last().lastConfirmedAt)
        advanceTimeBy(90_000); runCurrent()
        assertEquals(8, transport.reads)
        assertEquals(1, transport.subscriptions)
        assertEquals(0, transport.invokes)
        job.cancelAndJoin()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(8, transport.reads)
        assertEquals(0, transport.active)
        val resumed = backgroundScope.launch { port.updates(device).collect() }
        runCurrent()
        assertEquals(9, transport.reads)
        resumed.cancelAndJoin()
    }
    @Test fun `periodic read failure marks stale then reconnects without sending commands`() = runTest {
        val transport = TestTransport().apply { state = ReportedState(true) }
        val reports = mutableListOf<DeviceSnapshot>()
        val job = backgroundScope.launch {
            ConnectedMatterPort(transport, flowOf(LocalNetwork(true)), retryDelay = { 1000 }).updates(device).toList(reports)
        }
        runCurrent()
        transport.failReads = true
        advanceTimeBy(15_000); runCurrent()
        assertEquals(Freshness.Stale, reports.last().freshness)
        assertEquals(true, reports.last().reported.isOn)
        assertEquals(Availability.Unreachable, reports.last().availability)
        assertEquals(0, transport.active)
        transport.failReads = false
        advanceTimeBy(1000); runCurrent()
        assertEquals(Freshness.Fresh, reports.last().freshness)
        assertEquals(1, transport.reconnections)
        assertEquals(1, transport.active)
        assertEquals(0, transport.invokes)
        job.cancelAndJoin()
    }
    @Test fun `slow periodic read is not overlapped and cancellation releases subscription`() = runTest {
        val transport = TestTransport()
        val job = backgroundScope.launch {
            ConnectedMatterPort(transport, flowOf(LocalNetwork(true))).updates(device).collect()
        }
        runCurrent()
        transport.readGate = CompletableDeferred()
        advanceTimeBy(15_000); runCurrent()
        assertEquals(2, transport.reads)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(2, transport.reads)
        assertEquals(1, transport.active)
        job.cancelAndJoin()
        assertEquals(0, transport.active)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, transport.reads)
    }
    @Test fun `timed out periodic read restarts the whole connection`() = runTest {
        val transport = TestTransport()
        val reports = mutableListOf<DeviceSnapshot>()
        val job = backgroundScope.launch {
            ConnectedMatterPort(transport, flowOf(LocalNetwork(true)), retryDelay = { 1000 }).updates(device).toList(reports)
        }
        runCurrent()
        transport.readGate = CompletableDeferred()
        advanceTimeBy(40_000); runCurrent()
        assertEquals(Freshness.Stale, reports.last().freshness)
        assertEquals(0, transport.active)
        transport.readGate!!.complete(Unit)
        advanceTimeBy(1000); runCurrent()
        assertEquals(Freshness.Fresh, reports.last().freshness)
        assertEquals(1, transport.reconnections)
        assertEquals(1, transport.active)
        assertEquals(0, transport.invokes)
        job.cancelAndJoin()
    }
    @Test fun `cancellation escapes command instead of turning into failure`() = runTest {
        val transport = TestTransport().apply { suspendInvoke = true }
        val job = launch { ConnectedMatterPort(transport, flowOf(LocalNetwork(true))).execute(device, DeviceCommand.SetPower(true)) }
        runCurrent(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, transport.reads)
        assertEquals(1, transport.invokes)
    }
    @Test fun `caller timeout propagates cancellation`() = runTest {
        val transport = TestTransport().apply { suspendInvoke = true }
        val port = ConnectedMatterPort(transport, flowOf(LocalNetwork(true)))
        val error = runCatching { withTimeout(100) { port.execute(device, DeviceCommand.SetPower(true)) } }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertEquals(1, transport.invokes)
    }
    @Test fun `brightness and temperature tolerate protocol quantization`() {
        assertTrue(DeviceCommand.SetBrightness(33).matches(ReportedState(brightnessPercent = 33)))
        assertTrue(DeviceCommand.SetColorTemperature(3200).matches(ReportedState(colorTemperatureKelvin = 3195)))
        assertFalse(DeviceCommand.SetColorTemperature(3200).matches(ReportedState(colorTemperatureKelvin = 4000)))
        assertFalse(DeviceCommand.SetPower(false).matches(ReportedState()))
    }
}

private class TestTransport : MatterTransport {
    var state = ReportedState(false)
    var applyCommand = false
    var failReads = false
    var suspendInvoke = false
    var reads = 0
    var invokes = 0
    var active = 0
    var subscriptions = 0
    var reconnections = 0
    var readGate: CompletableDeferred<Unit>? = null
    override suspend fun reconnect(device: Device) { reconnections++ }
    val failure = MutableSharedFlow<Unit>()
    override suspend fun read(device: Device): MatterObservation { reads++; readGate?.await(); if (failReads) error("offline"); return MatterObservation(state) }
    override suspend fun invoke(device: Device, command: DeviceCommand) {
        invokes++
        if (suspendInvoke) awaitCancellation()
        if (applyCommand && command is DeviceCommand.SetPower) state = state.copy(isOn = command.isOn)
    }
    override fun subscribe(device: Device) = flow {
        active++; subscriptions++
        try { emit(MatterObservation(state)); failure.first(); error("subscription lost") }
        finally { active-- }
    }
}
