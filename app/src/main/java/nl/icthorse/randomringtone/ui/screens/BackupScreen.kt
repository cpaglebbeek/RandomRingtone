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
import nl.icthorse.randomringtone.AppBusyState
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
    var isProcessing by remember { mutableStateOf(false) }
    var progressPhase by remember { mutableStateOf("") }
    var progressCurrent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var progressPct by remember { mutableStateOf(0f) }
    var progressBps by remember { mutableStateOf(0L) }
    var progressEta by remember { mutableIntStateOf(-1) }

    // Slot state (iCt Horse)
    var slots by remember { mutableStateOf<List<SlotInfo>>(emptyList()) }
    var showSlotBackupDialog by remember { mutableStateOf(false) }
    var showSlotRestoreDialog by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var selectedSlot by remember { mutableIntStateOf(0) }

    // SAF backup meta (voor non-iCt Horse providers)
    var safBackupMeta by remember { mutableStateOf<BackupMeta?>(null) }

    // Load saved state
    LaunchedEffect(Unit) {
        backupUri = storage.getBackupUri() ?: ""
        if (backupUri.isNotBlank()) {
            safBackupMeta = backupManager.readBackupInfo(Uri.parse(backupUri))
        }
    }

    // Load iCt Horse slot status wanneer provider geselecteerd
    LaunchedEffect(selectedProvider) {
        if (selectedProvider == BackupProvider.ICT_HORSE) {
            slots = try { ictHorseClient.getSlotStatus() } catch (_: Exception) { emptyList() }
        } else if (backupUri.isNotBlank()) {
            safBackupMeta = backupManager.readBackupInfo(Uri.parse(backupUri))
        } else {
            safBackupMeta = null
        }
    }

    // Refresh slots na backup/restore
    suspend fun refreshSlots() {
        slots = try { ictHorseClient.getSlotStatus() } catch (_: Exception) { emptyList() }
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
                safBackupMeta = backupManager.readBackupInfo(uri)
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

    // Backup uitvoeren voor gekozen slot
    fun doBackup(slot: Int) {
        scope.launch {
            isProcessing = true
            AppBusyState.isBusy = true
            val result = ictHorseClient.backup(slot, db, storage, backupManager, onProgress)
            isProcessing = false
            AppBusyState.isBusy = false
            if (result.success) refreshSlots()
            snackbarHostState.showSnackbar(result.message)
        }
    }

    // Restore uitvoeren voor gekozen slot
    fun doRestore(slot: Int) {
        scope.launch {
            isProcessing = true
            AppBusyState.isBusy = true
            val result = ictHorseClient.restore(slot, db, storage, onProgress)
            isProcessing = false
            AppBusyState.isBusy = false
            if (result.success) refreshSlots()
            snackbarHostState.showSnackbar(result.message)
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
                        "Maximaal 2 backups per apparaat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // === Slot overzicht ===
            slots.forEach { slotInfo ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (slotInfo.exists)
                            MaterialTheme.colorScheme.secondaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (slotInfo.exists) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Slot ${slotInfo.slot}",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        if (slotInfo.exists && slotInfo.meta != null) {
                            val meta = slotInfo.meta
                            Text("Datum: ${meta.backupDate}", style = MaterialTheme.typography.bodySmall)
                            Text("v${meta.appVersion} — ${meta.trackCount} tracks, ${meta.playlistCount} playlists",
                                style = MaterialTheme.typography.bodySmall)
                            Text("${meta.downloadFileCount} downloads + ${meta.ringtoneFileCount} ringtones",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Leeg", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
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

            // SAF backup info
            if (safBackupMeta != null) {
                val meta = safBackupMeta!!
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Laatste backup", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Datum: ${meta.backupDate}")
                        Text("App versie: ${meta.appVersion}")
                        Text("Tracks: ${meta.trackCount} | Playlists: ${meta.playlistCount}")
                        Text("Bestanden: ${meta.downloadFileCount} downloads + ${meta.ringtoneFileCount} ringtones")
                    }
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
        val canRestore = if (selectedProvider == BackupProvider.ICT_HORSE) {
            slots.any { it.exists }
        } else {
            backupUri.isNotBlank() && safBackupMeta != null
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (selectedProvider == BackupProvider.ICT_HORSE) {
                        val freeSlot = slots.firstOrNull { !it.exists }
                        if (freeSlot != null) {
                            // Vrije slot beschikbaar → direct gebruiken
                            doBackup(freeSlot.slot)
                        } else {
                            // Beide vol → gebruiker laten kiezen
                            showSlotBackupDialog = true
                        }
                    } else {
                        scope.launch {
                            isProcessing = true
                            AppBusyState.isBusy = true
                            val result = backupManager.backup(Uri.parse(backupUri), db, storage, onProgress)
                            isProcessing = false
                            AppBusyState.isBusy = false
                            if (result.success) {
                                safBackupMeta = backupManager.readBackupInfo(Uri.parse(backupUri))
                            }
                            snackbarHostState.showSnackbar(result.message)
                        }
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
                onClick = {
                    if (selectedProvider == BackupProvider.ICT_HORSE) {
                        val filledSlots = slots.filter { it.exists }
                        if (filledSlots.size == 1) {
                            // Maar 1 backup → direct bevestigingsdialoog
                            selectedSlot = filledSlots[0].slot
                            showRestoreConfirm = true
                        } else {
                            // Meerdere → gebruiker laten kiezen
                            showSlotRestoreDialog = true
                        }
                    } else {
                        selectedSlot = 0
                        showRestoreConfirm = true
                    }
                },
                enabled = canRestore && !isProcessing,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Herstellen")
            }
        }
    }

    // === Dialoog: welke slot overschrijven? (backup) ===
    if (showSlotBackupDialog) {
        AlertDialog(
            onDismissRequest = { showSlotBackupDialog = false },
            icon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
            title = { Text("Welke backup overschrijven?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Beide slots zijn bezet. Kies welke je wilt overschrijven:")
                    slots.filter { it.exists }.forEach { slotInfo ->
                        Card(
                            onClick = {
                                showSlotBackupDialog = false
                                doBackup(slotInfo.slot)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Slot ${slotInfo.slot}", style = MaterialTheme.typography.titleSmall)
                                    if (slotInfo.meta != null) {
                                        Text(slotInfo.meta.backupDate, style = MaterialTheme.typography.bodySmall)
                                        Text("v${slotInfo.meta.appVersion} — ${slotInfo.meta.trackCount} tracks",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSlotBackupDialog = false }) { Text("Annuleren") }
            }
        )
    }

    // === Dialoog: welke slot herstellen? (restore) ===
    if (showSlotRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showSlotRestoreDialog = false },
            icon = { Icon(Icons.Default.Restore, contentDescription = null) },
            title = { Text("Welke backup herstellen?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Kies de backup die je wilt herstellen:")
                    slots.filter { it.exists }.forEach { slotInfo ->
                        Card(
                            onClick = {
                                showSlotRestoreDialog = false
                                selectedSlot = slotInfo.slot
                                showRestoreConfirm = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Slot ${slotInfo.slot}", style = MaterialTheme.typography.titleSmall)
                                    if (slotInfo.meta != null) {
                                        Text(slotInfo.meta.backupDate, style = MaterialTheme.typography.bodySmall)
                                        Text("v${slotInfo.meta.appVersion} — ${slotInfo.meta.trackCount} tracks, ${slotInfo.meta.playlistCount} playlists",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSlotRestoreDialog = false }) { Text("Annuleren") }
            }
        )
    }

    // === Herstel bevestigingsdialoog ===
    if (showRestoreConfirm) {
        val restoreMeta = if (selectedProvider == BackupProvider.ICT_HORSE) {
            slots.firstOrNull { it.slot == selectedSlot }?.meta
        } else safBackupMeta

        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Backup herstellen?") },
            text = {
                Text(
                    "Dit vervangt ALLE huidige data" +
                        (if (selectedProvider == BackupProvider.ICT_HORSE) " met slot $selectedSlot" else "") +
                        ". Dit kan niet ongedaan worden gemaakt.\n\n" +
                        "Backup van: ${restoreMeta?.backupDate ?: "?"}\n" +
                        "Tracks: ${restoreMeta?.trackCount ?: 0}\n" +
                        "Playlists: ${restoreMeta?.playlistCount ?: 0}"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreConfirm = false
                        if (selectedProvider == BackupProvider.ICT_HORSE) {
                            doRestore(selectedSlot)
                        } else {
                            scope.launch {
                                isProcessing = true
                                AppBusyState.isBusy = true
                                val result = backupManager.restore(Uri.parse(backupUri), db, storage, onProgress)
                                isProcessing = false
                                AppBusyState.isBusy = false
                                if (result.success) {
                                    safBackupMeta = backupManager.readBackupInfo(Uri.parse(backupUri))
                                }
                                snackbarHostState.showSnackbar(result.message)
                            }
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
