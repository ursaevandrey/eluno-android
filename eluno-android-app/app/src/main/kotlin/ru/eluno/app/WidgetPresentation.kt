package ru.eluno.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import ru.eluno.core.database.WidgetBindingEntity
import ru.eluno.core.domain.Device
import ru.eluno.core.domain.DeviceId
import ru.eluno.core.domain.DeviceRepository
import ru.eluno.core.domain.DeviceSnapshot

internal data class WidgetPresentation(
    val deviceId: DeviceId?,
    val device: Device?,
    val snapshot: DeviceSnapshot?,
    val backgroundEnabled: Boolean = true,
    val backgroundOpacity: Int = 100,
    val colorMode: WidgetColorMode = WidgetColorMode.System,
)

/** Do not emit an empty presentation while a stored binding is still being loaded. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeWidgetPresentation(bindings: Flow<WidgetBindingEntity?>, repository: DeviceRepository): Flow<WidgetPresentation> =
    bindings.flatMapLatest { binding ->
        if (binding == null) flowOf(WidgetPresentation(null, null, null))
        else {
            val id = DeviceId(binding.deviceId)
            combine(repository.observeDevice(id), repository.observeSnapshot(id)) { device, snapshot ->
                WidgetPresentation(id, device, snapshot, binding.backgroundEnabled, binding.backgroundOpacity, WidgetColorMode.fromStorage(binding.colorMode))
            }
        }
    }
