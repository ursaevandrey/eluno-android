package ru.eluno.app

import ru.eluno.feature.widgets.*

import android.os.Bundle
import android.appwidget.AppWidgetManager
import android.content.Intent
import kotlinx.coroutines.flow.first
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable
import ru.eluno.core.designsystem.ElunoTheme
import ru.eluno.core.domain.AppSettings
import ru.eluno.core.domain.DeviceId
import ru.eluno.core.domain.ThemeMode
import ru.eluno.feature.commissioning.CommissioningScreen
import ru.eluno.feature.commissioning.CommissioningViewModel
import ru.eluno.feature.devices.*
import ru.eluno.feature.settings.SettingsScreen
import ru.eluno.feature.settings.SettingsViewModel

@Serializable data object DevicesRoute
@Serializable data class DeviceRoute(val deviceId: String)
@Serializable data object AddDeviceRoute
@Serializable data object SettingsRoute
private enum class Destination { Devices, Detail, Add, Settings }
internal const val ACTION_OPEN_WIDGET_DEVICE = "ru.eluno.app.action.OPEN_WIDGET_DEVICE"
private data class WidgetOpenRequest(val widgetId: Int, val sequence: Int)

class MainActivity : ComponentActivity() {
    private var widgetRequest by mutableStateOf<WidgetOpenRequest?>(null)
    private var requestSequence = 0

    private fun handleWidgetIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_WIDGET_DEVICE) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) widgetRequest = WidgetOpenRequest(id, ++requestSequence)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (localNetworkAllowed(this)) (application as ElunoApplication).container.foreground()
    }
    override fun onStop() {
        (application as ElunoApplication).container.background()
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleWidgetIntent(intent)
        val container = (application as ElunoApplication).container
        val layoutInfo = WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this)
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dark = when (settings.themeMode) { ThemeMode.System -> systemDark; ThemeMode.Light -> false; ThemeMode.Dark -> true }
            val features by produceState<List<DisplayFeature>>(emptyList()) { layoutInfo.collectLatest { value = it.displayFeatures } }
            ElunoTheme(darkTheme = dark, dynamicColor = settings.dynamicColors) { LocalNetworkPermissionGate(container) { ElunoNavigation(container, features, widgetRequest) } }
        }
    }
}

@Composable private fun ElunoNavigation(container: AppContainer, displayFeatures: List<DisplayFeature>, widgetRequest: WidgetOpenRequest?) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var destinationName by rememberSaveable { mutableStateOf(Destination.Devices.name) }
    var navigationRevision by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(widgetRequest) {
        val request = widgetRequest ?: return@LaunchedEffect
        val id = container.widgetRepository.getBinding(request.widgetId)?.deviceId
        val device = id?.let { container.deviceRepository.observeDevice(it).first() }
        selectedId = device?.id?.value
        destinationName = if (device == null) Destination.Devices.name else Destination.Detail.name
        navigationRevision++
    }
    val destination = Destination.valueOf(destinationName)
    key(navigationRevision) { BoxWithConstraints(Modifier.fillMaxSize()) {
        // Современные Fold-устройства в раскрытом виде могут давать около 750–835 dp
        // полезной ширины из-за плотности и системных областей, оставаясь пригодными
        // для двух независимых панелей.
        val expanded = maxWidth >= 720.dp && LocalDensity.current.fontScale < 1.6f
        if (expanded) {
            WideNavigation(container, displayFeatures, maxWidth, selectedId, destination, onSelect = { selectedId = it.value; destinationName = Destination.Detail.name }, onDestination = { destinationName = it.name })
        } else {
            CompactNavigation(container, selectedId, destination, onSelect = { selectedId = it.value; destinationName = Destination.Detail.name }, onDestination = { destinationName = it.name })
        }
    } }
}

@Composable private fun CompactNavigation(container: AppContainer, selectedId: String?, destination: Destination, onSelect: (DeviceId) -> Unit, onDestination: (Destination) -> Unit) {
    val nav = rememberNavController()
    val start = when (destination) { Destination.Detail -> selectedId?.let { DeviceRoute(it) } ?: DevicesRoute; Destination.Add -> AddDeviceRoute; Destination.Settings -> SettingsRoute; Destination.Devices -> DevicesRoute }
    NavHost(navController = nav, startDestination = start) {
        composable<DevicesRoute> {
            LaunchedEffect(Unit) { onDestination(Destination.Devices) }
            val vm: DevicesViewModel = viewModel(factory = factory { DevicesViewModel(container.deviceRepository) })
            DevicesScreen(vm, { onSelect(it); nav.navigate(DeviceRoute(it.value)) }, { onDestination(Destination.Add); nav.navigate(AddDeviceRoute) }, { onDestination(Destination.Settings); nav.navigate(SettingsRoute) })
        }
        composable<DeviceRoute> { entry ->
            val route = entry.toRoute<DeviceRoute>()
            val vm: DeviceDetailViewModel = viewModel(key = route.deviceId, factory = viewModelFactory { initializer { DeviceDetailViewModel(container.deviceRepository, createSavedStateHandle()) } })
            val backToDevices = {
                onDestination(Destination.Devices)
                if (!nav.popBackStack()) nav.navigate(DevicesRoute) { launchSingleTop = true }
            }
            BackHandler(onBack = backToDevices)
            DeviceDetailScreen(vm, onBack = backToDevices)
        }
        composable<AddDeviceRoute> {
            val backToDevices = {
                onDestination(Destination.Devices)
                if (!nav.popBackStack()) nav.navigate(DevicesRoute) { launchSingleTop = true }
            }
            BackHandler(onBack = backToDevices)
            AddScreen(container, backToDevices) { onSelect(it); nav.navigate(DeviceRoute(it.value)) { popUpTo<AddDeviceRoute> { inclusive = true } } }
        }

        composable<SettingsRoute> {
            val vm: SettingsViewModel = viewModel(factory = factory { SettingsViewModel(container.settingsRepository) })
            val backToDevices = {
                onDestination(Destination.Devices)
                if (!nav.popBackStack()) nav.navigate(DevicesRoute) { launchSingleTop = true }
            }
            BackHandler(onBack = backToDevices)
            SettingsScreen(vm, BuildConfig.VERSION_NAME, backToDevices)
        }
    }
}

@Composable private fun WideNavigation(container: AppContainer, displayFeatures: List<DisplayFeature>, containerWidth: Dp, selectedId: String?, destination: Destination, onSelect: (DeviceId) -> Unit, onDestination: (Destination) -> Unit) {
    BackHandler(enabled = destination != Destination.Devices) {
        onDestination(if (destination == Destination.Detail || selectedId == null) Destination.Devices else Destination.Detail)
    }

    if (selectedId == null) {
        Box(Modifier.fillMaxSize()) {
            when (destination) {
                Destination.Add -> AddScreen(container, { onDestination(Destination.Devices) }, onSelect)
                Destination.Settings -> {
                    val vm: SettingsViewModel = viewModel(factory = factory { SettingsViewModel(container.settingsRepository) })
                    SettingsScreen(vm, BuildConfig.VERSION_NAME) { onDestination(Destination.Devices) }
                }
                Destination.Devices, Destination.Detail -> {
                    val vm: DevicesViewModel = viewModel(factory = factory { DevicesViewModel(container.deviceRepository) })
                    DevicesScreen(vm, onSelect, { onDestination(Destination.Add) }, { onDestination(Destination.Settings) })
                }
            }
        }
        return
    }

    val fold = displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL && it.isSeparating }
    val density = LocalDensity.current
    val gutter = fold?.bounds?.width()?.takeIf { it > 0 }?.let { with(density) { it.toDp() } } ?: 24.dp
    val leftPaneWidth = fold?.bounds?.left?.let { with(density) { it.toDp() } }?.coerceIn(280.dp, containerWidth - gutter - 280.dp)
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.then(if (leftPaneWidth != null) Modifier.width(leftPaneWidth) else Modifier.weight(1f)).fillMaxHeight()) {
            val vm: DevicesViewModel = viewModel(factory = factory { DevicesViewModel(container.deviceRepository) })
            DevicesScreen(vm, onSelect, { onDestination(Destination.Add) }, { onDestination(Destination.Settings) }, DeviceId(selectedId))
        }
        HingeGutter(gutter)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (destination) {
                Destination.Detail -> {
                    val vm: DeviceDetailViewModel = viewModel(key = selectedId, factory = factory { DeviceDetailViewModel.forDevice(container.deviceRepository, DeviceId(selectedId)) })
                    DeviceDetailScreen(vm, onBack = null, embedded = true)
                }
                Destination.Add -> AddScreen(container, { onDestination(Destination.Detail) }, onSelect)
                Destination.Settings -> {
                    val vm: SettingsViewModel = viewModel(factory = factory { SettingsViewModel(container.settingsRepository) })
                    SettingsScreen(vm, BuildConfig.VERSION_NAME) { onDestination(Destination.Detail) }
                }
                Destination.Devices -> SelectDevicePlaceholder()
            }
        }
    }
}

@Composable private fun HingeGutter(width: Dp) {
    Box(Modifier.width(width.coerceAtLeast(1.dp)).fillMaxHeight().background(MaterialTheme.colorScheme.background))
}

@Composable private fun SelectDevicePlaceholder() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.TouchApp, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(stringResource(R.string.select_device), Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun <T : ViewModel> factory(create: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}

@Composable private fun AddScreen(container: AppContainer, onBack: () -> Unit, onAdded: (DeviceId) -> Unit) {
    val vm: CommissioningViewModel = viewModel(factory = factory { CommissioningViewModel(container.commissioningRepository) })
    CommissioningScreen(vm, onBack, onAdded)
}
