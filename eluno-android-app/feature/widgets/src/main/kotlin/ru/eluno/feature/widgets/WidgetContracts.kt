package ru.eluno.feature.widgets

import kotlinx.coroutines.flow.Flow
import ru.eluno.core.domain.DeviceId
import ru.eluno.core.domain.DeviceSnapshot
import ru.eluno.core.domain.WidgetBinding

interface WidgetBindingRepository {
    fun observeBinding(appWidgetId: Int): Flow<WidgetBinding?>
    fun observeDeviceState(deviceId: DeviceId): Flow<DeviceSnapshot?>
    suspend fun bind(binding: WidgetBinding)
    suspend fun unbind(appWidgetId: Int)
}
