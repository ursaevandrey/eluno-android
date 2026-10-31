package ru.eluno.app

import ru.eluno.feature.widgets.*

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.eluno.core.data.CommissioningRepositoryImpl
import ru.eluno.core.data.ForegroundSynchronization
import ru.eluno.core.data.WidgetBindingRepositoryImpl
import ru.eluno.core.data.DeviceRepositoryImpl
import ru.eluno.core.database.DataStoreSettingsRepository
import ru.eluno.core.database.createElunoDao
import ru.eluno.core.domain.*
import ru.eluno.core.matter.AndroidMatter

class AppContainer(application: Application) {
    private val matter = AndroidMatter(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        android.util.Log.e("Eluno", "Background operation failed", error)
    })
    private val dao = createElunoDao(application)
    val widgetRepository: WidgetBindingRepository = WidgetBindingRepositoryImpl(dao)
    val deviceRepository = DeviceRepositoryImpl(dao, matter.port)
    val commissioningRepository: CommissioningRepository = CommissioningRepositoryImpl(dao, matter.commissioning)
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(application)
    private val initialized = scope.async {
        deviceRepository.recoverAfterRestart()
    }
    suspend fun awaitInitialization() { initialized.await() }
    init {
        scope.launch {
            initialized.await()
            observeWidgetUpdates(widgetRepository.observeBindings(), deviceRepository).collect { ids ->
                ids.forEach { id -> updateDeviceWidget(application, id) }
            }
        }
    }
    private val synchronization = ForegroundSynchronization(scope, ::awaitInitialization,
        deviceRepository::startSynchronization, deviceRepository::stopSynchronization)
    fun foreground() = synchronization.foreground()
    fun background() = synchronization.background()

}

class ElunoApplication : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
