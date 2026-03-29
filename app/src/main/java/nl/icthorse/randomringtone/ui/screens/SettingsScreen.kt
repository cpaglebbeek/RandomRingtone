package nl.icthorse.randomringtone.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import nl.icthorse.randomringtone.data.AppRingtoneManager

@Composable
fun SettingsScreen(ringtoneManager: AppRingtoneManager) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canWriteSettings by remember { mutableStateOf(ringtoneManager.canWriteSettings()) }

    // Hercheck permissie wanneer gebruiker terugkeert van Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canWriteSettings = ringtoneManager.canWriteSettings()
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
                InfoRow("Versie", "0.1.0 \"Jimi Hendrix\"")
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
