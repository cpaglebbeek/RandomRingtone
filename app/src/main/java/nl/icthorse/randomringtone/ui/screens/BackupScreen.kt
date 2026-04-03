package nl.icthorse.randomringtone.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.*

private enum class BackupProvider(val label: String) {
    GOOGLE_DRIVE("Google Drive"),
    DROPBOX("Dropbox"),
    ONEDRIVE("OneDrive"),
    ICT_HORSE("iCt Horse")
}

@Composable
fun BackupScreen(
    ringtoneManager: AppRingtoneManager,
    db: RingtoneDatabase,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = ringtoneManager.storage
    val backupManager = remember { BackupManager(context) }
    val ictHorseClient = remember { IctHorseBackupClient(context) }

    var selectedProvider by remember { mutableStateOf(BackupProvider.ICT_HORSE) }
    var backupUri by remember { mutableStateOf("") }
    var backupMeta by remember { mutableStateOf<BackupMeta?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressPhase by remember { mutableStateOf("") }
    var progressCurrent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var progressPct by remember { mutableStateOf(0f) }
    var progressBps by remember { mutableStateOf(0L) }
    var progressEta by remember { mutableIntStateOf(-1) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    // Load saved state
    LaunchedEffect(Unit) {
        backupUri = storage.getBackupUri() ?: ""
        if (backupUri.isNotBlank()) {
            backupMeta = backupManager.readBackupInfo(Uri.parse(backupUri))
        }
    }

    // Load iCt Horse status wanneer provider geselecteerd
    LaunchedEffect(selectedProvider) {
        if (selectedProvider == BackupProvider.ICT_HORSE) {
            try {
                val status = ictHorseClient.getStatus()
                backupMeta = status.meta
            } catch (_: Exception) {
                backupMeta = null
            }
        } else if (backupUri.isNotBlank()) {
            backupMeta = backupManager.readBackupInfo(Uri.parse(backupUri))
        } else {
            backupMeta = null
        }
    }

    // SAF directory picker (voor GDrive/Dropbox/OneDrive)
    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            scope.launch {
                storage.setBackupUri(uri.toString())
                backupUri = uri.toString()
                backupMeta = backupManager.readBackupInfo(uri)
            }
        }
    }

    // Progress handler
    val onProgress: (BackupProgress) -> Unit = { p ->
        progressPhase = p.phase
        progressCurrent = p.current
        progressTotal = p.total
        progressPct = p.percentage
        progressBps = p.bytesPerSecond
        progressEta = p.etaSeconds
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Backup & Restore", style = MaterialTheme.typography.headlineMedium)

        // === Provider keuze ===
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup provider", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BackupProvider.entries.forEach { provider ->
                        FilterChip(
                            selected = selectedProvider == provider,
                            onClick = { selectedProvider = provider },
                            label = { Text(provider.label, style = MaterialTheme.typography.labelSmall) },
                            enabled = !isProcessing
                        )
                    }
                }
            }
        }

        // === Provider-specifieke config ===
        if (selectedProvider == BackupProvider.ICT_HORSE) {
            // iCt Horse: auto-geconfigureerd
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("iCt Horse Cloud", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "icthorse.nl/randomringtone/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Automatisch gekoppeld aan dit apparaat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            // SAF providers: map kiezen
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${selectedProvider.label} map", style = MaterialTheme.typography.titleMedium)
                    }

                    if (backupUri.isNotBlank()) {
                        Text(
                            text = Uri.parse(backupUri).lastPathSegment ?: backupUri,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Niet ingesteld — kies een map in ${selectedProvider.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = { directoryPicker.launch(null) },
                        enabled = !isProcessing
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (backupUri.isBlank()) "Kies map" else "Wijzig map")
                    }
                }
            }
        }

        // === Laatste Backup Info ===
        if (backupMeta != null) {
            val meta = backupMeta!!
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Laatste backup", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Datum: ${meta.backupDate}")
                    Text("App versie: ${meta.appVersion}")
                    Text("Tracks: ${meta.trackCount} | Playlists: ${meta.playlistCount}")
                    Text("Bestanden: ${meta.downloadFileCount} downloads + ${meta.ringtoneFileCount} ringtones")
                }
            }
        }

        // === Voortgang ===
        AnimatedVisibility(visible = isProcessing) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(progressPhase, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    LinearProgressIndicator(
                        progress = { if (progressPct > 0f) progressPct else if (progressTotal > 0) progressCurrent.toFloat() / progressTotal else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val pctText = "${(progressPct * 100).toInt()}%"
                        val fileText = "$progressCurrent / $progressTotal"
                        Text("$pctText  ($fileText)", style = MaterialTheme.typography.labelSmall)

                        val speedText = if (progressBps > 0) {
                            val mbps = progressBps / (1024.0 * 1024.0)
                            if (mbps >= 1.0) "%.1f MB/s".format(mbps) else "%.0f KB/s".format(progressBps / 1024.0)
                        } else ""
                        val etaText = when {
                            progressEta > 60 -> "${progressEta / 60}m ${progressEta % 60}s"
                            progressEta > 0 -> "${progressEta}s"
                            progressEta == 0 -> "<1s"
                            else -> ""
                        }
                        Text(
                            listOf(speedText, if (etaText.isNotEmpty()) "ETA $etaText" else "").filter { it.isNotEmpty() }.joinToString(" — "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // === Actieknoppen ===
        val canBackup = if (selectedProvider == BackupProvider.ICT_HORSE) true else backupUri.isNotBlank()
        val canRestore = canBackup && backupMeta != null

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isProcessing = true
                        val result = if (selectedProvider == BackupProvider.ICT_HORSE) {
                            ictHorseClient.backup(db, storage, backupManager, onProgress)
                        } else {
                            backupManager.backup(Uri.parse(backupUri), db, storage, onProgress)
                        }
                        isProcessing = false
                        if (result.success) {
                            // Refresh meta
                            backupMeta = if (selectedProvider == BackupProvider.ICT_HORSE) {
                                try { ictHorseClient.getStatus().meta } catch (_: Exception) { null }
                            } else {
                                backupManager.readBackupInfo(Uri.parse(backupUri))
                            }
                        }
                        snackbarHostState.showSnackbar(result.message)
                    }
                },
                enabled = canBackup && !isProcessing,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Backup")
            }

            OutlinedButton(
                onClick = { showRestoreConfirm = true },
                enabled = canRestore && !isProcessing,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Herstellen")
            }
        }
    }

    // === Herstel bevestigingsdialoog ===
    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Backup herstellen?") },
            text = {
                Text(
                    "Dit vervangt ALLE huidige data met de backup van ${selectedProvider.label}. " +
                        "Dit kan niet ongedaan worden gemaakt.\n\n" +
                        "Backup van: ${backupMeta?.backupDate ?: "?"}\n" +
                        "Tracks: ${backupMeta?.trackCount ?: 0}\n" +
                        "Playlists: ${backupMeta?.playlistCount ?: 0}"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreConfirm = false
                        scope.launch {
                            isProcessing = true
                            val result = if (selectedProvider == BackupProvider.ICT_HORSE) {
                                ictHorseClient.restore(db, storage, onProgress)
                            } else {
                                backupManager.restore(Uri.parse(backupUri), db, storage, onProgress)
                            }
                            isProcessing = false
                            if (result.success) {
                                backupMeta = if (selectedProvider == BackupProvider.ICT_HORSE) {
                                    try { ictHorseClient.getStatus().meta } catch (_: Exception) { null }
                                } else {
                                    backupManager.readBackupInfo(Uri.parse(backupUri))
                                }
                            }
                            snackbarHostState.showSnackbar(result.message)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Herstellen") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Annuleren") }
            }
        )
    }
}
