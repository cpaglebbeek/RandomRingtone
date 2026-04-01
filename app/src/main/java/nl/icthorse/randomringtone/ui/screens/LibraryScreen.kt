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

data class FileInfo(
    val file: File,
    val name: String,
    val sizeFormatted: String
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

    var downloads by remember { mutableStateOf<List<FileInfo>>(emptyList()) }
    var ringtones by remember { mutableStateOf<List<FileInfo>>(emptyList()) }
    var savedTracks by remember { mutableStateOf<List<SavedTrack>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTab by remember { mutableIntStateOf(0) }

    fun refresh() {
        scope.launch {
            val rtDir = ringtoneManager.storage.getRingtoneDir()
            ringtones = (rtDir.listFiles() ?: emptyArray())
                .filter { it.extension == "mp3" || it.extension == "m4a" }
                .sortedByDescending { it.lastModified() }
                .map { FileInfo(it, it.nameWithoutExtension, formatFileSize(it.length())) }

            // Downloads: filter bestanden die al in ringtones staan (zelfde naam)
            val ringtoneNames = ringtones.map { it.file.name }.toSet()
            val dlDir = ringtoneManager.storage.getDownloadDir()
            downloads = (dlDir.listFiles() ?: emptyArray())
                .filter { it.extension == "mp3" && it.name !in ringtoneNames }
                .sortedByDescending { it.lastModified() }
                .map { FileInfo(it, it.nameWithoutExtension, formatFileSize(it.length())) }

            playlists = db.savedTrackDao().getPlaylistNames()
            savedTracks = playlists.flatMap { db.savedTrackDao().getByPlaylist(it) }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Text(
            "Bibliotheek",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp)
        )

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
                onTrim = { fileInfo ->
                    // Zoek track info in DB, of gebruik bestandsnaam
                    val trackId = extractTrackId(fileInfo.file.name)
                    onOpenEditor?.invoke(
                        fileInfo.name, "", fileInfo.file, trackId, ""
                    )
                },
                onDelete = { fileInfo ->
                    scope.launch {
                        val trackId = extractTrackId(fileInfo.file.name)
                        val existing = db.savedTrackDao().getById(trackId)
                        if (existing != null) db.savedTrackDao().delete(existing)
                        fileInfo.file.delete()
                        refresh()
                        snackbarHostState.showSnackbar("Verwijderd: ${fileInfo.name}")
                    }
                }
            )
            1 -> RingtonesTab(
                ringtones = ringtones,
                db = db,
                ringtoneManager = ringtoneManager,
                snackbarHostState = snackbarHostState,
                onRefresh = { refresh() }
            )
        }
    }
}

// --- Downloads Tab ---

@Composable
private fun DownloadsTab(
    downloads: List<FileInfo>,
    onTrim: (FileInfo) -> Unit,
    onDelete: (FileInfo) -> Unit
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
            items(downloads) { fileInfo ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.AudioFile,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                fileInfo.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                fileInfo.sizeFormatted,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Trim knop → opent editor
                        FilledTonalButton(onClick = { onTrim(fileInfo) }) {
                            Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Trim")
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = { onDelete(fileInfo) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Verwijderen",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

// --- Ringtones Tab ---

@Composable
private fun RingtonesTab(
    ringtones: List<FileInfo>,
    db: RingtoneDatabase,
    ringtoneManager: AppRingtoneManager,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showChannelDialog by remember { mutableStateOf<FileInfo?>(null) }
    var showPlaylistDialog by remember { mutableStateOf<FileInfo?>(null) }
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
            items(ringtones) { fileInfo ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                fileInfo.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                fileInfo.sizeFormatted,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Instellen als ringtone (kies kanaal)
                        IconButton(onClick = { showChannelDialog = fileInfo }) {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = "Instellen als...")
                        }
                        // Toevoegen aan playlist
                        IconButton(onClick = { showPlaylistDialog = fileInfo }) {
                            Icon(Icons.Default.PlaylistAdd, contentDescription = "Aan playlist")
                        }
                        // Verwijderen
                        IconButton(onClick = {
                            scope.launch {
                                val trackId = extractTrackId(fileInfo.file.name)
                                val existing = db.savedTrackDao().getById(trackId)
                                if (existing != null) db.savedTrackDao().delete(existing)
                                fileInfo.file.delete()
                                onRefresh()
                                snackbarHostState.showSnackbar("Verwijderd: ${fileInfo.name}")
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Verwijderen",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    // Kanaal-selectie dialoog
    showChannelDialog?.let { fileInfo ->
        AlertDialog(
            onDismissRequest = { showChannelDialog = null },
            title = { Text("Instellen als...") },
            text = {
                Column {
                    ChannelOption("Telefoon (globaal)", Icons.Default.Phone) {
                        scope.launch {
                            setAsChannel(context, ringtoneManager, fileInfo.file, Channel.CALL, snackbarHostState)
                            showChannelDialog = null
                        }
                    }
                    ChannelOption("Notificatie (globaal)", Icons.Default.Notifications) {
                        scope.launch {
                            setAsChannel(context, ringtoneManager, fileInfo.file, Channel.NOTIFICATION, snackbarHostState)
                            showChannelDialog = null
                        }
                    }
                    ChannelOption("SMS (globaal)", Icons.Default.Sms) {
                        scope.launch {
                            saveGlobalAssignment(db, fileInfo, Channel.SMS)
                            snackbarHostState.showSnackbar("SMS ringtone ingesteld (via NotificationService)")
                            showChannelDialog = null
                        }
                    }
                    ChannelOption("WhatsApp (globaal)", Icons.Default.Chat) {
                        scope.launch {
                            saveGlobalAssignment(db, fileInfo, Channel.WHATSAPP)
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
    showPlaylistDialog?.let { fileInfo ->
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
                            val trackId = extractTrackId(fileInfo.file.name)
                            val pName = if (createNew) newPlaylistName.trim() else selectedPlaylist!!.name
                            db.savedTrackDao().insert(
                                SavedTrack(
                                    deezerTrackId = trackId,
                                    title = fileInfo.name,
                                    artist = "",
                                    previewUrl = "",
                                    localPath = fileInfo.file.absolutePath,
                                    playlistName = pName
                                )
                            )
                            // Link via playlist_tracks
                            val playlistId = if (createNew) {
                                db.playlistDao().insert(Playlist(name = pName))
                            } else {
                                selectedPlaylist!!.id
                            }
                            val sortOrder = db.playlistTrackDao().getNextSortOrder(playlistId)
                            db.playlistTrackDao().insert(PlaylistTrack(playlistId, trackId, sortOrder))
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

private suspend fun saveGlobalAssignment(db: RingtoneDatabase, fileInfo: FileInfo, channel: Channel) {
    val trackId = extractTrackId(fileInfo.file.name)
    // Sla track op in DB als die nog niet bestaat
    db.savedTrackDao().insert(
        SavedTrack(
            deezerTrackId = trackId,
            title = fileInfo.name,
            artist = "",
            previewUrl = "",
            localPath = fileInfo.file.absolutePath,
            playlistName = "_system_${channel.name.lowercase()}"
        )
    )
    // Maak of hergebruik globale playlist voor dit kanaal
    val existing = db.playlistDao().getActiveGlobalForChannel(channel)
    if (existing.isNotEmpty()) {
        val playlist = existing.first()
        // Voeg track toe aan bestaande playlist
        val sortOrder = db.playlistTrackDao().getNextSortOrder(playlist.id)
        db.playlistTrackDao().insert(PlaylistTrack(playlist.id, trackId, sortOrder))
        // Zet als FIXED modus met deze track
        db.playlistDao().update(playlist.copy(mode = Mode.FIXED, lastPlayedTrackId = trackId))
    } else {
        // Maak nieuwe FIXED playlist aan
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
        db.playlistTrackDao().insert(PlaylistTrack(playlistId, trackId, 0))
        // Enforce 1-actief-per-kanaal
        ConflictResolver(db).enforceOneActivePerChannelScope(playlistId)
    }
}

private fun extractTrackId(fileName: String): Long {
    // Probeer track ID uit bestandsnaam te halen: download_12345.mp3 of ringtone_12345.mp3
    val match = Regex("(?:download|ringtone|lib)_(\\d+)").find(fileName)
    if (match != null) return match.groupValues[1].toLongOrNull() ?: fileName.hashCode().toLong().let { if (it < 0) -it else it }
    return fileName.hashCode().toLong().let { if (it < 0) -it else it }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
