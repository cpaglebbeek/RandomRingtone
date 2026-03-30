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

    var backupUri by remember { mutableStateOf("") }
    var backupMeta by remember { mutableStateOf<BackupMeta?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressPhase by remember { mutableStateOf("") }
    var progressCurrent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    // Load saved backup URI + bestaande backup info
    LaunchedEffect(Unit) {
        backupUri = storage.getBackupUri() ?: ""
        if (backupUri.isNotBlank()) {
            backupMeta = backupManager.readBackupInfo(Uri.parse(backupUri))
        }
    }

    // SAF directory picker
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Backup & Restore", style = MaterialTheme.typography.headlineMedium)

        // === Backup Locatie ===
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Backup locatie", style = MaterialTheme.typography.titleMedium)
                }

                if (backupUri.isNotBlank()) {
                    val displayPath = Uri.parse(backupUri).lastPathSegment ?: backupUri
                    Text(
                        text = displayPath,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Niet ingesteld",
                        style = MaterialTheme.typography.bodyMedium,
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
                    Text("Tracks: ${meta.trackCount} | Playlists: ${meta.playlistCount} | Koppelingen: ${meta.playlistTrackCount}")
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
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(progressPhase, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { if (progressTotal > 0) progressCurrent.toFloat() / progressTotal else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "$progressCurrent / $progressTotal",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        // === Actieknoppen ===
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isProcessing = true
                        val result = backupManager.backup(
                            backupUri = Uri.parse(backupUri),
                            db = db,
                            storage = storage,
                            onProgress = { p ->
                                progressPhase = p.phase
                                progressCurrent = p.current
                                progressTotal = p.total
                            }
                        )
                        isProcessing = false
                        if (result.success) {
                            backupMeta = backupManager.readBackupInfo(Uri.parse(backupUri))
                        }
                        snackbarHostState.showSnackbar(result.message)
                    }
                },
                enabled = backupUri.isNotBlank() && !isProcessing,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Backup maken")
            }

            OutlinedButton(
                onClick = { showRestoreConfirm = true },
                enabled = backupUri.isNotBlank() && backupMeta != null && !isProcessing,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Herstellen")
            }
        }

        // === Uitleg ===
        if (backupUri.isBlank()) {
            Text(
                "Kies een map op je telefoon of in een cloud-app (Google Drive, Dropbox, OneDrive) " +
                    "om backups op te slaan. De cloud-app moet geinstalleerd zijn op je telefoon.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                    "Dit vervangt ALLE huidige data (tracks, playlists, bestanden) met de backup. " +
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
                            val result = backupManager.restore(
                                backupUri = Uri.parse(backupUri),
                                db = db,
                                storage = storage,
                                onProgress = { p ->
                                    progressPhase = p.phase
                                    progressCurrent = p.current
                                    progressTotal = p.total
                                }
                            )
                            isProcessing = false
                            if (result.success) {
                                backupMeta = backupManager.readBackupInfo(Uri.parse(backupUri))
                            }
                            snackbarHostState.showSnackbar(result.message)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Herstellen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text("Annuleren")
                }
            }
        )
    }
}
