@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.eluno.feature.commissioning

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import ru.eluno.core.designsystem.ElunoTopBar
import ru.eluno.core.designsystem.ElunoIcons
import ru.eluno.core.domain.*

class CommissioningViewModel(private val repository: CommissioningRepository) : ViewModel() {
    val state = repository.state
    private var operation: Job? = null
    init { repository.dismiss() }
    fun connect(request: CommissioningRequest) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch { repository.commission(request) }
    }
    fun cancel() { operation?.cancel() }
    fun consume() = repository.dismiss()
}

@Composable fun CommissioningScreen(vm: CommissioningViewModel, onBack: () -> Unit, onAdded: (DeviceId) -> Unit) {
    androidx.activity.compose.BackHandler { vm.cancel(); onBack() }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Intentionally not rememberSaveable: neither pairing codes nor Wi-Fi secrets survive recreation.
    var code by remember { mutableStateOf("") }
    var manualCode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var selectedIcon by remember { mutableStateOf(DeviceIcon.Automatic) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var wifi by remember { mutableStateOf(false) }
    var ssidEdited by remember { mutableStateOf(false) }
    var wifiMessage by remember { mutableStateOf<String?>(null) }
    var wifiSettingsAction by remember { mutableStateOf<String?>(null) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var cameraPermissionDenied by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionDenied = !granted
        if (granted) showScanner = true
    }
    val busy = state.stage in setOf(CommissioningStage.Validating, CommissioningStage.Discovering, CommissioningStage.Pairing, CommissioningStage.Attestation, CommissioningStage.Network, CommissioningStage.Fabric, CommissioningStage.Inspecting)
    fun fillCurrentWifiName() {
        // A late permission result must not overwrite manual input or an in-flight request.
        if (!wifi || busy || ssidEdited) return
        wifiSettingsAction = null
        when (val network = currentWifiNetwork(context)) {
            is CurrentWifiNetwork.Connected -> {
                if (ssid != network.ssid) password = ""
                ssid = network.ssid
                wifiMessage = null
            }
            CurrentWifiNetwork.PermissionRequired -> {
                wifiMessage = "Чтобы подставить текущую сеть, Android требует доступ к точному местоположению. Разрешите его в настройках или введите имя сети вручную."
                wifiSettingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            }
            CurrentWifiNetwork.LocationDisabled -> {
                wifiMessage = "Включите геолокацию, чтобы определить текущую Wi-Fi-сеть, или введите её имя вручную."
                wifiSettingsAction = Settings.ACTION_LOCATION_SOURCE_SETTINGS
            }
            CurrentWifiNetwork.Unavailable -> {
                wifiMessage = "Не удалось определить текущую Wi-Fi-сеть. Подключите телефон к нужной сети или введите её имя вручную."
            }
        }
    }
    val wifiPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        fillCurrentWifiName()
    }
    fun requestCurrentWifiName() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fillCurrentWifiName()
        } else {
            wifiMessage = "Доступ к местоположению нужен Android для определения имени Wi-Fi-сети."
            wifiPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (wifi && ssid.isEmpty() && !ssidEdited) fillCurrentWifiName()
    }
    fun connect() {
        if (wifi) {
            val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            if (adapter?.isEnabled != true) { permissionMessage = "Включите Bluetooth на телефоне и повторите подключение."; return }
            if (Build.VERSION.SDK_INT < 31 && context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled != true) {
                permissionMessage = "Для поиска Bluetooth-устройств на этой версии Android включите геолокацию."; return
            }
        }
        vm.connect(CommissioningRequest(code, name, if (wifi) ssid else null, if (wifi) password else null, selectedIcon))
        code = ""; password = ""
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) connect() else permissionMessage = "Для подключения нового Wi-Fi-устройства разрешите доступ к Bluetooth в настройках приложения."
    }
    LaunchedEffect(state.stage, state.devices) {
        if (state.stage == CommissioningStage.Ready) state.devices.firstOrNull()?.let { vm.consume(); onAdded(it) }
    }
    if (showScanner && !busy) {
        MatterQrScanner(
            onClose = { showScanner = false },
            onManualEntry = {
                showScanner = false
                manualCode = true
                scanMessage = null
            },
            onScanned = { scanned ->
                code = scanned
                scanMessage = "QR-код считан. Нажмите «Подключить», когда форма заполнена."
                showScanner = false
            },
        )
        return
    }
    Scaffold(topBar = { ElunoTopBar(onBack = { vm.cancel(); onBack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Подключить устройство", style = MaterialTheme.typography.headlineLarge)
            Text("Включите режим подключения на устройстве Matter. Если оно уже добавлено в другой дом, откройте общий доступ там и получите новый код.")
            OutlinedTextField(name, { name = it }, label = { Text("Название устройства") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Иконка", style = MaterialTheme.typography.titleMedium)
                Text("Выберите значок для карточки устройства.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                    items(iconChoices, key = { it.icon.name }) { choice ->
                        FilterChip(
                            selected = selectedIcon == choice.icon,
                            onClick = { selectedIcon = choice.icon },
                            enabled = !busy,
                            label = { Text(choice.label) },
                            leadingIcon = { Icon(painterResource(choice.imageRes), contentDescription = null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (manualCode) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; scanMessage = null },
                        placeholder = { Text("Код Metter") },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Код Metter" },
                        enabled = !busy,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    TextButton(onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        manualCode = false
                        scanMessage = if (code.isNotBlank()) "Код введён. Можно подключить устройство." else null
                    }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Сканировать QR") }
                } else {
                    Button(onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        scanMessage = null
                        cameraPermissionDenied = false
                        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                            scanMessage = "Камера недоступна. Введите код с устройства вручную."
                        } else if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            showScanner = true
                        } else cameraPermission.launch(Manifest.permission.CAMERA)
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(painterResource(ElunoIcons.QrScanner), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Сканировать QR-код")
                    }
                    TextButton(onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        manualCode = true
                        scanMessage = null
                        cameraPermissionDenied = false
                    }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("ввести вручную") }
                }
            }
            scanMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!manualCode && cameraPermissionDenied) {
                Text("Для сканирования разрешите доступ к камере. Код можно ввести вручную.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text("Открыть настройки доступа") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Настроить Wi-Fi через Bluetooth", Modifier.weight(1f))
                Switch(wifi, {
                    wifi = it
                    if (it && ssid.isEmpty() && !ssidEdited) requestCurrentWifiName()
                }, enabled = !busy)
            }
            if (wifi) {
                OutlinedTextField(ssid, {
                    if (ssid != it) password = ""
                    ssid = it
                    ssidEdited = true
                    wifiMessage = null
                    wifiSettingsAction = null
                }, label = { Text("Имя сети Wi-Fi") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true)
                wifiMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                wifiSettingsAction?.let { action ->
                    TextButton(onClick = {
                        val intent = if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                            Intent(action, Uri.fromParts("package", context.packageName, null))
                        } else Intent(action)
                        context.startActivity(intent)
                    }, enabled = !busy) { Text("Открыть настройки") }
                }
                TextButton(onClick = {
                    ssidEdited = false
                    requestCurrentWifiName()
                }, enabled = !busy) { Text("Подставить текущую сеть") }
                OutlinedTextField(password, { password = it }, label = { Text("Пароль Wi-Fi") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation())
            } else Text("Устройство должно быть уже подключено к той же Wi-Fi или Ethernet-сети, что и телефон.")
            Text("Поддерживаются Matter over Wi-Fi и Ethernet. Thread пока не поддерживается.", style = MaterialTheme.typography.bodySmall)
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.stage.label())
                TextButton(onClick = { vm.cancel(); code = ""; password = "" }) { Text("Отменить") }
            } else {
                Button(onClick = {
                    permissionMessage = null
                    val required = if (!wifi) emptyList() else if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    val missing = required.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (missing.isEmpty()) connect() else launcher.launch(missing.toTypedArray())
                }, enabled = code.isNotBlank() && name.isNotBlank() && (!wifi || ssid.isNotBlank()), modifier = Modifier.fillMaxWidth()) { Text("Подключить") }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            permissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private data class IconChoice(val icon: DeviceIcon, val label: String, val imageRes: Int)

private val iconChoices = listOf(
    IconChoice(DeviceIcon.Automatic, "Авто", ElunoIcons.Device),
    IconChoice(DeviceIcon.Lightbulb, "Лампа", ElunoIcons.Lamp),
    IconChoice(DeviceIcon.LightMode, "Свет", ElunoIcons.Light),
    IconChoice(DeviceIcon.LightStrip, "Лента", ElunoIcons.LedStrip),
    IconChoice(DeviceIcon.Outlet, "Розетка", ElunoIcons.Outlet),
    IconChoice(DeviceIcon.Device, "Устройство", ElunoIcons.Device),
)

private fun CommissioningStage.label() = when (this) {
    CommissioningStage.Validating -> "Проверяем код"
    CommissioningStage.Discovering -> "Ищем устройство"
    CommissioningStage.Pairing -> "Устанавливаем защищённое соединение"
    CommissioningStage.Attestation -> "Проверяем подлинность устройства"
    CommissioningStage.Network -> "Настраиваем сеть"
    CommissioningStage.Fabric -> "Добавляем устройство в дом"
    CommissioningStage.Inspecting -> "Проверяем возможности устройства"
    else -> ""
}
