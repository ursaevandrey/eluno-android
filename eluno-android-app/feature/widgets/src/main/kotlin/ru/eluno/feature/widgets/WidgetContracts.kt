package ru.eluno.feature.widgets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import ru.eluno.core.domain.*

/** No device observers without bindings; timestamps never invalidate widget content. */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeWidgetUpdates(bindings: Flow<List<WidgetBinding>>, devices: DeviceRepository): Flow<Set<Int>> = flow {
    var previous = emptyMap<Int, WidgetPresentation>()
    bindings.distinctUntilChanged().flatMapLatest { entries ->
        if (entries.isEmpty()) flowOf(emptyMap())
        else combine(entries.map { binding ->
            observeWidgetPresentation(flowOf(binding), devices).map { binding.appWidgetId to it }
        }) { it.toMap() }
    }.collect { next ->
        val changed = (previous.keys + next.keys).filter { previous[it] != next[it] }.toSet()
        previous = next
        if (changed.isNotEmpty()) emit(changed)
    }
}
