package ru.eluno.core.matter

import chip.devicecontroller.*
import chip.devicecontroller.GetConnectedDeviceCallbackJni.GetConnectedDeviceCallback
import chip.devicecontroller.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import matter.tlv.*
import ru.eluno.core.domain.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

internal class ChipMatterTransport(private val runtime: ChipRuntime) : MatterTransport {
    private val chip get() = runtime.controller

    private suspend fun connect(node: String): Long = suspendCancellableCoroutine { continuation ->
        chip.getConnectedDevicePointer(node.toULong(16).toLong(), object : GetConnectedDeviceCallback {
            override fun onDeviceConnected(devicePointer: Long) {
                continuation.resume(devicePointer) { _, pointer, _ -> chip.releaseConnectedDevicePointer(pointer) }
            }
            override fun onConnectionFailure(nodeId: Long, error: Exception) { android.util.Log.w("ElunoMatter", "Matter connection failed", error); if (continuation.isActive) continuation.resumeWithException(error) }
        })
    }

    private suspend fun <T> connected(node: String, block: suspend (Long) -> T): T {
        val pointer = connect(node)
        return try { block(pointer) } finally { chip.releaseConnectedDevicePointer(pointer) }
    }

    override suspend fun reconnect(device: Device) { chip.markNodeSessionsDefunct(device.binding.nodeIdHex.toULong(16).toLong()) }

    override suspend fun read(device: Device): ReportedState = try {
        connected(device.binding.nodeIdHex) { pointer ->
            val report = readPaths(pointer, paths(device))
            decode(device, report, ReportedState())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        android.util.Log.w("ElunoMatter", "Matter read failed", error)
        throw error
    }

    private suspend fun readPaths(pointer: Long, paths: List<ChipAttributePath>, allowPathErrors: Boolean = false): NodeState = suspendCancellableCoroutine { continuation ->
        val accumulated = NodeState()
        chip.readAttributePath(object : ReportCallback {
            override fun onError(attributePath: ChipAttributePath?, eventPath: ChipEventPath?, e: Exception) {
                if (continuation.isActive && (!allowPathErrors || attributePath == null)) {
                    android.util.Log.w("ElunoMatter", "Matter attribute read failed", e)
                    continuation.resumeWithException(e)
                }
            }
            override fun onReport(nodeState: NodeState) {
                // SDK may split a read across multiple reports.
                synchronized(accumulated) { merge(accumulated, nodeState) }
            }
            override fun onDone() {
                if (!continuation.isActive) return
                val errors = accumulated.endpointStates.values.flatMap { it.clusterStates.values }.any { cluster -> cluster.attributeStatuses.values.any { it.status != Status.Code.Success } }
                if (!allowPathErrors && errors) {
                    android.util.Log.w("ElunoMatter", "Matter attribute read returned an error status")
                    continuation.resumeWithException(IllegalStateException("Не удалось прочитать атрибуты"))
                }
                else continuation.resume(accumulated)
            }
        }, pointer, paths, 10_000)
    }

    override suspend fun invoke(device: Device, command: DeviceCommand) = connected(device.binding.nodeIdHex) { pointer ->
        val writer = TlvWriter().apply { startStructure(AnonymousTag) }
        val (cluster, id) = when (command) {
            is DeviceCommand.SetPower -> 6L to if (command.isOn) 1L else 0L
            is DeviceCommand.SetBrightness -> {
                writer.put(ContextSpecificTag(0), (command.percent * 254.0 / 100).roundToInt().toUByte())
                writer.put(ContextSpecificTag(1), 0.toUShort())
                writer.put(ContextSpecificTag(2), 1.toUByte()); writer.put(ContextSpecificTag(3), 1.toUByte())
                8L to 0L
            }
            is DeviceCommand.SetColorTemperature -> {
                writer.put(ContextSpecificTag(0), (1_000_000.0 / command.kelvin).roundToInt().toUShort())
                writer.put(ContextSpecificTag(1), 0.toUShort())
                writer.put(ContextSpecificTag(2), 1.toUByte()); writer.put(ContextSpecificTag(3), 1.toUByte())
                0x300L to 0xAL
            }
            is DeviceCommand.SetColor -> {
                val (hue, saturation) = MatterColor.toHueSaturation(command.color)
                writer.put(ContextSpecificTag(0), hue.toUByte()); writer.put(ContextSpecificTag(1), saturation.toUByte())
                writer.put(ContextSpecificTag(2), 0.toUShort())
                writer.put(ContextSpecificTag(3), 1.toUByte()); writer.put(ContextSpecificTag(4), 1.toUByte())
                0x300L to 6L
            }
        }
        writer.endStructure()
        suspendCancellableCoroutine<Unit> { continuation ->
            chip.invoke(object : InvokeCallback {
                override fun onError(e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(invokeElement: InvokeElement, successCode: Long) {
                    if (continuation.isActive) {
                        if (successCode == 0L) continuation.resume(Unit)
                        else continuation.resumeWithException(IllegalStateException("Команда отклонена устройством"))
                    }
                }
            }, pointer, InvokeElement.newInstance(device.binding.endpointId.toInt(), cluster, id, writer.getEncoded(), null), 0, 10_000)
        }
    }

    override fun subscribe(device: Device): Flow<ReportedState> = callbackFlow {
        val pointer = withTimeout(25_000) { connect(device.binding.nodeIdHex) }
        val guard = Any()
        var closed = false
        var subscriptionId: Long? = null
        var state = ReportedState()
        fun shutdown(id: Long) = chip.shutdownSubscriptions(chip.fabricIndex, device.binding.nodeIdHex.toULong(16).toLong(), id)
        try {
            state = withTimeout(15_000) { decode(device, readPaths(pointer, paths(device)), ReportedState()) }
            chip.subscribeToPath(
                { id ->
                    val dispose = synchronized(guard) { if (closed) true else { subscriptionId = id; false } }
                    if (dispose) shutdown(id)
                },
                { _, _ -> close(IllegalStateException("Подписка прервана")) },
                object : ReportCallback {
                    override fun onError(attributePath: ChipAttributePath?, eventPath: ChipEventPath?, e: Exception) { close(e) }
                    override fun onReport(nodeState: NodeState) {
                        synchronized(guard) {
                            if (!closed) try {
                                state = decode(device, nodeState, state)
                                if (trySend(state).isFailure) close(IllegalStateException("Переполнен буфер изменений"))
                            } catch (e: Exception) { close(e) }
                        }
                    }
                    override fun onDone() { close() }
                }, pointer, paths(device), emptyList(), 0, 30, true, true, 10_000,
            )
            awaitClose {
                val id = synchronized(guard) { closed = true; subscriptionId }
                if (id != null) shutdown(id)
                else {
                    // Before establishment SDK exposes only node-level cancellation. Sibling
                    // endpoint streams receive termination and are re-established by the port.
                    chip.shutdownSubscriptions(chip.fabricIndex, device.binding.nodeIdHex.toULong(16).toLong())
                }
            }
        } finally {
            synchronized(guard) { closed = true }
            chip.releaseConnectedDevicePointer(pointer)
        }
    }

    suspend fun inspect(node: String, name: String): List<Device> = withTimeout(30_000) {
        connected(node) { pointer ->
            val descriptor = readPaths(pointer, listOf(ChipAttributePath.newInstance(ChipPathId.forWildcard(), ChipPathId.forId(0x1D), ChipPathId.forWildcard())))
            descriptor.endpointStates.toSortedMap().mapNotNull { (endpoint, value) ->
                if (endpoint == 0) return@mapNotNull null
                val attributes = value.getClusterState(0x1D)?.attributeStates ?: return@mapNotNull null
                val servers = (attributes[1L]?.value as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }.orEmpty()
                if (6L !in servers) return@mapNotNull null
                val capabilities = mutableSetOf<Capability>(Capability.OnOff)
                val query = buildList {
                    if (8L in servers) { add(path(endpoint, 8, 0xFFFC)); add(path(endpoint, 8, 2)); add(path(endpoint, 8, 3)) }
                    if (0x300L in servers) { add(path(endpoint, 0x300, 0xFFFC)); add(path(endpoint, 0x300, 0x400B)); add(path(endpoint, 0x300, 0x400C)) }
                }
                val details = if (query.isEmpty()) NodeState() else readPaths(pointer, query, allowPathErrors = true)
                if (8L in servers && ((number(details, endpoint, 8, 0xFFFC) ?: 0) and 1) != 0) {
                    val min = number(details, endpoint, 8, 2) ?: 1
                    val max = number(details, endpoint, 8, 3) ?: 254
                    capabilities += Capability.LevelControl(kotlin.math.ceil(min * 100.0 / 254).toInt().coerceAtLeast(1), kotlin.math.floor(max * 100.0 / 254).toInt().coerceAtMost(100))
                }
                val colorFeatures = number(details, endpoint, 0x300, 0xFFFC) ?: 0
                if (colorFeatures and 1 != 0) capabilities += Capability.ColorControl
                if (colorFeatures and 0x10 != 0) {
                    val minMireds = number(details, endpoint, 0x300, 0x400B)
                    val maxMireds = number(details, endpoint, 0x300, 0x400C)
                    if (minMireds != null && maxMireds != null && minMireds > 0 && maxMireds >= minMireds) {
                        capabilities += Capability.ColorTemperature(kotlin.math.ceil(1_000_000.0 / maxMireds).toInt(), kotlin.math.floor(1_000_000.0 / minMireds).toInt())
                    }
                }
                val types = (attributes[0L]?.value as? List<*>)?.filterIsInstance<ChipStructs.DescriptorClusterDeviceTypeStruct>()?.map { it.deviceType.toLong() }.orEmpty()
                val category = when { types.any { it in listOf(0x10AL, 0x10BL) } -> DeviceCategory.Outlet; types.any { it in listOf(0x100L,0x101L,0x10CL,0x10DL) } -> DeviceCategory.Light; else -> DeviceCategory.Unknown }
                Device(DeviceId("matter-$node-$endpoint"), name, category, DeviceBinding(node, endpoint.toUShort()), capabilities)
            }.also { check(it.isNotEmpty()) { "Поддерживаемые функции устройства не найдены" } }
        }
    }

    private fun paths(device: Device) = buildList {
        val ep = device.binding.endpointId.toInt()
        if (Capability.OnOff in device.capabilities) add(path(ep, 6, 0))
        if (device.capabilities.any { it is Capability.LevelControl }) add(path(ep, 8, 0))
        if (device.capabilities.any { it is Capability.ColorTemperature }) add(path(ep, 0x300, 7))
        if (Capability.ColorControl in device.capabilities) { add(path(ep, 0x300, 0)); add(path(ep, 0x300, 1)) }
    }

    private fun decode(device: Device, report: NodeState, previous: ReportedState): ReportedState {
        val endpoint = device.binding.endpointId.toInt()
        check(report.getEndpointState(endpoint)?.clusterStates?.values?.none { cluster -> cluster.attributeStatuses.values.any { it.status != Status.Code.Success } } != false) {
            "Устройство отклонило чтение атрибутов"
        }
        fun attr(cluster: Long, id: Long) = report.getEndpointState(endpoint)?.getClusterState(cluster)?.attributeStates?.get(id)
        fun int(cluster: Long, id: Long) = (attr(cluster, id)?.value as? Number)?.toInt()
        val power = attr(6,0)
        val level = attr(8,0)
        val temperature = attr(0x300,7)
        val previousHS = previous.color?.let(MatterColor::toHueSaturation)
        val hue = int(0x300,0) ?: previousHS?.first
        val saturation = int(0x300,1) ?: previousHS?.second
        return previous.copy(
            isOn = if (power != null) power.value as? Boolean else previous.isOn,
            brightnessPercent = if (level != null) int(8,0)?.takeIf { it in 0..254 }?.let { (it * 100.0 / 254).roundToInt() } else previous.brightnessPercent,
            colorTemperatureKelvin = if (temperature != null) int(0x300,7)?.takeIf { it > 0 }?.let { (1_000_000.0 / it).roundToInt() } else previous.colorTemperatureKelvin,
            color = if (hue != null && saturation != null && hue in 0..254 && saturation in 0..254) MatterColor.fromHueSaturation(hue,saturation) else previous.color,
        )
    }
    private fun path(ep: Int, cluster: Long, attr: Long) = ChipAttributePath.newInstance(ep, cluster, attr)
    private fun number(report: NodeState, ep: Int, cluster: Long, attr: Long) = (report.getEndpointState(ep)?.getClusterState(cluster)?.attributeStates?.get(attr)?.value as? Number)?.toInt()
    private fun merge(into: NodeState, report: NodeState) {
        report.endpointStates.forEach { (ep, state) ->
            val existing = into.endpointStates[ep]
            if (existing == null) into.endpointStates[ep] = state else state.clusterStates.forEach { (cluster, values) ->
                val old = existing.clusterStates[cluster]
                if (old == null) existing.clusterStates[cluster] = values else {
                    old.attributeStates.putAll(values.attributeStates)
                    old.attributeStatuses.putAll(values.attributeStatuses)
                }
            }
        }
    }
}
