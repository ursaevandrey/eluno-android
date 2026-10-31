package ru.eluno.core.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import ru.eluno.core.database.*
import ru.eluno.core.domain.*
import java.security.SecureRandom

class CommissioningRepositoryImpl(private val dao: ElunoDao, private val port: CommissioningPort, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : CommissioningRepository {
    private val mutableState = MutableStateFlow(CommissioningState())
    override val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    override fun dismiss() { if (!mutex.isLocked) mutableState.value = CommissioningState() }

    override suspend fun commission(request: CommissioningRequest) = withContext(dispatcher) {
        if (!mutex.tryLock()) return@withContext
        try {
            mutableState.value = CommissioningState(CommissioningStage.Validating)
            port.validate(request)
            val node = SecureRandom().nextLong().ushr(1).coerceAtLeast(1).toULong().toString(16).padStart(16, '0')
            val actualNode = port.pair(node, request) { mutableState.value = CommissioningState(it) }
            mutableState.value = CommissioningState(CommissioningStage.Inspecting)
            val devices = port.inspect(actualNode, request.name.trim()).map { it.copy(icon = request.icon) }
            check(devices.isNotEmpty()) { "Поддерживаемые функции не найдены." }
            dao.saveCommissionedDevices(devices.map { it.toEntity() })
            mutableState.value = CommissioningState(CommissioningStage.Ready, devices = devices.map { it.id })
        } catch (cancelled: CancellationException) {
            mutableState.value = CommissioningState(CommissioningStage.Cancelled, "Подключение остановлено. Можно начать новую попытку.")
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = CommissioningState(CommissioningStage.Failed, error.message ?: "Не удалось подключить устройство")
        } finally {
            mutex.unlock()
        }
    }

}
