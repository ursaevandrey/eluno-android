package ru.eluno.app

import kotlinx.coroutines.flow.first
import ru.eluno.core.domain.CommandId
import ru.eluno.core.domain.DeviceCommand
import ru.eluno.core.domain.DeviceId
import ru.eluno.core.domain.DeviceRepository
import ru.eluno.core.domain.Freshness

/** Never infer the next power command from the launcher's potentially old view. */
internal suspend fun executeWidgetToggle(repository: DeviceRepository, id: DeviceId, stillBound: suspend () -> Boolean = { true }): Result<CommandId> {
    val refresh = repository.refresh(id)
    refresh.exceptionOrNull()?.let { return Result.failure(it) }
    val snapshot = repository.observeSnapshot(id).first()
    val on = snapshot?.reported?.isOn
    if (snapshot?.freshness != Freshness.Fresh || on == null) {
        return Result.failure(IllegalStateException("Не удалось определить состояние устройства"))
    }
    if (!stillBound()) return Result.failure(IllegalStateException("Привязка виджета изменилась"))
    return repository.submit(id, DeviceCommand.SetPower(!on))
}
