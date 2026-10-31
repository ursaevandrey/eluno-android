package ru.eluno.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.eluno.core.database.WidgetBindingEntity
import ru.eluno.core.designsystem.ElunoIcons
import ru.eluno.core.designsystem.ElunoTheme
import ru.eluno.core.domain.Capability
import ru.eluno.core.domain.Device
import ru.eluno.core.domain.DeviceId
import ru.eluno.feature.devices.deviceIcon
import kotlin.math.roundToInt

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(Activity.RESULT_CANCELED)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val container = (application as ElunoApplication).container
        val horizontal = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className == HorizontalDeviceWidgetReceiver::class.java.name
        setContent {
            ElunoTheme {
                val allDevices by container.deviceRepository.observeDevices().collectAsStateWithLifecycle(initialValue = null)
                val devices = allDevices?.filter { Capability.OnOff in it.capabilities }
                var selected by rememberSaveable { mutableStateOf<String?>(null) }
                var backgroundEnabled by rememberSaveable { mutableStateOf(true) }
                var opacity by rememberSaveable { mutableIntStateOf(MAX_WIDGET_OPACITY) }
                var colorModeName by rememberSaveable { mutableStateOf(WidgetColorMode.System.name) }
                val colorMode = WidgetColorMode.fromStorage(colorModeName)
                var loaded by rememberSaveable { mutableStateOf(false) }
                var saving by remember { mutableStateOf(false) }
                var saveError by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(widgetId) {
                    if (!loaded) {
                        container.dao.getWidgetBinding(widgetId)?.let { binding ->
                            selected = binding.deviceId
                            backgroundEnabled = binding.backgroundEnabled
                            opacity = normalizeWidgetOpacity(binding.backgroundOpacity)
                            colorModeName = WidgetColorMode.fromStorage(binding.colorMode).name
                        }
                        loaded = true
                    }
                }
                val selectedDevice = devices?.firstOrNull { it.id.value == selected }
                var previewBounds by remember { mutableStateOf<Rect?>(null) }
                var viewportBounds by remember { mutableStateOf<Rect?>(null) }
                val screenColor = MaterialTheme.colorScheme.background
                // The wallpaper is rendered by the system behind this translucent window.
                // Cut out only the visible part of the preview, including while scrolling.
                Box(Modifier.fillMaxSize().drawBehind {
                    val preview = previewBounds
                    val viewport = viewportBounds
                    if (preview == null || viewport == null || !preview.overlaps(viewport)) {
                        drawRect(screenColor)
                    } else {
                        val rounded = Path().apply { addRoundRect(RoundRect(preview, CornerRadius(28.dp.toPx()))) }
                        val visible = Path().apply { addRect(viewport) }
                        val hole = Path().apply { op(rounded, visible, PathOperation.Intersect) }
                        clipPath(hole, ClipOp.Difference) { drawRect(screenColor) }
                    }
                }) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.fillMaxHeight().widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
                            Text(stringResource(R.string.widget_settings_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 20.dp))
                            if (!loaded || devices == null) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            } else {
                                LazyColumn(Modifier.weight(1f).onGloballyPositioned { viewportBounds = it.boundsInRoot() }, verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                    item { WidgetSettingsPreview(selectedDevice, horizontal, backgroundEnabled, opacity, colorMode, onBoundsChanged = { previewBounds = it }) }
                                    item {
                                        DevicePicker(devices, selectedDevice, onSelected = { selected = it.id.value })
                                        if (devices.isEmpty()) {
                                            TextButton(onClick = { startActivity(Intent(this@WidgetConfigActivity, MainActivity::class.java)); finish() }) { Text(stringResource(R.string.widget_open_app)) }
                                        }
                                    }
                                    item {
                                        WidgetBackgroundSettings(backgroundEnabled, opacity, colorMode, onBackgroundChanged = { backgroundEnabled = it }, onOpacityChanged = { opacity = it }, onColorModeChanged = { colorModeName = it.name })
                                    }
                                    if (saveError) item { Text(stringResource(R.string.widget_save_error), color = MaterialTheme.colorScheme.error) }
                                }
                                Surface(
                                    Modifier.align(Alignment.CenterHorizontally).widthIn(max = 460.dp).fillMaxWidth().padding(bottom = 16.dp),
                                    shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant,
                                    tonalElevation = 3.dp, shadowElevation = 4.dp,
                                ) {
                                    Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { finish() }, modifier = Modifier.weight(1f), enabled = !saving) { Text(stringResource(R.string.widget_cancel), style = MaterialTheme.typography.titleMedium) }
                                        VerticalDivider(Modifier.height(28.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                        TextButton(onClick = {
                                            val id = selected ?: return@TextButton
                                            saving = true
                                            saveError = false
                                            scope.launch {
                                                val device = container.deviceRepository.observeDevice(DeviceId(id)).first()
                                                if (device == null || Capability.OnOff !in device.capabilities) {
                                                    saveError = true
                                                    saving = false
                                                    return@launch
                                                }
                                                try {
                                                    container.dao.upsertWidgetBinding(WidgetBindingEntity(widgetId, id, backgroundEnabled, normalizeWidgetOpacity(opacity), colorMode.name))
                                                    updateConfiguredWidget(this@WidgetConfigActivity, widgetId)
                                                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                                    finish()
                                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                    throw cancelled
                                                } catch (_: Exception) {
                                                    saveError = true
                                                    saving = false
                                                }
                                            }
                                        }, enabled = selectedDevice != null && !saving, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.widget_save), style = MaterialTheme.typography.titleMedium) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun DevicePicker(devices: List<Device>, selectedDevice: Device?, onSelected: (Device) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().clickable(enabled = devices.isNotEmpty()) { expanded = true }.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.widget_choose_device), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(selectedDevice?.name ?: stringResource(if (devices.isEmpty()) R.string.widget_no_devices else R.string.widget_select_device), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        WidgetDropdown(expanded = expanded, onDismiss = { expanded = false }) {
            devices.forEach { device ->
                WidgetDropdownItem(device.name, selected = device.id == selectedDevice?.id) {
                    onSelected(device)
                    expanded = false
                }
            }
        }
    }
}

@Composable private fun WidgetBackgroundSettings(enabled: Boolean, opacity: Int, colorMode: WidgetColorMode, onBackgroundChanged: (Boolean) -> Unit, onOpacityChanged: (Int) -> Unit, onColorModeChanged: (WidgetColorMode) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.widget_background), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = onBackgroundChanged)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.widget_opacity), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                Text(stringResource(R.string.widget_opacity_percent, opacity), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
            Slider(value = opacity.toFloat(), onValueChange = { onOpacityChanged(normalizeWidgetOpacity(it.roundToInt())) }, valueRange = MIN_WIDGET_OPACITY.toFloat()..MAX_WIDGET_OPACITY.toFloat(), steps = 1, enabled = enabled)
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(MIN_WIDGET_OPACITY, MID_WIDGET_OPACITY, MAX_WIDGET_OPACITY).forEach { value ->
                    Text(stringResource(R.string.widget_opacity_percent, value), style = MaterialTheme.typography.bodySmall, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            WidgetColorPicker(colorMode, onColorModeChanged)
        }
    }
}

@Composable private fun WidgetColorPicker(mode: WidgetColorMode, onSelected: (WidgetColorMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.widget_colors), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(mode.labelResource()), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        WidgetDropdown(expanded = expanded, onDismiss = { expanded = false }) {
            WidgetColorMode.entries.forEach { option ->
                WidgetDropdownItem(stringResource(option.labelResource()), selected = option == mode) {
                    onSelected(option)
                    expanded = false
                }
            }
        }
    }
}

@Composable private fun WidgetDropdown(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val menuWidth = (LocalConfiguration.current.screenWidthDp.dp - 40.dp).coerceIn(200.dp, 400.dp)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(menuWidth),
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        content = content,
    )
}

@Composable private fun WidgetDropdownItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { this.selected = selected },
        text = { Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal), color = color) },
        trailingIcon = {
            if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            else Spacer(Modifier.size(24.dp))
        },
        onClick = onClick,
    )
}

private fun WidgetColorMode.labelResource(): Int = when (this) {
    WidgetColorMode.System -> R.string.widget_colors_system
    WidgetColorMode.Light -> R.string.widget_colors_light
    WidgetColorMode.Dark -> R.string.widget_colors_dark
}

@Composable private fun WidgetSettingsPreview(device: Device?, horizontal: Boolean, enabled: Boolean, opacity: Int, colorMode: WidgetColorMode, onBoundsChanged: (Rect?) -> Unit) {
    DisposableEffect(Unit) { onDispose { onBoundsChanged(null) } }
    val palette = widgetPalette(colorMode, isSystemInDarkTheme())
    val background = palette.background.copy(alpha = widgetBackgroundAlpha(enabled, opacity))
    val ink = palette.text
    val button = palette.button
    val powerInk = palette.power
    Box(
        Modifier.fillMaxWidth().height(220.dp).onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) },
        contentAlignment = Alignment.Center,
    ) {
        Surface(modifier = if (horizontal) Modifier.widthIn(max = 260.dp).fillMaxWidth(0.85f).height(104.dp) else Modifier.size(164.dp), shape = RoundedCornerShape(28.dp), color = background) {
            if (horizontal) {
                Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PreviewName(device, ink, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    PreviewPower(button, powerInk, 56)
                }
            } else {
                Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    PreviewName(device, ink, Modifier, centered = true)
                    Spacer(Modifier.height(12.dp))
                    PreviewPower(button, powerInk, 72)
                }
            }
        }
    }
}

@Composable private fun PreviewName(device: Device?, ink: Color, modifier: Modifier, centered: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start) {
        Icon(painterResource(device?.let(::deviceIcon) ?: ElunoIcons.Device), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
        Spacer(Modifier.width(6.dp))
        Text(device?.name ?: stringResource(R.string.widget_preview_device), style = MaterialTheme.typography.titleMedium, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
    }
}

@Composable private fun PreviewPower(background: Color, tint: Color, size: Int) {
    Surface(shape = CircleShape, color = background, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_widget_power), contentDescription = null, tint = tint, modifier = Modifier.size(if (size == 56) 26.dp else 32.dp)) }
    }
}
