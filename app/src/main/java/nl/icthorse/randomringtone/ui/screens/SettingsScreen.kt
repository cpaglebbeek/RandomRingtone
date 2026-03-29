package nl.icthorse.randomringtone.ui.screens

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import nl.icthorse.randomringtone.data.AppRingtoneManager

@Composable
fun SettingsScreen(ringtoneManager: AppRingtoneManager) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canWriteSettings by remember { mutableStateOf(ringtoneManager.canWriteSettings()) }
    var notificationAccessEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }

    // Hercheck permissies wanneer gebruiker terugkeert van Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canWriteSettings = ringtoneManager.canWriteSettings()
                notificationAccessEnabled = isNotificationListenerEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Instellingen",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Permissies sectie
        Text(
            text = "Permissies",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        // WRITE_SETTINGS permissie
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (canWriteSettings) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (canWriteSettings)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Systeeminstellingen wijzigen",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (canWriteSettings)
                            "Toegestaan — ringtone kan worden ingesteld"
                        else
                            "Vereist om ringtone te kunnen wijzigen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!canWriteSettings) {
                    FilledTonalButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        }
                    ) {
                        Text("Toestaan")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // NotificationListener permissie (voor SMS/WhatsApp custom ringtones)
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (notificationAccessEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (notificationAccessEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Notificatie-toegang",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (notificationAccessEnabled)
                            "Toegestaan — SMS en WhatsApp ringtones actief"
                        else
                            "Vereist voor custom SMS en WhatsApp ringtones",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!notificationAccessEnabled) {
                    FilledTonalButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
                        }
                    ) {
                        Text("Toestaan")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // App info
        Text(
            text = "Over",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                InfoRow("App", "RandomRingtone")
                InfoRow("Versie", "${nl.icthorse.randomringtone.BuildConfig.VERSION_NAME} \"David Bowie\"")
                InfoRow("Muziekbron", "Deezer (30 sec previews)")
                InfoRow("Ringtone duur", "~20 sec (voicemail timeout)")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Cache wissen
        OutlinedButton(
            onClick = { ringtoneManager.clearCache() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Gedownloade ringtones wissen")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun isNotificationListenerEnabled(context: android.content.Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    if (!TextUtils.isEmpty(flat)) {
        val names = flat.split(":")
        val cn = ComponentName(context, nl.icthorse.randomringtone.service.NotificationService::class.java)
        return names.any { ComponentName.unflattenFromString(it) == cn }
    }
    return false
}
