@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.eluno.feature.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.eluno.core.designsystem.ElunoTopBar
import ru.eluno.core.designsystem.ElunoWordmark
import ru.eluno.core.designsystem.NoticePanel
import ru.eluno.core.domain.*

class SettingsViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    fun theme(value: ThemeMode) { viewModelScope.launch { settingsRepository.setTheme(value) } }
    fun dynamic(value: Boolean) { viewModelScope.launch { settingsRepository.setDynamicColors(value) } }
}

@Composable fun SettingsScreen(viewModel: SettingsViewModel, version: String, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { ElunoTopBar(label = stringResource(R.string.toolbar_label), onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineLarge) }
            item { Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThemeCard(ThemeMode.Light, settings.themeMode, viewModel::theme, Modifier.weight(1f)); ThemeCard(ThemeMode.Dark, settings.themeMode, viewModel::theme, Modifier.weight(1f)); ThemeCard(ThemeMode.System, settings.themeMode, viewModel::theme, Modifier.weight(1f)) } }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(stringResource(R.string.dynamic_colors), style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.dynamic_hint), Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Switch(settings.dynamicColors, viewModel::dynamic, enabled = Build.VERSION.SDK_INT >= 31)
                    }
                }
            }
            item { Text(stringResource(R.string.about), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { ElunoWordmark(); Text(stringResource(R.string.version, version), Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { NoticePanel(icon = { Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary) }, title = stringResource(R.string.local_title), text = stringResource(R.string.local_text)) }
        }
    }
}

@Composable private fun ThemeCard(mode: ThemeMode, selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier) {
    val active = mode == selected
    val label = when (mode) { ThemeMode.Light -> stringResource(R.string.theme_light); ThemeMode.Dark -> stringResource(R.string.theme_dark); ThemeMode.System -> stringResource(R.string.theme_system) }
    val icon = when (mode) { ThemeMode.Light -> Icons.Outlined.LightMode; ThemeMode.Dark -> Icons.Outlined.DarkMode; ThemeMode.System -> Icons.Outlined.BrightnessMedium }
    Surface(modifier.heightIn(min = 114.dp).clickable { onSelect(mode) }, color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Box(Modifier.padding(12.dp)) {
            Icon(icon, null, Modifier.size(30.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (active) Icon(Icons.Outlined.Check, stringResource(R.string.selected), Modifier.size(18.dp).align(Alignment.TopEnd), tint = MaterialTheme.colorScheme.primary)
            Text(label, Modifier.align(Alignment.BottomStart), style = MaterialTheme.typography.labelLarge, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}
