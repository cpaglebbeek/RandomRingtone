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
    db: RingtoneDatabase? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<DeezerTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val api = remember { DeezerApi() }
    val context = LocalContext.current

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
                        onSaveToPlaylist = if (db != null) { playlistName ->
                            scope.launch {
                                val file = ringtoneManager.downloadPreview(track)
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
                                snackbarHostState.showSnackbar(
                                    "Opgeslagen in '$playlistName': ${track.artist.name} - ${track.titleShort}"
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
    onSetRingtone: () -> Unit,
    onSaveToPlaylist: ((String) -> Unit)? = null
) {
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

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
            onDismissRequest = { showPlaylistDialog = false },
            title = { Text("Opslaan in playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist naam") },
                    placeholder = { Text("bijv. Rock, Chill, Favoriet...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            onSaveToPlaylist(newPlaylistName.trim())
                            showPlaylistDialog = false
                            newPlaylistName = ""
                        }
                    },
                    enabled = newPlaylistName.isNotBlank()
                ) { Text("Opslaan") }
            },
            dismissButton = {
                TextButton(onClick = { showPlaylistDialog = false }) { Text("Annuleren") }
            }
        )
    }
}
