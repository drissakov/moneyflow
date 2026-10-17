package com.moneyflow.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moneyflow.app.MoneyFlowApplication
import com.moneyflow.app.planning.PlanningReminders

@Composable
internal fun PlanningReminderControl(userId: String) {
    val context = LocalContext.current
    val repository = (context.applicationContext as MoneyFlowApplication).repository
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var enabled by remember(userId) { mutableStateOf(PlanningReminders.isEnabled(context, userId)) }
    var allowed by remember(userId) { mutableStateOf(PlanningReminders.canNotify(context)) }
    var message by remember(userId) { mutableStateOf<String?>(null) }
    fun change(value: Boolean) {
        if (repository.session.value?.user?.id != userId) return
        message = null
        runCatching { PlanningReminders.setEnabled(context, userId, value) }
            .onFailure { message = it.message ?: "Не удалось изменить напоминания" }
        enabled = PlanningReminders.isEnabled(context, userId)
        allowed = PlanningReminders.canNotify(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) change(true) else message = "Для напоминаний разрешите уведомления в настройках Android."
    }
    DisposableEffect(lifecycle, userId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = PlanningReminders.isEnabled(context, userId)
                allowed = PlanningReminders.canNotify(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Напоминания о плане", style = MaterialTheme.typography.titleMedium)
                Text("Раз в день, если есть доходы или платежи для проверки.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(enabled, onCheckedChange = { value ->
                if (!value) change(false)
                else if (Build.VERSION.SDK_INT >= 33 && !allowed) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else change(true)
            }, modifier = Modifier.testTag("planning_reminders_enabled"))
        }
        Text("Время доставки зависит от Android и подключения к сети. Суммы и названия счетов в уведомлениях не показываются.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!allowed && (enabled || message != null)) {
            Text("Android ограничивает уведомления MoneyFlow.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }, modifier = Modifier.testTag("planning_notification_settings")) { Text("Настройки уведомлений") }
        }
        if (enabled && allowed) TextButton(onClick = {
            if (repository.session.value?.user?.id == userId) {
                runCatching { PlanningReminders.checkSoon(context, userId) }
                    .onSuccess { message = "Проверка запланирована. Напоминание появится, если есть неподтверждённые планы; не чаще одного раза в день." }
                    .onFailure { message = it.message ?: "Не удалось запустить проверку" }
            }
        }, modifier = Modifier.testTag("planning_reminders_check")) { Text("Проверить сейчас") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
