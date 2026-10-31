package ru.eluno.app

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.glance.appwidget.updateAll
import ru.eluno.core.data.CommissioningRepositoryImpl
import ru.eluno.core.data.DeviceRepositoryImpl
import ru.eluno.core.database.DataStoreSettingsRepository
import ru.eluno.core.database.createElunoDao
import ru.eluno.core.domain.*
import ru.eluno.core.matter.AndroidMatter

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class AppContainer(application: Application) {
    private val matter = AndroidMatter(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dao = createElunoDao(application)
    val deviceRepository = DeviceRepositoryImpl(dao, matter.port)
    val commissioningRepository: CommissioningRepository = CommissioningRepositoryImpl(dao, matter.commissioning)
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(application)
    private val initialized = scope.launch {
        deviceRepository.recoverAfterRestart()
    }
    suspend fun awaitInitialization() { initialized.join() }
    init {
        scope.launch {
            deviceRepository.observeDevices().flatMapLatest { devices ->
                if (devices.isEmpty()) flowOf(Unit) else combine(devices.map { deviceRepository.observeSnapshot(it.id) }) { Unit }
            }.debounce(250).collect { updateDeviceWidgets(application) }
        }
    }
    private var foregroundJob: Job? = null
    private var stopJob: Job? = null
    fun foreground() {
        stopJob?.cancel()
        if (foregroundJob?.isActive == true) return
        foregroundJob = scope.launch {
            initialized.join()
            deviceRepository.startSynchronization(this)
            awaitCancellation()
        }
    }
    fun background() {
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(2_000)
            foregroundJob?.cancelAndJoin()
            foregroundJob = null
            deviceRepository.stopSynchronization()
        }
    }
}

class ElunoApplication : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
