package ru.eluno.core.data

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.eluno.core.database.ElunoDao
import ru.eluno.core.database.WidgetBindingEntity
import ru.eluno.core.domain.*

class WidgetBindingRepositoryImpl(private val dao: ElunoDao) : WidgetBindingRepository {
    override fun observeBindings() = dao.observeWidgetBindings().map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()
    override fun observeBinding(id: Int) = dao.observeWidgetBinding(id).map { it?.toDomain() }.distinctUntilChanged()
    override suspend fun getBinding(id: Int) = dao.getWidgetBinding(id)?.toDomain()
    override suspend fun bind(binding: WidgetBinding) {
        check(dao.getDevice(binding.deviceId.value) != null) { "Устройство не найдено" }
        dao.upsertWidgetBinding(WidgetBindingEntity(binding.appWidgetId, binding.deviceId.value, binding.backgroundEnabled, binding.backgroundOpacity, binding.colorMode))
    }
    override suspend fun unbind(id: Int) = dao.deleteWidgetBinding(id)
    override suspend fun restore(oldIds: List<Int>, newIds: List<Int>) = dao.restoreWidgetBindings(oldIds, newIds)
}

private fun WidgetBindingEntity.toDomain() = WidgetBinding(appWidgetId, DeviceId(deviceId), backgroundEnabled, backgroundOpacity, colorMode)
