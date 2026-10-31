package ru.eluno.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ru.eluno.core.domain.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `theme and dynamic color actions are persisted through repository`() = runTest(dispatcher) {
        val settings = FakeSettingsRepository()
        val viewModel = SettingsViewModel(settings)
        viewModel.theme(ThemeMode.Dark)
        viewModel.dynamic(false)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(AppSettings(ThemeMode.Dark, false), settings.current.value)
    }
}

private class FakeSettingsRepository : SettingsRepository {
    val current = MutableStateFlow(AppSettings())
    override val settings: Flow<AppSettings> = current
    override suspend fun setTheme(mode: ThemeMode) { current.value = current.value.copy(themeMode = mode) }
    override suspend fun setDynamicColors(enabled: Boolean) { current.value = current.value.copy(dynamicColors = enabled) }
}
