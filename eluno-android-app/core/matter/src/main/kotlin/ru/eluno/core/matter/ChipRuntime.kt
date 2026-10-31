package ru.eluno.core.matter

import android.content.Context
import android.util.Base64
import chip.devicecontroller.*
import chip.platform.*
import java.security.SecureRandom

/** One runtime per application. Initialisation happens on the IO dispatcher. */
internal class ChipRuntime(context: Context) {
    private val context = context.applicationContext
    private var platform: AndroidChipPlatform? = null
    val controller: ChipDeviceController by lazy {
        ChipDeviceController.loadJni()
        val store = FabricStore(this.context)
        val ipk = store.get("ElunoIPK")?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: ByteArray(16).also { SecureRandom().nextBytes(it); store.set("ElunoIPK", Base64.encodeToString(it, Base64.NO_WRAP)) }
        val fabric = store.get("ElunoFabricId")?.toLong() ?: (SecureRandom().nextLong().ushr(1).coerceAtLeast(1)).also { store.set("ElunoFabricId", it.toString()) }
        platform = AndroidChipPlatform(
            AndroidBleManager(this.context), AndroidNfcCommissioningManager(), store,
            PreferencesConfigurationManager(this.context),
            NsdManagerServiceResolver(this.context, NsdManagerServiceResolver.NsdManagerResolverAvailState()),
            NsdManagerServiceBrowser(this.context), ChipMdnsCallbackImpl(), DiagnosticDataProviderImpl(this.context),
        )
        ChipDeviceController(ControllerParams.newBuilder().setFabricId(fabric)
            .setControllerVendorId(BuildConfig.MATTER_VENDOR_ID).setIpk(ipk)
            .setFailsafeTimerSeconds(120).setSkipAttestationCertificateValidation(false).build()).also { chip ->
            val certs = this.context.assets.list("paa").orEmpty().filter { it.endsWith(".der") }.associate { name ->
                val cert = this.context.assets.open("paa/$name").use { it.readBytes() }
                Base64.encodeToString(DeviceAttestation.extractSkidFromPaaCert(cert), Base64.NO_WRAP) to cert
            }
            check(certs.isNotEmpty()) { "Отсутствуют сертификаты доверия Matter" }
            chip.setAttestationTrustStoreDelegate { skid -> certs[Base64.encodeToString(skid, Base64.NO_WRAP)] }
            // No delegate may waive failed attestation. SDK's full verifier remains enabled.
            chip.startDnssd()
        }
    }
}
