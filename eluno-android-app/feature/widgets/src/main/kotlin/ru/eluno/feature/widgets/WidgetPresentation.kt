package ru.eluno.feature.widgets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import ru.eluno.core.domain.WidgetBinding
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.eluno.core.domain.Device
import ru.eluno.core.domain.DeviceId
import ru.eluno.core.domain.DeviceRepository
import ru.eluno.core.domain.DeviceSnapshot

data class WidgetPresentation(
    val deviceId: DeviceId?,
    val device: Device?,
    val pending: Boolean = false,
    val backgroundEnabled: Boolean = true,
    val backgroundOpacity: Int = 100,
    val colorMode: WidgetColorMode = WidgetColorMode.System,
)

/** Do not emit an empty presentation while a stored binding is still being loaded. */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeWidgetPresentation(bindings: Flow<WidgetBinding?>, repository: DeviceRepository): Flow<WidgetPresentation> =
    bindings.flatMapLatest { binding ->
        if (binding == null) flowOf(WidgetPresentation(null, null))
        else {
            val id = binding.deviceId
            combine(repository.observeDevice(id), repository.observeSnapshot(id)) { device, snapshot ->
                WidgetPresentation(id, device, snapshot?.pendingCommand != null, binding.backgroundEnabled, binding.backgroundOpacity, WidgetColorMode.fromStorage(binding.colorMode))
            }
        }
    }.distinctUntilChanged()
