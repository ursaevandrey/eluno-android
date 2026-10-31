@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.eluno.feature.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ru.eluno.core.designsystem.*
import ru.eluno.core.domain.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DeviceListItem(val device: Device, val snapshot: DeviceSnapshot?)
sealed interface DevicesUiState {
    data object Loading : DevicesUiState
    data class Content(val items: List<DeviceListItem>) : DevicesUiState
    data class Error(val message: String) : DevicesUiState
}

class DevicesViewModel(private val repository: DeviceRepository) : ViewModel() {
    val uiState: StateFlow<DevicesUiState> = repository.observeDevices()
        .flatMapLatest { devices ->
            if (devices.isEmpty()) flowOf<DevicesUiState>(DevicesUiState.Content(emptyList()))
            else combine(devices.map { device -> repository.observeSnapshot(device.id).map { DeviceListItem(device, it) } }) {
                DevicesUiState.Content(it.toList()) as DevicesUiState
            }
        }
        .catch { emit(DevicesUiState.Error(it.message.orEmpty())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState.Loading)

    fun setPower(id: DeviceId, on: Boolean) { viewModelScope.launch { repository.submit(id, DeviceCommand.SetPower(on)) } }
}

data class DeviceDetailUiState(val device: Device? = null, val snapshot: DeviceSnapshot? = null, val loading: Boolean = true, val message: String? = null)

class DeviceDetailViewModel private constructor(private val repository: DeviceRepository, private val id: DeviceId) : ViewModel() {
    constructor(repository: DeviceRepository, savedStateHandle: SavedStateHandle) : this(repository, DeviceId(checkNotNull(savedStateHandle["deviceId"])))
    private val transientMessage = MutableStateFlow<String?>(null)
    val uiState = combine(repository.observeDevice(id), repository.observeSnapshot(id), transientMessage) { device, snapshot, message ->
        DeviceDetailUiState(device, snapshot, loading = false, message = message)
    }.catch { emit(DeviceDetailUiState(loading = false, message = it.message)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceDetailUiState())

    fun refresh() = launch { repository.refresh(id) }
    fun setPower(value: Boolean) = command(DeviceCommand.SetPower(value))
    fun setBrightness(value: Int) = command(DeviceCommand.SetBrightness(value))
    fun setTemperature(value: Int) = command(DeviceCommand.SetColorTemperature(value))
    fun setColor(value: RgbColor) = command(DeviceCommand.SetColor(value))
    fun rename(value: String) = launch { repository.rename(id, value) }
    fun updatePresentation(name: String, icon: DeviceIcon) = launch {
        val renamed = repository.rename(id, name)
        if (renamed.isFailure) renamed else repository.setIcon(id, icon)
    }
    fun dismissMessage() { transientMessage.value = null }
    private fun command(command: DeviceCommand) = launch { repository.submit(id, command) }
    private fun launch(block: suspend () -> Result<*>) { viewModelScope.launch { block().onFailure { transientMessage.value = it.message } } }
    companion object { fun forDevice(repository: DeviceRepository, id: DeviceId) = DeviceDetailViewModel(repository, id) }
}

@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    onDevice: (DeviceId) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    selectedId: DeviceId? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { ElunoWordmark() },
                actions = { IconButton(onClick = onSettings) { Icon(painterResource(ElunoIcons.Settings), stringResource(R.string.settings)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (state is DevicesUiState.Content && (state as DevicesUiState.Content).items.isNotEmpty()) {
                ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(stringResource(R.string.add_device)) })
            }
        },
    ) { padding ->
        when (val value = state) {
            DevicesUiState.Loading -> Centered(padding) { CircularProgressIndicator() }
            is DevicesUiState.Error -> Centered(padding) { Text(value.message.ifBlank { stringResource(R.string.load_error) }, color = MaterialTheme.colorScheme.error) }
            is DevicesUiState.Content -> {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Text(
                        stringResource(R.string.devices_title),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    if (value.items.isEmpty()) {
                        EmptyDevices(Modifier.weight(1f), onAdd)
                    } else {
                    LazyVerticalGrid(
                        modifier = Modifier.weight(1f),
                        columns = GridCells.Adaptive(156.dp),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 112.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(value.items, key = { it.device.id.value }) { item ->
                            DeviceTile(item, selected = item.device.id == selectedId, onClick = { onDevice(item.device.id) }) { on -> viewModel.setPower(item.device.id, on) }
                        }
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.list_hint), Modifier.padding(6.dp, 10.dp, 6.dp, 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable private fun DeviceTile(item: DeviceListItem, selected: Boolean, onClick: () -> Unit, onPower: (Boolean) -> Unit) {
    val snapshot = item.snapshot
    val active = snapshot?.reported?.isOn == true && snapshot.freshness == Freshness.Fresh && snapshot.availability == Availability.Reachable
    val color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        onClick = onClick,
        color = color,
        shape = MaterialTheme.shapes.large,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().height(236.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(deviceIcon(item.device)), null, Modifier.size(38.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.device.capabilities.any { it is Capability.OnOff }) PowerAction(snapshot, onPower, compact = true)
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
                Text(item.device.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(snapshot.stateLabel(), Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodyMedium, color = stateColor(snapshot, active))
                Spacer(Modifier.weight(1f))
                snapshot?.reported?.brightnessPercent?.let { value ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("$value%", fontSize = 38.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.6).sp)
                            Text(
                                stringResource(R.string.brightness_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        val temperature = snapshot.reported.colorTemperatureKelvin
                        val color = snapshot.reported.color
                        if (temperature != null || color != null) {
                            Column(
                                modifier = Modifier.widthIn(min = 50.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                color?.let { ColorDot(it) }
                                temperature?.let {
                                    Text(
                                        stringResource(R.string.kelvin_short, it),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                } ?: snapshot?.lastConfirmedAt?.let { Text(stringResource(R.string.last_state, if (snapshot.reported.isOn == true) stringResource(R.string.on_lower) else stringResource(R.string.off_lower)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (snapshot?.pendingCommand != null) Text(stringResource(R.string.pending), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable private fun PowerAction(snapshot: DeviceSnapshot?, onPower: (Boolean) -> Unit, compact: Boolean = false) {
    val on = snapshot?.reported?.isOn == true
    val enabled = snapshot?.reported?.isOn != null && snapshot.availability != Availability.Unreachable && snapshot.pendingCommand == null
    val size = if (compact) 48.dp else 66.dp
    val stateDescriptionText = stringResource(if (on) R.string.on else R.string.off)
    FilledIconButton(
        onClick = { onPower(!on) },
        enabled = enabled,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (on) MaterialTheme.colorScheme.primary else Color.Transparent, contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, disabledContainerColor = MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(size).then(if (!on && enabled) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier).semantics { role = Role.Switch; stateDescription = stateDescriptionText },
    ) { Icon(painterResource(ElunoIcons.Power), if (on) stringResource(R.string.turn_off) else stringResource(R.string.turn_on), Modifier.size(if (compact) 26.dp else 34.dp)) }
}

@Composable
fun DeviceDetailScreen(viewModel: DeviceDetailViewModel, onBack: (() -> Unit)?, embedded: Boolean = false) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val device = state.device
    var rename by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable(device?.id?.value) { mutableStateOf(device?.name.orEmpty()) }
    var selectedIcon by rememberSaveable(device?.id?.value) { mutableStateOf(device?.icon ?: DeviceIcon.Automatic) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ElunoTopBar(label = if (embedded) stringResource(R.string.control) else stringResource(R.string.device), onBack = onBack, actions = { IconButton(onClick = { rename = true }, enabled = device != null) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_actions)) } }) },
    ) { padding ->
        when { state.loading -> Centered(padding) { CircularProgressIndicator() }; device == null -> Centered(padding) { Text(stringResource(R.string.device_not_found)) }; else -> DeviceControls(device, state.snapshot, Modifier.padding(padding), viewModel) { rename = true } }
    }
    if (rename) ModalBottomSheet(onDismissRequest = { rename = false }) {
        Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.edit_device_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.edit_device_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name)) }, singleLine = true, isError = name.isBlank(), supportingText = { if (name.isBlank()) Text(stringResource(R.string.name_empty)) })
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.icon_title), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                    lazyItems(deviceIconChoices, key = { it.icon.name }) { choice ->
                        FilterChip(
                            selected = selectedIcon == choice.icon,
                            onClick = { selectedIcon = choice.icon },
                            label = { Text(stringResource(choice.labelRes)) },
                            leadingIcon = { Icon(painterResource(choice.imageRes), contentDescription = null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.cancel)) }; Button(onClick = { viewModel.updatePresentation(name, selectedIcon); rename = false }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } }
        }
    }
    state.message?.let { message -> AlertDialog(onDismissRequest = viewModel::dismissMessage, text = { Text(message) }, confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.close)) } }) }
}

@Composable private fun DeviceControls(device: Device, snapshot: DeviceSnapshot?, modifier: Modifier, viewModel: DeviceDetailViewModel, onRename: () -> Unit) {
    val disabled = snapshot?.pendingCommand != null || snapshot?.availability == Availability.Unreachable
    var colorMode by rememberSaveable(device.id.value) { mutableStateOf(true) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(device.name, style = MaterialTheme.typography.headlineLarge); Text(stringResource(R.string.device_subtitle, device.category.label()), Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { PowerPanel(device, snapshot, viewModel::setPower) }
        snapshot?.lastError?.let { error -> item { ErrorPanel(error.label()) } }
        if (snapshot?.pendingCommand != null) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.pending), style = MaterialTheme.typography.bodySmall) }
        device.capabilities.filterIsInstance<Capability.LevelControl>().firstOrNull()?.let { cap -> item { RangeControl(stringResource(R.string.brightness_label), snapshot?.reported?.brightnessPercent, cap.minPercent, cap.maxPercent, "%", disabled, viewModel::setBrightness) } }
        val temp = device.capabilities.filterIsInstance<Capability.ColorTemperature>().firstOrNull()
        val color = device.capabilities.any { it is Capability.ColorControl }
        if (color && temp != null) item { SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { SegmentedButton(colorMode, { colorMode = true }, SegmentedButtonDefaults.itemShape(0, 2), icon = { Icon(Icons.Outlined.Palette, null, Modifier.size(20.dp)) }) { Text(stringResource(R.string.color)) }; SegmentedButton(!colorMode, { colorMode = false }, SegmentedButtonDefaults.itemShape(1, 2), icon = { Icon(Icons.Outlined.DeviceThermostat, null, Modifier.size(20.dp)) }) { Text(stringResource(R.string.temperature_label)) } } }
        if (temp != null && (!color || !colorMode)) item { RangeControl(stringResource(R.string.temperature_label), snapshot?.reported?.colorTemperatureKelvin, temp.minKelvin, temp.maxKelvin, "K", disabled, viewModel::setTemperature) }
        if (color && colorMode) item { ColorControl(snapshot?.reported?.color, disabled, viewModel::setColor) }
        if (device.category == DeviceCategory.Outlet && snapshot?.availability != Availability.Unreachable) item { Column(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.Power, null, Modifier.size(34.dp)); Text(stringResource(R.string.outlet_title), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.outlet_text), Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { DeviceActions(snapshot?.pendingCommand == null, viewModel::refresh, onRename) }
    }
}

@Composable private fun DeviceActions(refreshEnabled: Boolean, onRefresh: () -> Unit, onRename: () -> Unit) {
    @Composable fun Refresh(modifier: Modifier) = FilledTonalButton(onRefresh, modifier, enabled = refreshEnabled) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.refresh)) }
    @Composable fun Rename(modifier: Modifier) = FilledTonalButton(onRename, modifier) { Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.rename)) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 420.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Refresh(Modifier.fillMaxWidth()); Rename(Modifier.fillMaxWidth()) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Refresh(Modifier.weight(1f)); Rename(Modifier.weight(1f)) }
        }
    }
}

@Composable private fun PowerPanel(device: Device, snapshot: DeviceSnapshot?, onPower: (Boolean) -> Unit) {
    val active = snapshot?.reported?.isOn == true && snapshot.freshness == Freshness.Fresh && snapshot.availability == Availability.Reachable
    Surface(color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(painterResource(deviceIcon(device)), null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) { Text(stringResource(R.string.power), style = MaterialTheme.typography.titleLarge); Text(snapshot.stateLabel(), color = stateColor(snapshot, active)) }
                if (device.capabilities.any { it is Capability.OnOff }) PowerAction(snapshot, onPower)
            }
            Text(confirmedText(snapshot), Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun RangeControl(label: String, confirmed: Int?, min: Int, max: Int, unit: String, disabled: Boolean, onCommit: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var draft by remember(confirmed, dragging) { mutableFloatStateOf((confirmed ?: min).coerceIn(min, max).toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) { Text(label, style = MaterialTheme.typography.titleMedium); Text(if (unit == "%") "${draft.toInt()}%" else "${formatNumber(draft.toInt())} K", fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold) }
        Slider(draft, { dragging = true; draft = it }, Modifier.fillMaxWidth(), enabled = !disabled && confirmed != null, valueRange = min.toFloat()..max.toFloat(), onValueChangeFinished = { dragging = false; onCommit(draft.toInt()) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(if (unit == "%") "$min%" else "${formatNumber(min)} K", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(if (unit == "%") "$max%" else "${formatNumber(max)} K", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        confirmed?.let { Text(stringResource(R.string.confirmed_value, if (unit == "%") "$it%" else "${formatNumber(it)} K"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun ColorControl(selected: RgbColor?, disabled: Boolean, onColor: (RgbColor) -> Unit) {
    val colors = listOf(RgbColor(255,181,64), RgbColor(238,111,111), RgbColor(213,87,164), RgbColor(157,112,224), RgbColor(87,88,225), RgbColor(111,198,195), RgbColor(113,184,117), RgbColor(240,237,255))
    Column { Text(stringResource(R.string.glow_color), style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(12.dp)); colors.chunked(4).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) { row.forEach { rgb -> val chosen = rgb == selected; Box(Modifier.size(54.dp).clip(CircleShape).background(rgb.color()).clickable(enabled = !disabled) { onColor(rgb) }, contentAlignment = Alignment.Center) { if (chosen) Icon(Icons.Outlined.Check, stringResource(R.string.selected_color), tint = if (rgb.red + rgb.green + rgb.blue > 550) Color(0xFF382653) else Color.White) } } }; Spacer(Modifier.height(12.dp)) } }
}

@Composable private fun ErrorPanel(message: String) { Surface(color = LocalElunoColors.current.warningContainer, shape = MaterialTheme.shapes.medium) { Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.WarningAmber, null, tint = LocalElunoColors.current.warning); Column { Text(stringResource(R.string.unavailable_title), style = MaterialTheme.typography.titleMedium); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }
@Composable
private fun EmptyDevices(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(top = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Devices, null, Modifier.size(54.dp))
            Text(
                stringResource(R.string.empty_devices),
                modifier = Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.empty_hint),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onAdd, modifier = Modifier.padding(top = 24.dp)) {
                Icon(Icons.Outlined.Add, null)
                Text(stringResource(R.string.add_device), Modifier.padding(start = 8.dp))
            }
        }
    }
}
@Composable private fun Centered(padding: PaddingValues, content: @Composable () -> Unit) { Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { content() } }
@Composable private fun DeviceCategory.label() = when (this) { DeviceCategory.Outlet -> stringResource(R.string.category_outlet); DeviceCategory.Light -> stringResource(R.string.category_light); DeviceCategory.LightStrip -> stringResource(R.string.category_strip); DeviceCategory.Unknown -> stringResource(R.string.category_unknown) }
@Composable private fun DeviceSnapshot?.stateLabel(): String = when { this == null -> stringResource(R.string.unknown_state); availability == Availability.Unreachable -> stringResource(R.string.no_connection); freshness == Freshness.Unknown || reported.isOn == null -> stringResource(R.string.unknown_state); freshness == Freshness.Stale -> stringResource(R.string.stale_state); reported.isOn == true -> stringResource(R.string.on); else -> stringResource(R.string.off) }
@Composable private fun stateColor(snapshot: DeviceSnapshot?, active: Boolean) = when { snapshot?.availability == Availability.Unreachable || snapshot?.freshness == Freshness.Stale -> LocalElunoColors.current.warning; active -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
@Composable private fun confirmedText(snapshot: DeviceSnapshot?): String = when { snapshot?.availability == Availability.Unreachable -> stringResource(R.string.last_confirmed_off); snapshot?.freshness == Freshness.Stale -> stringResource(R.string.unknown_stale); snapshot?.pendingCommand != null -> stringResource(R.string.waiting_confirmation); snapshot?.lastConfirmedAt != null -> stringResource(R.string.last_update, DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(snapshot.lastConfirmedAt)); else -> stringResource(R.string.last_confirmed) }
fun deviceIcon(device: Device): Int = when (device.icon) {
    DeviceIcon.Automatic -> when (device.category) {
        DeviceCategory.Outlet -> ElunoIcons.Outlet
        DeviceCategory.Light -> ElunoIcons.Light
        DeviceCategory.LightStrip -> ElunoIcons.LedStrip
        DeviceCategory.Unknown -> ElunoIcons.Device
    }
    DeviceIcon.Lightbulb -> ElunoIcons.Lamp
    DeviceIcon.LightMode -> ElunoIcons.Light
    DeviceIcon.LightStrip -> ElunoIcons.LedStrip
    DeviceIcon.Outlet -> ElunoIcons.Outlet
    DeviceIcon.Device -> ElunoIcons.Device
}
private data class DeviceIconChoice(val icon: DeviceIcon, val labelRes: Int, val imageRes: Int)
private val deviceIconChoices = listOf(
    DeviceIconChoice(DeviceIcon.Automatic, R.string.icon_auto, ElunoIcons.Device),
    DeviceIconChoice(DeviceIcon.Lightbulb, R.string.icon_lamp, ElunoIcons.Lamp),
    DeviceIconChoice(DeviceIcon.LightMode, R.string.icon_light, ElunoIcons.Light),
    DeviceIconChoice(DeviceIcon.LightStrip, R.string.icon_strip, ElunoIcons.LedStrip),
    DeviceIconChoice(DeviceIcon.Outlet, R.string.icon_outlet, ElunoIcons.Outlet),
    DeviceIconChoice(DeviceIcon.Device, R.string.icon_device, ElunoIcons.Device),
)
@Composable private fun DeviceError.label() = when (this) { DeviceError.DeviceNotFound -> stringResource(R.string.device_not_found); DeviceError.Unavailable -> stringResource(R.string.unavailable_detail); is DeviceError.InvalidValue -> message; is DeviceError.Transport -> message; is DeviceError.UnsupportedCapability -> stringResource(R.string.unsupported_capability, capability) }
private fun RgbColor.color() = Color(red, green, blue)
private fun formatNumber(value: Int) = String.format("%,d", value).replace(',', ' ')
@Composable private fun ColorDot(rgb: RgbColor) { Box(Modifier.size(28.dp).clip(CircleShape).background(rgb.color())) }
