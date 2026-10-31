package ru.eluno.core.matter

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.eluno.core.domain.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.random.Random

/** Transport owns native handles; a returned invoke only acknowledges delivery, never state. */
interface MatterTransport {
    suspend fun reconnect(device: Device) = Unit
    suspend fun read(device: Device): MatterObservation
    suspend fun invoke(device: Device, command: DeviceCommand)
    fun subscribe(device: Device): Flow<MatterObservation>
}

data class LocalNetwork(val available: Boolean, val generation: Long = 0)

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedMatterPort(
    private val transport: MatterTransport,
    private val networks: Flow<LocalNetwork>,
    private val now: () -> Instant = Instant::now,
    private val retryDelay: (Int) -> Long = { attempt -> minOf(30_000L, 1_000L shl attempt.coerceAtMost(5)) + Random.nextLong(250) },
) : MatterPort {
    private val generations = ConcurrentHashMap<String, Long>()
    private val nodeLocks = ConcurrentHashMap<String, Mutex>()
    private fun lock(device: Device) = nodeLocks.getOrPut(device.binding.nodeIdHex) { Mutex() }

    private fun snapshot(device: Device, state: MatterObservation) = DeviceSnapshot(
        device.id, state.reported, Freshness.Fresh, Availability.Reachable, state.observedAt ?: now(), sourceSequence = state.sequence,
    )

    override suspend fun read(device: Device): CommandResult = operation {
        lock(device).withLock { snapshot(device, transport.read(device)) }
    }

    override suspend fun execute(device: Device, command: DeviceCommand): CommandResult {
        device.validate(command)?.let { return CommandResult.Failure(it) }
        return operation {
            lock(device).withLock {
                transport.invoke(device, command)
                // Never replay an invoke after timeout: its effect may already have happened.
                withTimeout(10_000) {
                    var state = transport.read(device)
                    while (!command.matches(state.reported)) {
                        delay(300)
                        state = transport.read(device)
                    }
                    snapshot(device, state)
                }
            }
        }
    }

    private suspend fun operation(block: suspend () -> DeviceSnapshot): CommandResult = try {
        CommandResult.Success(withTimeout(25_000) { block() })
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        CommandResult.Failure(DeviceError.Transport("Устройство не подтвердило состояние вовремя. Обновите данные."))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CommandResult.Failure(DeviceError.Unavailable)
    }

    override fun updates(device: Device): Flow<DeviceSnapshot> = flow {
        var last = DeviceSnapshot(device.id)
        emitAll(networks.distinctUntilChanged().flatMapLatest { network ->
            flow {
                fun unavailable() = last.copy(
                    freshness = if (last.lastConfirmedAt == null) Freshness.Unknown else Freshness.Stale,
                    availability = Availability.Unreachable, lastError = DeviceError.Unavailable,
                )
                if (!network.available) { emit(unavailable()); return@flow }
                var attempt = 0
                while (currentCoroutineContext().isActive) {
                    try {
                        val initial = withTimeout(25_000) { lock(device).withLock {
                            val previousGeneration = generations.put(device.binding.nodeIdHex, network.generation)
                            if (attempt > 0 || (previousGeneration != null && previousGeneration != network.generation)) transport.reconnect(device)
                            transport.read(device)
                        } }
                        last = snapshot(device, initial)
                        emit(last)
                        // The foreground collector owns both jobs. Poll even when a subscription
                        // stays open silently; cancel both on backgrounding or network changes.
                        val periodicReads = flow {
                            while (currentCoroutineContext().isActive) {
                                delay(15_000)
                                when (val result = read(device)) {
                                    is CommandResult.Success -> emit(MatterObservation(result.snapshot.reported, result.snapshot.sourceSequence, result.snapshot.lastConfirmedAt))
                                    // A timeout must fail the merged flow, not merely cancel its
                                    // polling child and leave a silent subscription running.
                                    is CommandResult.Failure -> error("Periodic read failed")
                                }
                            }
                        }
                        val subscription = transport.subscribe(device).onCompletion { cause ->
                            if (cause == null) error("Subscription closed")
                        }
                        merge(subscription, periodicReads).collect { reported ->
                            last = snapshot(device, reported)
                            attempt = 0
                            emit(last)
                        }
                    } catch (_: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        // A bounded read timed out; retry discovery/session, never the last command.
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Closing/recreating the flow releases the previous native subscription.
                    }
                    emit(unavailable())
                    delay(retryDelay(attempt++))
                }
            }
        })
    }
}

internal fun DeviceCommand.matches(state: ReportedState): Boolean = when (this) {
    is DeviceCommand.SetPower -> state.isOn == isOn
    is DeviceCommand.SetBrightness -> state.brightnessPercent?.let { abs(it - percent) <= 1 } == true
    is DeviceCommand.SetColorTemperature -> state.colorTemperatureKelvin?.let { abs(1_000_000.0 / it - 1_000_000.0 / kelvin) <= 1.1 } == true
    is DeviceCommand.SetColor -> state.color?.let {
        val (actualHue, actualSaturation) = MatterColor.toHueSaturation(it)
        val (expectedHue, expectedSaturation) = MatterColor.toHueSaturation(color)
        val distance = abs(actualHue - expectedHue)
        abs(actualSaturation - expectedSaturation) <= 1 && (expectedSaturation == 0 || minOf(distance, 254 - distance) <= 1)
    } == true
}

internal object MatterColor {
    fun toHueSaturation(color: RgbColor): Pair<Int, Int> {
        val r = color.red / 255.0; val g = color.green / 255.0; val b = color.blue / 255.0
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val delta = max - min
        val hue = when { delta == 0.0 -> 0.0; max == r -> ((g - b) / delta + 6) % 6; max == g -> (b - r) / delta + 2; else -> (r - g) / delta + 4 }
        return kotlin.math.round(hue / 6 * 254).toInt().coerceIn(0, 254) to kotlin.math.round((if (max == 0.0) 0.0 else delta / max) * 254).toInt()
    }
    fun fromHueSaturation(hue: Int, saturation: Int): RgbColor {
        val h = hue / 254.0 * 6; val s = saturation / 254.0
        val x = s * (1 - abs(h % 2 - 1)); val m = 1 - s
        val rgb = when (h.toInt() % 6) { 0 -> listOf(s,x,0.0); 1 -> listOf(x,s,0.0); 2 -> listOf(0.0,s,x); 3 -> listOf(0.0,x,s); 4 -> listOf(x,0.0,s); else -> listOf(s,0.0,x) }.map { kotlin.math.round((it + m) * 255).toInt().coerceIn(0,255) }
        return RgbColor(rgb[0], rgb[1], rgb[2])
    }
}
