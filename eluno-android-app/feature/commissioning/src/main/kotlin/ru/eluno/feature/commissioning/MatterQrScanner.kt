package ru.eluno.feature.commissioning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import ru.eluno.core.designsystem.ElunoTopBar

/** Filters unrelated QR codes and repeated camera frames; full Matter validation happens before pairing. */
internal class MatterQrResultGate {
    private var delivered = false

    fun accept(raw: String?): String? {
        if (delivered) return null
        val code = raw?.trim() ?: return null
        if (!code.startsWith("MT:") || code.length <= 3) return null
        delivered = true
        return code
    }
}

@Composable
internal fun MatterQrScanner(onClose: () -> Unit, onManualEntry: () -> Unit, onScanned: (String) -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnScanned by rememberUpdatedState(onScanned)
    val barcodeView = remember(context) {
        BarcodeView(context).apply {
            decoderFactory = matterQrDecoderFactory()
        }
    }
    var message by remember { mutableStateOf("Наведите камеру на QR-код Matter целиком: на устройстве, упаковке или экране другого приложения.") }
    var cameraFailed by remember { mutableStateOf(false) }

    DisposableEffect(barcodeView, lifecycle) {
        var disposed = false
        val results = MatterQrResultGate()
        val listener = object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() = Unit
            override fun previewStopped() = Unit
            override fun cameraClosed() = Unit
            override fun cameraError(error: Exception) {
                if (!disposed) {
                    cameraFailed = true
                    message = "Не удалось открыть камеру. Закройте другие приложения с камерой и попробуйте снова или введите код вручную."
                }
            }
        }
        barcodeView.addStateListener(listener)
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                if (disposed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
                val code = results.accept(result.text)
                if (code == null) {
                    message = "Это не QR-код Matter. Наведите камеру на код подключения устройства."
                    return
                }
                barcodeView.stopDecoding()
                barcodeView.pause()
                currentOnScanned(code)
            }
            override fun possibleResultPoints(resultPoints: List<ResultPoint>) = Unit
        })
        fun resumeCamera() {
            cameraFailed = false
            try {
                barcodeView.resume()
            } catch (_: RuntimeException) {
                cameraFailed = true
                message = "Камера недоступна. Проверьте разрешение в настройках приложения или введите код вручную."
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumeCamera()
                Lifecycle.Event.ON_PAUSE -> barcodeView.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        // addObserver synchronizes with the current lifecycle, including ON_RESUME.
        onDispose {
            disposed = true
            lifecycle.removeObserver(observer)
            barcodeView.stopDecoding()
            barcodeView.pause()
        }
    }

    Scaffold(topBar = { ElunoTopBar(onBack = onClose) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Сканировать QR-код", style = MaterialTheme.typography.headlineMedium)
            Text(message, color = if (cameraFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            AndroidView(
                factory = { barcodeView },
                modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            )
            OutlinedButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
                Text("Ввести код вручную")
            }
        }
    }
}
