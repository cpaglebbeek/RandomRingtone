package nl.icthorse.randomringtone.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.*
import java.io.File

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a")

/**
 * Bibliotheek item — single point of truth: altijd uit saved_tracks DB.
 */
data class LibraryItem(
    val file: File,
    val trackId: Long,
    val title: String,
    val artist: String,
    val sizeFormatted: String,
    val isScanned: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    db: RingtoneDatabase,
    ringtoneManager: AppRingtoneManager,
    snackbarHostState: SnackbarHostState,
    onOpenEditor: ((String, String, File, Long, String) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Single point of truth: één lijst uit DB
    var libraryItems by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }

    // Scan state
    var isScanning by remember { mutableStateOf(false) }
    var scanDiagnostic by remember { mutableStateOf<String?>(null) }
    var pendingScan by remember { mutableStateOf(false) }

    // Permissie check voor MediaStore scan
    val hasAudioPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else true
    }
    var audioPermissionGranted by remember { mutableStateOf(hasAudioPermission) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioPermissionGranted = granted
        if (granted) pendingScan = true
    }

    // Delete dialoog state
    var showDeleteDialog by remember { mutableStateOf<LibraryItem?>(null) }

    // === SINGLE POINT OF TRUTH: refresh uit DB ===
    fun refresh() {
        scope.launch {
            libraryItems = db.savedTrackDao().getAll()
                .filter { track ->
                    val path = track.localPath
                    if (path == null || path.isBlank()) return@filter false
                    // Single Point of Truth: alleen app-eigen bestanden
                    val name = File(path).name.lowercase()
                    path.contains("RandomRingtone", ignoreCase = true) ||
                        name.startsWith("spotify_mp3_") ||
                        name.startsWith("youtube_mp3_") ||
                        name.startsWith("ringtone_") ||
                        name.startsWith("download_")
                }
                .map { track ->
                    val file = File(track.localPath!!)
                    LibraryItem(
                        file = file,
                        trackId = track.deezerTrackId,
                        title = track.title.ifBlank { file.nameWithoutExtension },
                        artist = track.artist,
                        sizeFormatted = if (file.exists()) formatFileSize(file.length()) else "niet op schijf",
                        isScanned = true
                    )
                }
        }
    }

    // === SCAN ===
    fun doScan() {
        isScanning = true
        scope.launch {
            try {
                val result = ringtoneManager.storage.scanExistingFiles()
                val scanned = result.files

                if (scanned.isEmpty()) {
                    fun fmtDir(label: String, info: StorageManager.DirInfo) = buildString {
                        appendLine("$label:")
                        appendLine(info.path)
                        if (!info.exists) appendLine("  → MAP BESTAAT NIET!")
                        else if (info.totalCount == -1) appendLine("  → GEEN LEESTOEGANG")
                        else {
                            appendLine("  → ${info.totalCount} totaal, ${info.audioCount} audio")
                            if (info.fileNames.isNotEmpty()) {
                                appendLine("  Bestanden:")
                                info.fileNames.forEach { appendLine("    · $it") }
                            }
                        }
                    }
                    scanDiagnostic = buildString {
                        appendLine("Geen audiobestanden gevonden.\n")
                        appendLine(fmtDir("APP DOWNLOADS", result.downloadInfo))
                        appendLine(fmtDir("APP RINGTONES", result.ringtoneInfo))
                        appendLine(fmtDir("SYSTEEM DOWNLOADS", result.systemDownloadInfo))
                        if (result.usedMediaStore) {
                            appendLine("\nMEDIASTORE FALLBACK:")
                            if (result.mediaStoreCount > 0)
                                appendLine("  → ${result.mediaStoreCount} gevonden via MediaStore!")
                            else {
                                val perm = if (audioPermissionGranted) "TOEGEKEND" else "NIET TOEGEKEND"
                                appendLine("  → Niets gevonden. READ_MEDIA_AUDIO: $perm")
                            }
                        }
                    }
                } else {
                    var added = 0
                    for (sf in scanned) {
                        val byId = db.savedTrackDao().getById(sf.trackId)
                        if (byId == null) {
                            db.savedTrackDao().insert(
                                SavedTrack(
                                    deezerTrackId = sf.trackId,
                                    title = sf.title,
                                    artist = sf.artist,
                                    previewUrl = "",
                                    localPath = sf.localPath,
                                    playlistName = sf.playlistName ?: "Gescand"
                                )
                            )
                            added++
                        } else if (byId.localPath != null && !File(byId.localPath).exists()) {
                            db.savedTrackDao().insert(byId.copy(localPath = sf.localPath))
                            added++
                        }
                    }
                    val msCount = scanned.count { it.source == "mediastore" }
                    val msg = buildString {
                        append("$added nieuw van ${scanned.size} bestanden")
                        if (msCount > 0) append(" ($msCount via MediaStore)")
                    }
                    snackbarHostState.showSnackbar(msg)
                }
                refresh()
            } catch (e: Exception) {
                scanDiagnostic = "Scan mislukt:\n${e.message}\n\n${e.stackTraceToString().take(500)}"
            } finally {
                isScanning = false
            }
        }
    }

    LaunchedEffect(pendingScan) {
        if (pendingScan) { pendingScan = false; doScan() }
    }
    LaunchedEffect(Unit) { refresh() }

    // === DELETE DIALOOG ===
    showDeleteDialog?.let { item ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Verwijderen: ${item.title}") },
            text = { Text("Wat wil je verwijderen?") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val existing = db.savedTrackDao().getById(item.trackId)
                                if (existing != null) db.savedTrackDao().delete(existing)
                                refresh()
                                snackbarHostState.showSnackbar("Uit bibliotheek verwijderd: ${item.title}")
                            }
                            showDeleteDialog = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.RemoveCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Uit bibliotheek verwijderen")
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val existing = db.savedTrackDao().getById(item.trackId)
                                if (existing != null) db.savedTrackDao().delete(existing)
                                val deleted = item.file.delete()
                                refresh()
                                if (deleted) snackbarHostState.showSnackbar("Permanent verwijderd: ${item.title}")
                                else snackbarHostState.showSnackbar("Uit bibliotheek verwijderd, bestand niet van schijf gewist", duration = SnackbarDuration.Long)
                            }
                            showDeleteDialog = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verwijder van schijf (permanent)")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = null }) { Text("Annuleren") } }
        )
    }

    // === SCAN DIAGNOSTIEK DIALOOG ===
    scanDiagnostic?.let { diag ->
        AlertDialog(
            onDismissRequest = { scanDiagnostic = null },
            title = { Text("Scan resultaat") },
            text = {
                Text(diag, style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            },
            confirmButton = { TextButton(onClick = { scanDiagnostic = null }) { Text("OK") } }
        )
    }

    // === UI ===
    Column(modifier = Modifier.fillMaxSize()) {
        // Header + Scan knop
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Bibliotheek (${libraryItems.size})",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f))
            FilledTonalButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !audioPermissionGranted)
                        audioPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                    else doScan()
                },
                enabled = !isScanning
            ) {
                if (isScanning) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan")
            }
        }

        // Unified track list — single point of truth
        if (libraryItems.isEmpty()) {
            EmptyState(
                icon = Icons.Default.LibraryMusic,
                title = "Bibliotheek is leeg",
                subtitle = "Download nummers via Spotify en druk op Scan"
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(libraryItems, key = { it.trackId }) { item ->
                    LibraryCard(
                        item = item,
                        actions = {
                            if (onOpenEditor != null) {
                                IconButton(onClick = {
                                    onOpenEditor.invoke(item.title, item.artist, item.file, item.trackId, "")
                                }) {
                                    Icon(Icons.Default.ContentCut, contentDescription = "Trim")
                                }
                            }
                            IconButton(onClick = { showDeleteDialog = item }) {
                                Icon(Icons.Default.Delete, contentDescription = "Verwijderen",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                }
            }
        }
    }
}

// === Gedeelde Card ===

@Composable
private fun LibraryCard(
    item: LibraryItem,
    actions: @Composable RowScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (item.artist.isNotBlank()) {
                        Text(item.artist, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("·", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(item.sizeFormatted, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            actions()
        }
    }
}

// === Helpers ===

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private suspend fun setAsChannel(
    context: android.content.Context,
    ringtoneManager: AppRingtoneManager,
    file: File,
    channel: Channel,
    snackbarHostState: SnackbarHostState
) {
    if (!ringtoneManager.canWriteSettings()) {
        snackbarHostState.showSnackbar(
            message = "Permissie nodig: sta 'Systeeminstellingen wijzigen' toe",
            actionLabel = "Openen", duration = SnackbarDuration.Long
        ).let { result ->
            if (result == SnackbarResult.ActionPerformed) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                })
            }
        }
        return
    }
    val dummyTrack = DeezerTrack(id = extractTrackId(file.name),
        title = file.nameWithoutExtension, artist = DeezerArtist(name = ""), preview = "")
    val success = when (channel) {
        Channel.CALL -> ringtoneManager.setAsRingtone(dummyTrack, file)
        Channel.NOTIFICATION -> ringtoneManager.setAsNotification(dummyTrack, file)
        else -> false
    }
    snackbarHostState.showSnackbar(if (success) "Ingesteld als ${channel.name.lowercase()}" else "Instellen mislukt")
}

private fun extractTrackId(fileName: String): Long {
    val match = Regex("(?:download|ringtone|lib)_(\\d+)").find(fileName)
    if (match != null) return match.groupValues[1].toLongOrNull() ?: fileName.hashCode().toLong().let { if (it < 0) -it else it }
    return fileName.hashCode().toLong().let { if (it < 0) -it else it }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
