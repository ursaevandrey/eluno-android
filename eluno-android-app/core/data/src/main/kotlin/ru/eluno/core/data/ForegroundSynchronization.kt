package ru.eluno.core.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** collectLatest joins the old owner, including cleanup, before starting a new one. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSynchronization(
    scope: CoroutineScope,
    private val ready: suspend () -> Unit,
    private val start: (CoroutineScope) -> Unit,
    private val stop: suspend () -> Unit,
) {
    private val visible = MutableStateFlow(false)
    private val job = scope.launch {
        visible.transformLatest { active ->
            if (!active) delay(2_000)
            emit(active)
        }.distinctUntilChanged().collectLatest { active ->
            if (active) coroutineScope {
                ready()
                try {
                    start(this)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { stop() }
                }
            }
        }
    }
    fun foreground() { visible.value = true }
    fun background() { visible.value = false }
    suspend fun close() { job.cancelAndJoin() }
}
