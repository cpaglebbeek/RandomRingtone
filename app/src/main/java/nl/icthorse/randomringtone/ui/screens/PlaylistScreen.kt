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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    ringtoneManager: AppRingtoneManager,
    snackbarHostState: SnackbarHostState,
    db: RingtoneDatabase? = null,
    onOpenEditor: ((DeezerTrack, java.io.File) -> Unit)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<DeezerTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val api = remember { DeezerApi() }
    val context = LocalContext.current

    // Laad bestaande playlists voor het "opslaan" dialoog
    var existingPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    LaunchedEffect(Unit) {
        if (db != null) {
            existingPlaylists = db.playlistDao().getAll()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Zoek artiest, nummer of playlist") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (searchQuery.isNotBlank()) {
                    isLoading = true
                    errorMessage = null
                    scope.launch {
                        try {
                            tracks = api.searchTracks(searchQuery)
                        } catch (e: Exception) {
                            errorMessage = "Zoeken mislukt: ${e.message}"
                        } finally {
                            isLoading = false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = searchQuery.isNotBlank() && !isLoading
        ) {
            Text(if (isLoading) "Zoeken..." else "Zoeken op Deezer")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Error message
        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Results
        if (tracks.isEmpty() && !isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Zoek een nummer om als ringtone te gebruiken",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val previewTracks = tracks.filter { it.hasPreview }
            Text(
                text = "${previewTracks.size} van ${tracks.size} tracks hebben een preview",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(previewTracks) { track ->
                    TrackItem(
                        track = track,
                        onEdit = if (onOpenEditor != null) {
                            {
                                scope.launch {
                                    try {
                                        snackbarHostState.showSnackbar(
                                            "Downloaden...", duration = SnackbarDuration.Short
                                        )
                                        val file = ringtoneManager.downloadPreview(track)
                                        onOpenEditor(track, file)
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Download mislukt: ${e.message}")
                                    }
                                }
                            }
                        } else null,
                        onSetRingtone = {
                            scope.launch {
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
                                    return@launch
                                }
                                try {
                                    val file = ringtoneManager.downloadPreview(track)
                                    val success = ringtoneManager.setAsRingtone(track, file)
                                    snackbarHostState.showSnackbar(
                                        if (success) "Ringtone ingesteld: ${track.artist.name} - ${track.titleShort}"
                                        else "Instellen mislukt — controleer permissies"
                                    )
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Fout: ${e.message}")
                                }
                            }
                        },
                        existingPlaylists = existingPlaylists,
                        onSaveToPlaylist = if (db != null) { playlistId, playlistName ->
                            scope.launch {
                                val file = ringtoneManager.downloadPreview(track)
                                // Sla track op in saved_tracks
                                db.savedTrackDao().insert(
                                    SavedTrack(
                                        deezerTrackId = track.id,
                                        title = track.titleShort.ifBlank { track.title },
                                        artist = track.artist.name,
                                        previewUrl = track.preview,
                                        localPath = file.absolutePath,
                                        playlistName = playlistName
                                    )
                                )
                                // Link aan playlist via playlist_tracks
                                val targetPlaylistId = if (playlistId != null) {
                                    playlistId
                                } else {
                                    // Nieuwe playlist aanmaken
                                    db.playlistDao().insert(
                                        Playlist(name = playlistName)
                                    )
                                }
                                val sortOrder = db.playlistTrackDao().getNextSortOrder(targetPlaylistId)
                                db.playlistTrackDao().insert(
                                    PlaylistTrack(targetPlaylistId, track.id, sortOrder)
                                )
                                // Refresh playlist lijst
                                existingPlaylists = db.playlistDao().getAll()
                                snackbarHostState.showSnackbar(
                                    "Toegevoegd aan '$playlistName': ${track.artist.name} - ${track.titleShort}"
                                )
                            }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackItem(
    track: DeezerTrack,
    onEdit: (() -> Unit)? = null,
    onSetRingtone: () -> Unit,
    existingPlaylists: List<Playlist> = emptyList(),
    onSaveToPlaylist: ((Long?, String) -> Unit)? = null
) {
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var createNew by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.titleShort.ifBlank { track.title },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Bewerken")
                }
            }
            if (onSaveToPlaylist != null) {
                IconButton(onClick = { showPlaylistDialog = true }) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = "Toevoegen aan playlist")
                }
            }
            FilledTonalButton(onClick = onSetRingtone) {
                Text("Ringtone")
            }
        }
    }

    if (showPlaylistDialog && onSaveToPlaylist != null) {
        AlertDialog(
            onDismissRequest = {
                showPlaylistDialog = false
                selectedPlaylist = null
                createNew = false
                newPlaylistName = ""
            },
            title = { Text("Toevoegen aan playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Bestaande playlists
                    if (existingPlaylists.isNotEmpty()) {
                        Text("Bestaande playlists:", style = MaterialTheme.typography.labelLarge)
                        existingPlaylists.forEach { playlist ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                RadioButton(
                                    selected = selectedPlaylist == playlist && !createNew,
                                    onClick = {
                                        selectedPlaylist = playlist
                                        createNew = false
                                    }
                                )
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }

                    // Nieuwe playlist optie
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = createNew,
                            onClick = {
                                createNew = true
                                selectedPlaylist = null
                            }
                        )
                        Text(
                            text = "Nieuwe playlist",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (createNew) {
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            label = { Text("Playlist naam") },
                            placeholder = { Text("bijv. Rock, Chill, Favoriet...") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 40.dp)
                        )
                    }
                }
            },
            confirmButton = {
                val canSave = if (createNew) newPlaylistName.isNotBlank() else selectedPlaylist != null
                Button(
                    onClick = {
                        if (createNew && newPlaylistName.isNotBlank()) {
                            onSaveToPlaylist(null, newPlaylistName.trim())
                        } else if (selectedPlaylist != null) {
                            onSaveToPlaylist(selectedPlaylist!!.id, selectedPlaylist!!.name)
                        }
                        showPlaylistDialog = false
                        selectedPlaylist = null
                        createNew = false
                        newPlaylistName = ""
                    },
                    enabled = canSave
                ) { Text("Toevoegen") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPlaylistDialog = false
                    selectedPlaylist = null
                    createNew = false
                    newPlaylistName = ""
                }) { Text("Annuleren") }
            }
        )
    }
}
