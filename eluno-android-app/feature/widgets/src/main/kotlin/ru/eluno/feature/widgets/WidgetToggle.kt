package ru.eluno.feature.widgets

import ru.eluno.core.domain.CommandId
import ru.eluno.core.domain.DeviceId
import ru.eluno.core.domain.DeviceRepository

/** The repository serializes read + absolute command with application actions. */
suspend fun executeWidgetToggle(repository: DeviceRepository, id: DeviceId, stillBound: suspend () -> Boolean = { true }): Result<CommandId> =
    repository.togglePower(id, stillBound)
