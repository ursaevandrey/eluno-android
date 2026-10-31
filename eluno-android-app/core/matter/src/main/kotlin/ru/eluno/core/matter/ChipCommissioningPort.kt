package ru.eluno.core.matter

import chip.devicecontroller.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import matter.onboardingpayload.OnboardingPayloadParser
import ru.eluno.core.domain.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ChipCommissioningPort(private val runtime: ChipRuntime, private val transport: ChipMatterTransport) : CommissioningPort {
    private val mutex = Mutex()
    override fun validate(request: CommissioningRequest) {
        require(request.name.trim().isNotEmpty()) { "Введите имя устройства" }
        try {
            val parser = OnboardingPayloadParser()
            if (request.setupCode.startsWith("MT:")) parser.parseQrCode(request.setupCode)
            else parser.parseManualPairingCode(request.setupCode)
        } catch (_: Exception) { throw IllegalArgumentException("Неверный код Matter. Проверьте код на устройстве или новый код общего доступа.") }
        request.wifiSsid?.let { require(it.toByteArray(Charsets.UTF_8).size in 1..32) { "Имя сети должно занимать от 1 до 32 байт" } }
    }

    override suspend fun pair(nodeIdHex: String, request: CommissioningRequest, onStage: (CommissioningStage) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            validate(request)
            val chip = runtime.controller
            val node = nodeIdHex.toULong(16).toLong()
            pairingAttempt(
                stop = { chip.stopDevicePairing(node) },
                clearListener = { chip.setCompletionListener(CompletionAdapter()) },
            ) {
                try {
                    withTimeout(150_000) {
                        suspendCancellableCoroutine<String> { continuation ->
                            fun fail(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
                            chip.setCompletionListener(object : CompletionAdapter() {
                                override fun onCommissioningComplete(nodeId: Long, errorCode: Long) {
                                    if (nodeId != node || !continuation.isActive) return
                                    val matchingNode = if (errorCode == 126L) runCatching { chip.getMatchingFabricNodeId() }.getOrDefault(0L) else 0L
                                    val resolved = commissionedNode(node, errorCode, matchingNode)
                                    if (resolved != null) continuation.resume(resolved.toULong().toString(16).padStart(16, '0'))
                                    else if (errorCode == 126L) fail("Устройство уже связано с Eluno, но существующий доступ не удалось восстановить. Откройте новый код общего доступа и повторите добавление.")
                                    else fail("Не удалось завершить подключение Matter (код $errorCode)")
                                }
                                override fun onPairingComplete(errorCode: Long) { if (errorCode != 0L) fail("Не удалось установить защищённое соединение (код $errorCode)") }
                                override fun onError(error: Throwable) {
                                    val details = (error as? ChipDeviceControllerException)?.let { " (код 0x${it.errorCode.toString(16)})" }.orEmpty()
                                    fail("Ошибка подключения Matter$details. Проверьте действующий код общего доступа и локальную сеть.")
                                }
                                override fun onReadCommissioningInfo(vendorId: Int, productId: Int, wifiEndpointId: Int, threadEndpointId: Int) {
                                    if (wifiEndpointId == 0xFFFF && threadEndpointId != 0xFFFF) {
                                        fail("Устройства только с Thread пока не поддерживаются")
                                    }
                                }
                                override fun onCommissioningStageStart(nodeId: Long, stage: String) {
                                    if (!continuation.isActive || nodeId != node) return
                                    onStage(when {
                                        stage.contains("Attestation", true) -> CommissioningStage.Attestation
                                        stage.contains("Network", true) -> CommissioningStage.Network
                                        stage.contains("NOC", true) || stage.contains("Credentials", true) -> CommissioningStage.Fabric
                                        else -> CommissioningStage.Pairing
                                    })
                                }
                                override fun onICDRegistrationInfoRequired() { fail("Спящие устройства ICD пока не поддерживаются") }
                            })
                            onStage(CommissioningStage.Discovering)
                            val credentials = request.wifiSsid?.let { NetworkCredentials.forWiFi(NetworkCredentials.WiFiCredentials(it, request.wifiPassword ?: "")) }
                            chip.pairDeviceWithCode(node, request.setupCode, true, credentials == null,
                                CommissionParameters.Builder().setNetworkCredentials(credentials).build())
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw IllegalStateException("Время подключения истекло. Проверьте режим подключения устройства и домашнюю сеть.")
                } catch (error: ChipDeviceControllerException) {
                    throw IllegalStateException("Не удалось начать подключение Matter (код 0x${error.errorCode.toString(16)}). Проверьте действующий код сопряжения и локальную сеть.", error)
                }
            }
        }
    }

    override suspend fun inspect(nodeIdHex: String, name: String) = withContext(Dispatchers.IO) { transport.inspect(nodeIdHex, name) }
}

internal open class CompletionAdapter : ChipDeviceController.CompletionListener {
    override fun onConnectDeviceComplete() = Unit
    override fun onStatusUpdate(status: Int) = Unit
    override fun onPairingComplete(errorCode: Long) = Unit
    override fun onPairingDeleted(errorCode: Long) = Unit
    override fun onCommissioningComplete(nodeId: Long, errorCode: Long) = Unit
    override fun onReadCommissioningInfo(vendorId: Int, productId: Int, wifiEndpointId: Int, threadEndpointId: Int) = Unit
    override fun onCommissioningStatusUpdate(nodeId: Long, stage: String, errorCode: Long) = Unit
    override fun onCommissioningStageStart(nodeId: Long, stage: String) = Unit
    override fun onNotifyChipConnectionClosed() = Unit
    override fun onCloseBleComplete() = Unit
    override fun onError(error: Throwable) = Unit
    override fun onOpCSRGenerationComplete(csr: ByteArray) = Unit
    override fun onICDRegistrationInfoRequired() = Unit
    override fun onICDRegistrationComplete(errorCode: Long, icdDeviceInfo: ICDDeviceInfo) = Unit
}

class AndroidMatter(context: android.content.Context) {
    private val runtime = ChipRuntime(context)
    private val transport = ChipMatterTransport(runtime)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networks = localNetworks(context).shareIn(scope, SharingStarted.WhileSubscribed(0, 0), replay = 1)
    val port: MatterPort = ConnectedMatterPort(transport, networks).let { delegate ->
        object : MatterPort {
            override suspend fun read(device: Device) = withContext(Dispatchers.IO) { delegate.read(device) }
            override suspend fun execute(device: Device, command: DeviceCommand) = withContext(Dispatchers.IO) { delegate.execute(device, command) }
            override fun updates(device: Device) = delegate.updates(device).flowOn(Dispatchers.IO)
        }
    }
    val commissioning: CommissioningPort = ChipCommissioningPort(runtime, transport)
}
