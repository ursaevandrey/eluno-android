package ru.eluno.core.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.eluno.core.database.CommandEntity
import ru.eluno.core.database.DeviceEntity
import ru.eluno.core.database.ElunoDao
import ru.eluno.core.database.SnapshotEntity
import ru.eluno.core.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import java.time.Instant

class DeviceRepositoryImpl(
    private val dao: ElunoDao,
    private val matterPort: MatterPort,
) : DeviceRepository {
    private val commandLocks = java.util.concurrent.ConcurrentHashMap<DeviceId, Mutex>()
    private val stateMutex = Mutex()
    private var synchronization: kotlinx.coroutines.Job? = null

    override fun observeDevices(): Flow<List<Device>> = dao.observeDevices().map { rows -> rows.map(DeviceEntity::toDomain) }
    override fun observeDevice(id: DeviceId): Flow<Device?> = dao.observeDevice(id.value).map { it?.toDomain() }
    override fun observeSnapshot(id: DeviceId): Flow<DeviceSnapshot?> = dao.observeSnapshot(id.value).map { it?.toDomain() }

    /** One subscription per catalog endpoint, independent of the number of UI collectors. */
    @Synchronized fun startSynchronization(scope: kotlinx.coroutines.CoroutineScope) {
        if (synchronization?.isActive == true) return
        synchronization = scope.launch {
            kotlinx.coroutines.coroutineScope {
                val subscriptions = mutableMapOf<DeviceId, Pair<Device, kotlinx.coroutines.Job>>()
                observeDevices().collect { devices ->
                    val ids = devices.map { it.id }.toSet()
                    subscriptions.keys.filter { it !in ids }.forEach { subscriptions.remove(it)?.second?.cancel() }
                    devices.forEach { device ->
                        val previous = subscriptions[device.id]
                        if (previous == null || previous.first.binding != device.binding || previous.first.capabilities != device.capabilities) {
                            previous?.second?.cancelAndJoin()
                            subscriptions[device.id] = device to launch {
                                matterPort.updates(device).collect { incoming ->
                                    stateMutex.withLock {
                                        if (dao.getDevice(device.id.value) != null) saveReport(incoming)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun stopSynchronization() {
        val job = synchronized(this) { synchronization.also { synchronization = null } }
        job?.cancelAndJoin()
        stateMutex.withLock { markCachedStateStale(recoverCommands = false) }
    }

    suspend fun recoverAfterRestart() = stateMutex.withLock { markCachedStateStale(recoverCommands = true) }

    private suspend fun markCachedStateStale(recoverCommands: Boolean) {
        dao.observeDevices().first().forEach { device ->
            val old = dao.getSnapshot(device.id)?.toDomain() ?: return@forEach
            val pending = old.pendingCommand.takeIf { recoverCommands }
            val snapshot = old.copy(
                freshness = if (old.lastConfirmedAt == null) Freshness.Unknown else Freshness.Stale,
                availability = Availability.Unknown,
                pendingCommand = if (recoverCommands) null else old.pendingCommand,
                lastError = if (pending != null) DeviceError.Transport("Подтверждение команды прервано. Обновите состояние.") else old.lastError,
            )
            if (pending != null) dao.saveCommandState(pending.command.toEntity(pending.id, old.deviceId, "Interrupted", pending.submittedAt, "Процесс был остановлен"), snapshot.toEntity())
            else dao.upsertSnapshot(snapshot.toEntity())
        }
    }

    private suspend fun saveReport(incoming: DeviceSnapshot) {
        val old = dao.getSnapshot(incoming.deviceId.value)?.toDomain()
        val oldTime = old?.lastConfirmedAt
        val newTime = incoming.lastConfirmedAt
        if (oldTime != null && newTime != null && newTime < oldTime) {
            if (incoming.availability != Availability.Reachable) dao.upsertSnapshot(old.copy(freshness = Freshness.Stale, availability = incoming.availability, lastError = incoming.lastError).toEntity())
            return
        }
        val next = if (incoming.lastConfirmedAt == null && old != null) incoming.copy(
            reported = old.reported, lastConfirmedAt = old.lastConfirmedAt,
            freshness = if (old.lastConfirmedAt == null) Freshness.Unknown else Freshness.Stale,
        ) else incoming
        dao.upsertSnapshot(next.copy(pendingCommand = old?.pendingCommand).toEntity())
    }

    override suspend fun refresh(id: DeviceId): Result<Unit> = cancellableResult {
        val device = dao.getDevice(id.value)?.toDomain() ?: error("Устройство не найдено")
        when (val result = matterPort.read(device)) {
            is CommandResult.Success -> stateMutex.withLock { saveReport(result.snapshot) }
            is CommandResult.Failure -> {
                stateMutex.withLock {
                    val current = dao.getSnapshot(id.value)?.toDomain() ?: DeviceSnapshot(id)
                    dao.upsertSnapshot(current.failed(result.error).toEntity())
                }
                error(result.error.displayText())
            }
        }
    }

    override suspend fun submit(id: DeviceId, command: DeviceCommand): Result<CommandId> = cancellableResult {
        commandLocks.getOrPut(id) { Mutex() }.withLock {
            val device = dao.getDevice(id.value)?.toDomain() ?: error("Устройство не найдено")
            device.validate(command)?.let { error(it.displayText()) }
            val commandId = CommandId.create()
            val now = Instant.now()
            stateMutex.withLock {
                val current = dao.getSnapshot(id.value)?.toDomain() ?: DeviceSnapshot(id)
                dao.saveCommandState(command.toEntity(commandId, id, "Running", now), current.copy(pendingCommand = PendingCommand(commandId, command, now), lastError = null).toEntity())
            }
            try {
                val result = matterPort.execute(device, command)
                stateMutex.withLock {
                    val current = dao.getSnapshot(id.value)?.toDomain() ?: DeviceSnapshot(id)
                    when (result) {
                        is CommandResult.Success -> {
                            saveReport(result.snapshot)
                            val latest = dao.getSnapshot(id.value)!!.toDomain()
                            dao.saveCommandState(command.toEntity(commandId, id, "Confirmed", now), latest.copy(pendingCommand = null, lastError = null).toEntity())
                        }
                        is CommandResult.Failure -> dao.saveCommandState(command.toEntity(commandId, id, "Failed", now, result.error.displayText()), current.failed(result.error).copy(pendingCommand = null).toEntity())
                    }
                }
                if (result is CommandResult.Failure) error(result.error.displayText())
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) { finishInterrupted(id, commandId, command, now) }
                throw cancelled
            } catch (error: Exception) {
                finishInterrupted(id, commandId, command, now)
                throw error
            }
            commandId
        }
    }

    private suspend fun finishInterrupted(id: DeviceId, commandId: CommandId, command: DeviceCommand, now: Instant) = stateMutex.withLock {
        val current = dao.getSnapshot(id.value)?.toDomain() ?: return@withLock
        if (current.pendingCommand?.id == commandId) {
            val error = DeviceError.Transport("Подтверждение команды прервано. Обновите состояние.")
            dao.saveCommandState(command.toEntity(commandId, id, "Interrupted", now, error.message), current.failed(error).copy(pendingCommand = null).toEntity())
        }
    }

    override suspend fun rename(id: DeviceId, name: String): Result<Unit> = runCatching {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Имя не может быть пустым" }
        check(dao.rename(id.value, clean) == 1) { "Устройство не найдено" }
    }

    override suspend fun setIcon(id: DeviceId, icon: DeviceIcon): Result<Unit> = runCatching {
        check(dao.setIcon(id.value, icon.name) == 1) { "Устройство не найдено" }
    }

}

private fun DeviceEntity.toDomain() = Device(
    DeviceId(id), name, DeviceCategory.valueOf(category), DeviceBinding(nodeIdHex, endpointId.toUShort()), decodeCapabilities(capabilities), DeviceIcon.valueOf(icon),
)
internal fun Device.toEntity() = DeviceEntity(id.value, name, category.name, binding.nodeIdHex, binding.endpointId.toInt(), encodeCapabilities(capabilities), icon.name)

private fun encodeCapabilities(values: Set<Capability>) = values.joinToString(";") {
    when (it) {
        Capability.OnOff -> "onoff"
        is Capability.LevelControl -> "level:${it.minPercent}:${it.maxPercent}"
        is Capability.ColorTemperature -> "temperature:${it.minKelvin}:${it.maxKelvin}"
        Capability.ColorControl -> "color"
    }
}
private fun decodeCapabilities(value: String): Set<Capability> = value.split(';').mapNotNull { token ->
    val p = token.split(':')
    when (p.firstOrNull()) {
        "onoff" -> Capability.OnOff
        "level" -> Capability.LevelControl(p[1].toInt(), p[2].toInt())
        "temperature" -> Capability.ColorTemperature(p[1].toInt(), p[2].toInt())
        "color" -> Capability.ColorControl
        else -> null
    }
}.toSet()

private fun SnapshotEntity.toDomain(): DeviceSnapshot {
    val command = pendingId?.let { id -> PendingCommand(CommandId(id), decodeCommand(pendingType!!, pendingValue!!), Instant.ofEpochMilli(pendingAtEpochMs!!)) }
    val color = colorRgb?.let { RgbColor((it shr 16) and 255, (it shr 8) and 255, it and 255) }
    val error = errorType?.let { if (it == "unavailable") DeviceError.Unavailable else DeviceError.Transport(errorMessage ?: "Ошибка") }
    return DeviceSnapshot(DeviceId(deviceId), ReportedState(isOn, brightness, colorTemperature, color), Freshness.valueOf(freshness), Availability.valueOf(availability), lastConfirmedEpochMs?.let(Instant::ofEpochMilli), command, error)
}
private fun DeviceSnapshot.toEntity(): SnapshotEntity {
    val color = reported.color?.let { (it.red shl 16) or (it.green shl 8) or it.blue }
    val pending = pendingCommand
    return SnapshotEntity(deviceId.value, reported.isOn, reported.brightnessPercent, reported.colorTemperatureKelvin, color, freshness.name, availability.name, lastConfirmedAt?.toEpochMilli(), pending?.id?.value, pending?.command?.type(), pending?.command?.value(), pending?.submittedAt?.toEpochMilli(), when (lastError) { DeviceError.Unavailable -> "unavailable"; null -> null; else -> "other" }, lastError?.displayText())
}
private fun DeviceCommand.type() = when (this) { is DeviceCommand.SetPower -> "power"; is DeviceCommand.SetBrightness -> "brightness"; is DeviceCommand.SetColorTemperature -> "temperature"; is DeviceCommand.SetColor -> "color" }
private fun DeviceCommand.value() = when (this) { is DeviceCommand.SetPower -> isOn.toString(); is DeviceCommand.SetBrightness -> percent.toString(); is DeviceCommand.SetColorTemperature -> kelvin.toString(); is DeviceCommand.SetColor -> "${color.red},${color.green},${color.blue}" }
private fun decodeCommand(type: String, value: String): DeviceCommand = when (type) { "power" -> DeviceCommand.SetPower(value.toBoolean()); "brightness" -> DeviceCommand.SetBrightness(value.toInt()); "temperature" -> DeviceCommand.SetColorTemperature(value.toInt()); else -> value.split(',').map(String::toInt).let { DeviceCommand.SetColor(RgbColor(it[0], it[1], it[2])) } }
private fun DeviceCommand.toEntity(id: CommandId, deviceId: DeviceId, status: String, now: Instant, error: String? = null) = CommandEntity(id.value, deviceId.value, type(), value(), status, now.toEpochMilli(), error)
private fun DeviceError.displayText() = when (this) { DeviceError.DeviceNotFound -> "Устройство не найдено"; DeviceError.Unavailable -> "Устройство недоступно"; is DeviceError.UnsupportedCapability -> "Функция $capability не поддерживается"; is DeviceError.InvalidValue -> message; is DeviceError.Transport -> message }

private suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> = try { Result.success(block()) }
catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
catch (error: Exception) { Result.failure(error) }

private fun DeviceSnapshot.failed(error: DeviceError) = copy(
    freshness = if (lastConfirmedAt == null) Freshness.Unknown else Freshness.Stale,
    availability = if (error == DeviceError.Unavailable) Availability.Unreachable else Availability.Unknown,
    lastError = error,
)
