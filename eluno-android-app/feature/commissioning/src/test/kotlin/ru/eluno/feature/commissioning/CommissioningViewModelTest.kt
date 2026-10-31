package ru.eluno.feature.commissioning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eluno.core.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommissioningViewModelTest {
    @Test fun `opening Add resets old feedback and never starts recovery or pairing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FormRepository()
            val vm = CommissioningViewModel(repo)
            advanceUntilIdle()
            assertEquals(CommissioningStage.Idle, vm.state.value.stage)
            assertEquals(0, repo.commissions)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `only explicit Connect starts commissioning`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FormRepository()
            val vm = CommissioningViewModel(repo)
            advanceUntilIdle()
            vm.connect(CommissioningRequest("test-code", "Устройство"))
            advanceUntilIdle()
            assertEquals(1, repo.commissions)
        } finally { Dispatchers.resetMain() }
    }
}

private class FormRepository : CommissioningRepository {
    override val state = MutableStateFlow(CommissioningState(CommissioningStage.Failed, "Прежняя ошибка"))
    var commissions = 0
    override suspend fun commission(request: CommissioningRequest) { commissions++ }
    override fun dismiss() { state.value = CommissioningState() }
}
