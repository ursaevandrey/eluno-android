package ru.eluno.core.domain

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

@JvmInline value class DeviceId(val value: String) {
    companion object { fun create(): DeviceId = DeviceId(UUID.randomUUID().toString()) }
}
@JvmInline value class CommandId(val value: String) {
    companion object { fun create(): CommandId = CommandId(UUID.randomUUID().toString()) }
}

data class DeviceBinding(val nodeIdHex: String, val endpointId: UShort)
enum class DeviceCategory { Outlet, Light, LightStrip, Unknown }
enum class DeviceIcon { Automatic, Lightbulb, LightMode, LightStrip, Outlet, Device }

sealed interface Capability {
    data object OnOff : Capability
    data class LevelControl(val minPercent: Int = 1, val maxPercent: Int = 100) : Capability
    data class ColorTemperature(val minKelvin: Int, val maxKelvin: Int) : Capability
    data object ColorControl : Capability
}

data class Device(
    val id: DeviceId,
    val name: String,
    val category: DeviceCategory,
    val binding: DeviceBinding,
    val capabilities: Set<Capability>,
    val icon: DeviceIcon = DeviceIcon.Automatic,
)

data class RgbColor(val red: Int, val green: Int, val blue: Int) {
    init { require(red in 0..255 && green in 0..255 && blue in 0..255) }
}

data class ReportedState(
    val isOn: Boolean? = null,
    val brightnessPercent: Int? = null,
    val colorTemperatureKelvin: Int? = null,
    val color: RgbColor? = null,
)

enum class Freshness { Unknown, Fresh, Stale }
enum class Availability { Unknown, Reachable, Unreachable }

sealed interface DeviceCommand {
    data class SetPower(val isOn: Boolean) : DeviceCommand
    data class SetBrightness(val percent: Int) : DeviceCommand
    data class SetColorTemperature(val kelvin: Int) : DeviceCommand
    data class SetColor(val color: RgbColor) : DeviceCommand
}

data class PendingCommand(
    val id: CommandId,
    val command: DeviceCommand,
    val submittedAt: Instant,
)

sealed interface DeviceError {
    data object DeviceNotFound : DeviceError
    data object Unavailable : DeviceError
    data class UnsupportedCapability(val capability: String) : DeviceError
    data class InvalidValue(val message: String) : DeviceError
    data class Transport(val message: String) : DeviceError
}

data class DeviceSnapshot(
    val deviceId: DeviceId,
    val reported: ReportedState = ReportedState(),
    val freshness: Freshness = Freshness.Unknown,
    val availability: Availability = Availability.Unknown,
    val lastConfirmedAt: Instant? = null,
    val pendingCommand: PendingCommand? = null,
    val lastError: DeviceError? = null,
)

sealed interface CommandResult {
    data class Success(val snapshot: DeviceSnapshot) : CommandResult
    data class Failure(val error: DeviceError) : CommandResult
}

interface MatterPort {
    suspend fun read(device: Device): CommandResult
    suspend fun execute(device: Device, command: DeviceCommand): CommandResult
    fun updates(device: Device): Flow<DeviceSnapshot>
}

interface DeviceRepository {
    fun observeDevices(): Flow<List<Device>>
    fun observeDevice(id: DeviceId): Flow<Device?>
    fun observeSnapshot(id: DeviceId): Flow<DeviceSnapshot?>
    suspend fun refresh(id: DeviceId): Result<Unit>
    suspend fun submit(id: DeviceId, command: DeviceCommand): Result<CommandId>
    suspend fun rename(id: DeviceId, name: String): Result<Unit>
    suspend fun setIcon(id: DeviceId, icon: DeviceIcon): Result<Unit>
}

enum class ThemeMode { System, Light, Dark }
data class AppSettings(val themeMode: ThemeMode = ThemeMode.System, val dynamicColors: Boolean = true)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setTheme(mode: ThemeMode)
    suspend fun setDynamicColors(enabled: Boolean)
}

data class WidgetBinding(val appWidgetId: Int, val deviceId: DeviceId)

fun Device.validate(command: DeviceCommand): DeviceError? = when (command) {
    is DeviceCommand.SetPower -> if (capabilities.any { it is Capability.OnOff }) null
        else DeviceError.UnsupportedCapability("OnOff")
    is DeviceCommand.SetBrightness -> {
        val capability = capabilities.filterIsInstance<Capability.LevelControl>().firstOrNull()
            ?: return DeviceError.UnsupportedCapability("LevelControl")
        if (command.percent in capability.minPercent..capability.maxPercent) null
        else DeviceError.InvalidValue("Яркость должна быть от ${capability.minPercent} до ${capability.maxPercent}%")
    }
    is DeviceCommand.SetColorTemperature -> {
        val capability = capabilities.filterIsInstance<Capability.ColorTemperature>().firstOrNull()
            ?: return DeviceError.UnsupportedCapability("ColorTemperature")
        if (command.kelvin in capability.minKelvin..capability.maxKelvin) null
        else DeviceError.InvalidValue("Температура должна быть от ${capability.minKelvin} до ${capability.maxKelvin} K")
    }
    is DeviceCommand.SetColor -> if (capabilities.any { it is Capability.ColorControl }) null
        else DeviceError.UnsupportedCapability("ColorControl")
}
