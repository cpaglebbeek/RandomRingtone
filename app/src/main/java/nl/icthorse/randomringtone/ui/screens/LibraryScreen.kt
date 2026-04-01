package nl.icthorse.randomringtone.ui.screens

import android.content.Intent
import android.provider.Settings
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
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.*
import java.io.File

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a")

/**
 * Bibliotheek item: combineert schijfbestand met optionele DB metadata.
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

    var downloads by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var ringtones by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // Scan state
    var isScanning by remember { mutableStateOf(false) }
    var scanDiagnostic by remember { mutableStateOf<String?>(null) }

    // Delete dialoog state
    var showDeleteDialog by remember { mutableStateOf<LibraryItem?>(null) }

    fun refresh() {
        scope.launch {
            // Haal alle DB entries op, geïndexeerd op localPath
            val allTracks = db.savedTrackDao().getAll()
            val tracksByPath = allTracks.filter { it.localPath != null }
                .associateBy { it.localPath!! }

            // Ringtones: fysieke bestanden + DB enrichment
            val rtDir = ringtoneManager.storage.getRingtoneDir()
            val rtFiles = (rtDir.listFiles() ?: emptyArray())
                .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                .sortedByDescending { it.lastModified() }

            ringtones = rtFiles.map { file ->
                val track = tracksByPath[file.absolutePath]
                LibraryItem(
                    file = file,
                    trackId = track?.deezerTrackId ?: extractTrackId(file.name),
                    title = if (track != null && track.title.isNotBlank()) track.title else file.nameWithoutExtension,
                    artist = track?.artist ?: "",
                    sizeFormatted = formatFileSize(file.length()),
                    isScanned = track != null
                )
            }

            // Downloads: fysieke bestanden + DB enrichment
            val dlDir = ringtoneManager.storage.getDownloadDir()
            val dlFiles = (dlDir.listFiles() ?: emptyArray())
                .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                .sortedByDescending { it.lastModified() }

            downloads = dlFiles.map { file ->
                val track = tracksByPath[file.absolutePath]
                LibraryItem(
                    file = file,
                    trackId = track?.deezerTrackId ?: extractTrackId(file.name),
                    title = if (track != null && track.title.isNotBlank()) track.title else file.nameWithoutExtension,
                    artist = track?.artist ?: "",
                    sizeFormatted = formatFileSize(file.length()),
                    isScanned = track != null
                )
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // === DELETE DIALOOG ===
    showDeleteDialog?.let { item ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Verwijderen: ${item.title}") },
            text = {
                Text("Wat wil je verwijderen?", style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Optie 1: alleen uit bibliotheek (DB entry)
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
                    // Optie 2: van schijf (permanent)
                    Button(
                        onClick = {
                            scope.launch {
                                val existing = db.savedTrackDao().getById(item.trackId)
                                if (existing != null) db.savedTrackDao().delete(existing)
                                val deleted = item.file.delete()
                                refresh()
                                if (deleted) {
                                    snackbarHostState.showSnackbar("Permanent verwijderd: ${item.title}")
                                } else {
                                    snackbarHostState.showSnackbar(
                                        "Uit bibliotheek verwijderd, maar bestand kon niet van schijf gewist worden: ${item.file.absolutePath}",
                                        duration = SnackbarDuration.Long
                                    )
                                }
                            }
                            showDeleteDialog = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verwijder van schijf (permanent)")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Annuleren") }
            }
        )
    }

    // === SCAN DIAGNOSTIEK DIALOOG ===
    scanDiagnostic?.let { diag ->
        AlertDialog(
            onDismissRequest = { scanDiagnostic = null },
            title = { Text("Scan resultaat") },
            text = {
                Text(
                    diag,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = { scanDiagnostic = null }) { Text("OK") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header + Scan knop
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 16.dp, 16.dp, 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Bibliotheek",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(
                onClick = {
                    isScanning = true
                    scope.launch {
                        try {
                            val result = ringtoneManager.storage.scanExistingFiles()
                            val scanned = result.files

                            if (scanned.isEmpty()) {
                                // Toon diagnostiek dialoog
                                scanDiagnostic = buildString {
                                    appendLine("Geen audiobestanden (.mp3/.m4a) gevonden.\n")
                                    appendLine("Download map:")
                                    appendLine(result.downloadDir)
                                    if (!result.downloadDirExists) appendLine("  → Map bestaat niet!")
                                    else if (result.downloadDirCount == -1) appendLine("  → Geen leestoegang (listFiles=null)")
                                    else appendLine("  → ${result.downloadDirCount} bestanden in map")
                                    appendLine()
                                    appendLine("Ringtone map:")
                                    appendLine(result.ringtoneDir)
                                    if (!result.ringtoneDirExists) appendLine("  → Map bestaat niet!")
                                    else if (result.ringtoneDirCount == -1) appendLine("  → Geen leestoegang (listFiles=null)")
                                    else appendLine("  → ${result.ringtoneDirCount} bestanden in map")
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
                                    } else if (byId.localPath != null && !java.io.File(byId.localPath).exists()) {
                                        db.savedTrackDao().insert(byId.copy(localPath = sf.localPath))
                                        added++
                                    }
                                }
                                snackbarHostState.showSnackbar(
                                    "$added nieuw van ${scanned.size} bestanden " +
                                    "(${result.downloadDirCount} in downloads, ${result.ringtoneDirCount} in ringtones)"
                                )
                            }
                            refresh()
                        } catch (e: Exception) {
                            scanDiagnostic = "Scan mislukt:\n${e.message}\n\n${e.stackTraceToString().take(500)}"
                        } finally {
                            isScanning = false
                        }
                    }
                },
                enabled = !isScanning
            ) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan")
            }
        }

        // Sub-tabs
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Downloads (${downloads.size})") },
                icon = { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Ringtones (${ringtones.size})") },
                icon = { Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }

        when (selectedTab) {
            0 -> DownloadsTab(
                downloads = downloads,
                onTrim = { item ->
                    onOpenEditor?.invoke(
                        item.title, item.artist, item.file, item.trackId, ""
                    )
                },
                onDelete = { item -> showDeleteDialog = item }
            )
            1 -> RingtonesTab(
                ringtones = ringtones,
                db = db,
                ringtoneManager = ringtoneManager,
                snackbarHostState = snackbarHostState,
                onDelete = { item -> showDeleteDialog = item },
                onRefresh = { refresh() }
            )
        }
    }
}

// --- Downloads Tab ---

@Composable
private fun DownloadsTab(
    downloads: List<LibraryItem>,
    onTrim: (LibraryItem) -> Unit,
    onDelete: (LibraryItem) -> Unit
) {
    if (downloads.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Download,
            title = "Geen downloads",
            subtitle = "Zoek een nummer en download het via Zoeken"
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Text(
                    "Volledige MP3 bestanden in de download-map. Trim ze om als ringtone te gebruiken.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(downloads, key = { it.file.absolutePath }) { item ->
                LibraryCard(
                    item = item,
                    icon = Icons.Default.AudioFile,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    actions = {
                        FilledTonalButton(onClick = { onTrim(item) }) {
                            Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Trim")
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Verwijderen",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            }
        }
    }
}

// --- Ringtones Tab ---

@Composable
private fun RingtonesTab(
    ringtones: List<LibraryItem>,
    db: RingtoneDatabase,
    ringtoneManager: AppRingtoneManager,
    snackbarHostState: SnackbarHostState,
    onDelete: (LibraryItem) -> Unit,
    onRefresh: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showChannelDialog by remember { mutableStateOf<LibraryItem?>(null) }
    var showPlaylistDialog by remember { mutableStateOf<LibraryItem?>(null) }
    var existingPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    LaunchedEffect(Unit) {
        existingPlaylists = db.playlistDao().getAll()
    }

    if (ringtones.isEmpty()) {
        EmptyState(
            icon = Icons.Default.MusicNote,
            title = "Geen ringtones",
            subtitle = "Trim een download of sla een track op via de editor"
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Text(
                    "Getrimde ringtone bestanden. Stel in als ringtone of voeg toe aan een playlist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(ringtones, key = { it.file.absolutePath }) { item ->
                LibraryCard(
                    item = item,
                    icon = Icons.Default.Notifications,
                    iconTint = MaterialTheme.colorScheme.primary,
                    actions = {
                        IconButton(onClick = { showChannelDialog = item }) {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = "Instellen als...")
                        }
                        IconButton(onClick = { showPlaylistDialog = item }) {
                            Icon(Icons.Default.PlaylistAdd, contentDescription = "Aan playlist")
                        }
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Verwijderen",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            }
        }
    }

    // Kanaal-selectie dialoog
    showChannelDialog?.let { item ->
        AlertDialog(
            onDismissRequest = { showChannelDialog = null },
            title = { Text("Instellen als...") },
            text = {
                Column {
                    ChannelOption("Telefoon (globaal)", Icons.Default.Phone) {
                        scope.launch {
                            setAsChannel(context, ringtoneManager, item.file, Channel.CALL, snackbarHostState)
                            showChannelDialog = null
                        }
                    }
                    ChannelOption("Notificatie (globaal)", Icons.Default.Notifications) {
                        scope.launch {
                            setAsChannel(context, ringtoneManager, item.file, Channel.NOTIFICATION, snackbarHostState)
                            showChannelDialog = null
                        }
                    }
                    ChannelOption("SMS (globaal)", Icons.Default.Sms) {
                        scope.launch {
                            saveGlobalAssignment(db, item, Channel.SMS)
                            snackbarHostState.showSnackbar("SMS ringtone ingesteld (via NotificationService)")
                            showChannelDialog = null
                        }
                    }
                    ChannelOption("WhatsApp (globaal)", Icons.Default.Chat) {
                        scope.launch {
                            saveGlobalAssignment(db, item, Channel.WHATSAPP)
                            snackbarHostState.showSnackbar("WhatsApp ringtone ingesteld (via NotificationService)")
                            showChannelDialog = null
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChannelDialog = null }) { Text("Annuleren") }
            }
        )
    }

    // Playlist dialoog (met bestaande playlists)
    showPlaylistDialog?.let { item ->
        var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
        var createNew by remember { mutableStateOf(existingPlaylists.isEmpty()) }
        var newPlaylistName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showPlaylistDialog = null },
            title = { Text("Toevoegen aan playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (existingPlaylists.isNotEmpty()) {
                        Text("Bestaande playlists:", style = MaterialTheme.typography.labelLarge)
                        existingPlaylists.forEach { playlist ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                RadioButton(
                                    selected = selectedPlaylist == playlist && !createNew,
                                    onClick = { selectedPlaylist = playlist; createNew = false }
                                )
                                Text(playlist.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = createNew,
                            onClick = { createNew = true; selectedPlaylist = null }
                        )
                        Text("Nieuwe playlist", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (createNew) {
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            label = { Text("Playlist naam") },
                            placeholder = { Text("bijv. Rock, Chill, Favoriet...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(start = 40.dp)
                        )
                    }
                }
            },
            confirmButton = {
                val canSave = if (createNew) newPlaylistName.isNotBlank() else selectedPlaylist != null
                Button(
                    onClick = {
                        scope.launch {
                            val pName = if (createNew) newPlaylistName.trim() else selectedPlaylist!!.name
                            db.savedTrackDao().insert(
                                SavedTrack(
                                    deezerTrackId = item.trackId,
                                    title = item.title,
                                    artist = item.artist,
                                    previewUrl = "",
                                    localPath = item.file.absolutePath,
                                    playlistName = pName
                                )
                            )
                            val playlistId = if (createNew) {
                                db.playlistDao().insert(Playlist(name = pName))
                            } else {
                                selectedPlaylist!!.id
                            }
                            val sortOrder = db.playlistTrackDao().getNextSortOrder(playlistId)
                            db.playlistTrackDao().insert(PlaylistTrack(playlistId, item.trackId, sortOrder))
                            existingPlaylists = db.playlistDao().getAll()
                            snackbarHostState.showSnackbar("Toegevoegd aan '$pName'")
                            showPlaylistDialog = null
                        }
                    },
                    enabled = canSave
                ) { Text("Toevoegen") }
            },
            dismissButton = {
                TextButton(onClick = { showPlaylistDialog = null }) { Text("Annuleren") }
            }
        )
    }
}

// --- Gedeelde Card ---

@Composable
private fun LibraryCard(
    item: LibraryItem,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    actions: @Composable RowScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = iconTint
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.artist.isNotBlank()) {
                        Text(
                            item.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text("·", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        item.sizeFormatted,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            actions()
        }
    }
}

// --- Helpers ---

@Composable
private fun ChannelOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
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
            actionLabel = "Openen",
            duration = SnackbarDuration.Long
        ).let { result ->
            if (result == SnackbarResult.ActionPerformed) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                )
            }
        }
        return
    }

    val dummyTrack = DeezerTrack(
        id = extractTrackId(file.name),
        title = file.nameWithoutExtension,
        artist = DeezerArtist(name = ""),
        preview = ""
    )

    val success = when (channel) {
        Channel.CALL -> ringtoneManager.setAsRingtone(dummyTrack, file)
        Channel.NOTIFICATION -> ringtoneManager.setAsNotification(dummyTrack, file)
        else -> false
    }

    snackbarHostState.showSnackbar(
        if (success) "Ingesteld als ${channel.name.lowercase()}"
        else "Instellen mislukt"
    )
}

private suspend fun saveGlobalAssignment(db: RingtoneDatabase, item: LibraryItem, channel: Channel) {
    db.savedTrackDao().insert(
        SavedTrack(
            deezerTrackId = item.trackId,
            title = item.title,
            artist = item.artist,
            previewUrl = "",
            localPath = item.file.absolutePath,
            playlistName = "_system_${channel.name.lowercase()}"
        )
    )
    val existing = db.playlistDao().getActiveGlobalForChannel(channel)
    if (existing.isNotEmpty()) {
        val playlist = existing.first()
        val sortOrder = db.playlistTrackDao().getNextSortOrder(playlist.id)
        db.playlistTrackDao().insert(PlaylistTrack(playlist.id, item.trackId, sortOrder))
        db.playlistDao().update(playlist.copy(mode = Mode.FIXED, lastPlayedTrackId = item.trackId))
    } else {
        val channelName = when (channel) {
            Channel.SMS -> "SMS"
            Channel.WHATSAPP -> "WhatsApp"
            else -> channel.name
        }
        val playlistId = db.playlistDao().insert(
            Playlist(
                name = "$channelName ringtone",
                channel = channel,
                mode = Mode.FIXED,
                schedule = Schedule.MANUAL,
                isActive = true
            )
        )
        db.playlistTrackDao().insert(PlaylistTrack(playlistId, item.trackId, 0))
        ConflictResolver(db).enforceOneActivePerChannelScope(playlistId)
    }
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
