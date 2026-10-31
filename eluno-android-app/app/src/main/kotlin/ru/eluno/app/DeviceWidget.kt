package ru.eluno.app

import ru.eluno.feature.widgets.*

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.ForegroundInfo
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.eluno.core.domain.*
import ru.eluno.feature.devices.deviceIcon

private val widgetIdKey = ActionParameters.Key<Int>("widgetId")
private val deviceIdKey = ActionParameters.Key<String>("deviceId")

open class DeviceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    protected open val horizontal = false
    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        (context.applicationContext as ElunoApplication).container.widgetRepository.unbind(widgetId)
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as ElunoApplication).container
        container.awaitInitialization()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val presentations = observeWidgetPresentation(
            container.widgetRepository.observeBinding(appWidgetId),
            container.deviceRepository,
        )
        val initialPresentation = presentations.first()
        provideContent {
            val presentation by presentations.collectAsState(initial = initialPresentation)
            val deviceId = presentation.deviceId
            val workInfos by remember(deviceId) {
                deviceId?.let { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("widget-power-${it.value}") }
                    ?: kotlinx.coroutines.flow.flowOf(emptyList())
            }.collectAsState(initial = emptyList())
            val working = workInfos.any { !it.state.isFinished }
            WidgetContent(context, appWidgetId, presentation.device, presentation.pending, localNetworkAllowed(context), horizontal, working, presentation.backgroundEnabled, presentation.backgroundOpacity, presentation.colorMode)
        }
    }
}

class HorizontalDeviceWidget : DeviceWidget() { override val horizontal = true }

suspend fun updateDeviceWidget(context: Context, widgetId: Int) {
    try {
    val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId) ?: return
    val widget = when (info.provider.className) {
        DeviceWidgetReceiver::class.java.name -> DeviceWidget()
        HorizontalDeviceWidgetReceiver::class.java.name -> HorizontalDeviceWidget()
        else -> return
    }
    widget.update(context, GlanceAppWidgetManager(context).getGlanceIdBy(widgetId))
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (error: Exception) { android.util.Log.w("ElunoWidget", "Widget update failed", error) }
}

suspend fun updateConfiguredWidget(context: Context, widgetId: Int) {
    val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId) ?: return
    val widget = when (info.provider.className) {
        DeviceWidgetReceiver::class.java.name -> DeviceWidget()
        HorizontalDeviceWidgetReceiver::class.java.name -> HorizontalDeviceWidget()
        else -> return
    }
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)
    // update() can enqueue a background session; render the saved state directly while
    // the configuration activity is still visible, then publish it to the launcher.
    widget.update(context, glanceId)
    val views = widget.compose(context, id = glanceId)
    AppWidgetManager.getInstance(context).updateAppWidget(widgetId, views)
}

@Composable private fun WidgetContent(context: Context, widgetId: Int, device: Device?, pending: Boolean, allowed: Boolean, horizontal: Boolean, working: Boolean, backgroundEnabled: Boolean, backgroundOpacity: Int, colorMode: WidgetColorMode) {
    val surface = widgetBackgroundProvider(colorMode, backgroundEnabled, backgroundOpacity)
    val ink = widgetColorProvider(colorMode, LightWidgetPalette.text, DarkWidgetPalette.text)
    val radius = if (android.os.Build.VERSION.SDK_INT >= 31) android.R.dimen.system_app_widget_background_radius else 0
    val background = if (radius != 0) GlanceModifier.fillMaxSize().background(surface).cornerRadius(radius) else GlanceModifier.fillMaxSize().background(surface).cornerRadius(28.dp)
    if (device == null) {
        val configure = Intent(context, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        Column(background.padding(16.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(context.getString(R.string.widget_missing), style = TextStyle(color = ink, fontSize = 14.sp), maxLines = 2)
            Text(context.getString(R.string.widget_configure), GlanceModifier.clickable(actionStartActivity(configure)).padding(vertical = 8.dp), style = TextStyle(color = ink, fontSize = 14.sp))
        }
        return
    }
    val openDevice = Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_WIDGET_DEVICE
        data = "eluno://widget/$widgetId".toUri()
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    if (horizontal) {
        Row(background.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Vertical.CenterVertically, horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
            Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.Vertical.CenterVertically) { WidgetName(device, openDevice, ink) }
            Spacer(GlanceModifier.width(8.dp))
            PowerButton(context, widgetId, device, pending, allowed, 56, working, colorMode)
        }
    } else {
        Column(background.padding(horizontal = 16.dp, vertical = 16.dp), horizontalAlignment = Alignment.Horizontal.CenterHorizontally, verticalAlignment = Alignment.Vertical.CenterVertically) {
            WidgetName(device, openDevice, ink, centered = true)
            Spacer(GlanceModifier.height(12.dp))
            PowerButton(context, widgetId, device, pending, allowed, 72, working, colorMode)
        }
    }
}

@Composable private fun WidgetName(device: Device, openDevice: Intent, ink: ColorProvider, centered: Boolean = false) {
    Row(
        GlanceModifier.clickable(actionStartActivity(openDevice)),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = if (centered) Alignment.Horizontal.CenterHorizontally else Alignment.Horizontal.Start,
    ) {
        androidx.glance.Image(
            androidx.glance.ImageProvider(deviceIcon(device)), contentDescription = null,
            modifier = GlanceModifier.size(20.dp), colorFilter = androidx.glance.ColorFilter.tint(ink),
        )
        Spacer(GlanceModifier.width(6.dp))
        Text(device.name, if (centered) GlanceModifier else GlanceModifier.defaultWeight(), style = TextStyle(color = ink, fontSize = 16.sp, fontWeight = androidx.glance.text.FontWeight.Medium, textAlign = if (centered) androidx.glance.text.TextAlign.Center else androidx.glance.text.TextAlign.Start), maxLines = 2)
    }
}

@Composable private fun PowerButton(context: Context, widgetId: Int, device: Device, pending: Boolean, allowed: Boolean, size: Int, working: Boolean, colorMode: WidgetColorMode) {
    val enabled = allowed && Capability.OnOff in device.capabilities && !pending && !working
    val params = actionParametersOf(widgetIdKey to widgetId, deviceIdKey to device.id.value)
    val label = when {
        working || pending -> context.getString(R.string.widget_pending)
        else -> context.getString(R.string.widget_toggle, device.name)
    }
    val bg = widgetColorProvider(colorMode, LightWidgetPalette.button, DarkWidgetPalette.button)
    val tint = widgetColorProvider(colorMode, LightWidgetPalette.power, DarkWidgetPalette.power)
    val ripple = when (colorMode) {
        WidgetColorMode.System -> R.drawable.widget_power_ripple
        WidgetColorMode.Light -> R.drawable.widget_power_ripple_light
        WidgetColorMode.Dark -> R.drawable.widget_power_ripple_dark
    }
    val modifier = GlanceModifier.size(size.dp).background(bg).cornerRadius((size / 2).dp)
    Box(if (enabled) modifier.clickable(actionRunCallback<PowerAction>(params), rippleOverride = ripple) else modifier, contentAlignment = Alignment.Center) {
        androidx.glance.Image(androidx.glance.ImageProvider(R.drawable.ic_widget_power), contentDescription = label, modifier = GlanceModifier.size(if (size == 56) 26.dp else 32.dp), colorFilter = androidx.glance.ColorFilter.tint(tint))
    }
}

class PowerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val createdAt = System.currentTimeMillis()
        val createdElapsed = android.os.SystemClock.elapsedRealtime()
        val widgetId = parameters[widgetIdKey] ?: return
        val deviceId = parameters[deviceIdKey] ?: return
        val container = (context.applicationContext as ElunoApplication).container
        if (GlanceAppWidgetManager(context).getAppWidgetId(glanceId) != widgetId || container.widgetRepository.getBinding(widgetId)?.deviceId?.value != deviceId || !localNetworkAllowed(context)) return
        val builder = OneTimeWorkRequestBuilder<WidgetPowerWorker>()
            .setInputData(workDataOf("widgetId" to widgetId, "deviceId" to deviceId, "created" to createdAt, "createdElapsed" to createdElapsed))
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // A network constraint lets JobScheduler grant expedited work network access
            // during Battery Saver. Avoid it otherwise so offline LANs still work.
            if (context.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode) {
                builder.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            }
        }
        builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        val request = builder.build()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WorkManager.getInstance(context).enqueueUniqueWork("widget-power-$deviceId", ExistingWorkPolicy.KEEP, request).result.get() }
    }
}

class WidgetPowerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = "widget_control"
        val manager = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(channelId, applicationContext.getString(R.string.widget_notification_channel), android.app.NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_widget_power)
            .setContentTitle(applicationContext.getString(R.string.widget_notification_title))
            .setOngoing(true)
            .build()
        return ForegroundInfo(1201, notification)
    }

    override suspend fun doWork(): Result {
        val widgetId = inputData.getInt("widgetId", -1)
        val deviceId = inputData.getString("deviceId") ?: return Result.failure()
        val elapsed = android.os.SystemClock.elapsedRealtime() - inputData.getLong("createdElapsed", -1)
        val age = System.currentTimeMillis() - inputData.getLong("created", 0)
        val remaining = 15_000 - maxOf(elapsed, age)
        if (elapsed < 0 || age < 0 || remaining <= 0 || !localNetworkAllowed(applicationContext)) return Result.failure()
        val container = (applicationContext as ElunoApplication).container
        return try {
            withTimeout(remaining) {
                container.awaitInitialization()
                if (container.widgetRepository.getBinding(widgetId)?.deviceId?.value != deviceId) return@withTimeout Result.failure()
                val result = executeWidgetToggle(container.deviceRepository, DeviceId(deviceId)) {
                    localNetworkAllowed(applicationContext) && container.widgetRepository.getBinding(widgetId)?.deviceId?.value == deviceId
                }
                if (result.isSuccess) Result.success() else Result.failure()
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            Result.failure()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure() }
        finally { withContext(kotlinx.coroutines.NonCancellable) { updateDeviceWidget(applicationContext, widgetId) } }
    }

}

open class DeviceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DeviceWidget()
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        super.onRestored(context, oldWidgetIds, newWidgetIds)
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val bindings = (context.applicationContext as ElunoApplication).container.widgetRepository
                bindings.restore(oldWidgetIds.toList(), newWidgetIds.toList())
                newWidgetIds.forEach { updateDeviceWidget(context, it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("ElunoWidget", "Widget restore failed", error)
            } finally { pending.finish() }
        }
    }
}

class HorizontalDeviceWidgetReceiver : DeviceWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HorizontalDeviceWidget()
}
