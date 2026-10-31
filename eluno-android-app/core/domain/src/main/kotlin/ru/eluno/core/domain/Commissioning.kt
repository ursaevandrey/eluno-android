package ru.eluno.core.domain

import kotlinx.coroutines.flow.StateFlow

/** Secrets are ephemeral and must never be placed in saved state, logs, or the journal. */
class CommissioningRequest(
    setupCode: String,
    val name: String,
    val wifiSsid: String? = null,
    val wifiPassword: String? = null,
    val icon: DeviceIcon = DeviceIcon.Automatic,
) {
    val setupCode: String = setupCode.trim().let { code ->
        // '-' and '.' are significant Base38 characters in QR payloads.
        if (code.startsWith("MT:")) code else code.filterNot { it == '-' || it.isWhitespace() }
    }
    override fun toString() = "CommissioningRequest(<redacted>)"
}

enum class CommissioningStage { Idle, Validating, Discovering, Pairing, Attestation, Network, Fabric, Inspecting, Ready, Failed, Cancelled }
data class CommissioningState(val stage: CommissioningStage = CommissioningStage.Idle, val message: String? = null, val devices: List<DeviceId> = emptyList())

interface CommissioningPort {
    fun validate(request: CommissioningRequest)
    suspend fun pair(nodeIdHex: String, request: CommissioningRequest, onStage: (CommissioningStage) -> Unit): String
    suspend fun inspect(nodeIdHex: String, name: String): List<Device>
}

interface CommissioningRepository {
    val state: StateFlow<CommissioningState>
    suspend fun commission(request: CommissioningRequest)
    fun dismiss()
}
