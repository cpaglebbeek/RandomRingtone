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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import nl.icthorse.randomringtone.data.AppRingtoneManager

@Composable
fun SettingsScreen(
    ringtoneManager: AppRingtoneManager,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
            .verticalScroll(rememberScrollState())
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

        // Opslag sectie
        Text(
            text = "Opslag",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        var downloadPath by remember { mutableStateOf("") }
        var ringtonePath by remember { mutableStateOf("") }
        var diskUsage by remember { mutableStateOf<nl.icthorse.randomringtone.data.DiskUsage?>(null) }
        var editDownloadPath by remember { mutableStateOf(false) }
        var editRingtonePath by remember { mutableStateOf(false) }
        var tempPath by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            downloadPath = ringtoneManager.storage.getDownloadDir().absolutePath
            ringtonePath = ringtoneManager.storage.getRingtoneDir().absolutePath
            diskUsage = ringtoneManager.storage.getDiskUsage()
        }

        // Downloads locatie
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Downloads (tijdelijk)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = downloadPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                diskUsage?.let {
                    Text(
                        text = "${it.downloadCount} bestanden, ${it.downloadFormatted()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        tempPath = downloadPath
                        editDownloadPath = true
                    }) { Text("Wijzig") }
                    TextButton(onClick = {
                        scope.launch {
                            ringtoneManager.clearDownloads()
                            diskUsage = ringtoneManager.storage.getDiskUsage()
                            snackbarHostState.showSnackbar("Downloads gewist")
                        }
                    }) { Text("Wissen", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Ringtones locatie
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Ringtones (permanent)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = ringtonePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                diskUsage?.let {
                    Text(
                        text = "${it.ringtoneCount} bestanden, ${it.ringtoneFormatted()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        tempPath = ringtonePath
                        editRingtonePath = true
                    }) { Text("Wijzig") }
                    TextButton(onClick = {
                        scope.launch {
                            ringtoneManager.clearRingtones()
                            diskUsage = ringtoneManager.storage.getDiskUsage()
                            snackbarHostState.showSnackbar("Ringtones gewist")
                        }
                    }) { Text("Wissen", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        diskUsage?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Totaal schijfgebruik: ${it.totalFormatted()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                scope.launch {
                    ringtoneManager.storage.resetToDefaults()
                    downloadPath = ringtoneManager.storage.getDownloadDir().absolutePath
                    ringtonePath = ringtoneManager.storage.getRingtoneDir().absolutePath
                    snackbarHostState.showSnackbar("Paden gereset naar standaard")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reset naar standaard locaties") }

        Spacer(modifier = Modifier.height(24.dp))

        // App info
        Text(text = "Over", style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                InfoRow("App", "RandomRingtone")
                InfoRow("Versie", "${nl.icthorse.randomringtone.BuildConfig.VERSION_NAME} \"Amy Winehouse\"")
                InfoRow("Muziekbron", "Deezer (30 sec previews)")
                InfoRow("Ringtone duur", "Instelbaar via editor")
            }
        }

        // Pad-bewerk dialogen
        if (editDownloadPath) {
            PathEditDialog(
                title = "Download locatie",
                currentPath = tempPath,
                onDismiss = { editDownloadPath = false },
                onSave = { newPath ->
                    scope.launch {
                        ringtoneManager.storage.setDownloadDir(newPath)
                        downloadPath = newPath
                        editDownloadPath = false
                        snackbarHostState.showSnackbar("Download locatie bijgewerkt")
                    }
                }
            )
        }

        if (editRingtonePath) {
            PathEditDialog(
                title = "Ringtone locatie",
                currentPath = tempPath,
                onDismiss = { editRingtonePath = false },
                onSave = { newPath ->
                    scope.launch {
                        ringtoneManager.storage.setRingtoneDir(newPath)
                        ringtonePath = newPath
                        editRingtonePath = false
                        snackbarHostState.showSnackbar("Ringtone locatie bijgewerkt")
                    }
                }
            )
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

@Composable
private fun PathEditDialog(
    title: String,
    currentPath: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var path by remember { mutableStateOf(currentPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("Pad") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(path.trim()) },
                enabled = path.isNotBlank()
            ) { Text("Opslaan") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuleren") }
        }
    )
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
