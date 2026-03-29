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

@Composable
fun LibraryScreen(
    db: RingtoneDatabase,
    ringtoneManager: AppRingtoneManager,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var playlists by remember { mutableStateOf<List<String>>(emptyList()) }
    var tracksByPlaylist by remember { mutableStateOf<Map<String, List<SavedTrack>>>(emptyMap()) }

    LaunchedEffect(Unit) {
        playlists = db.savedTrackDao().getPlaylistNames()
        tracksByPlaylist = playlists.associateWith { db.savedTrackDao().getByPlaylist(it) }
    }

    // Refresh wanneer scherm terugkomt
    fun refresh() {
        scope.launch {
            playlists = db.savedTrackDao().getPlaylistNames()
            tracksByPlaylist = playlists.associateWith { db.savedTrackDao().getByPlaylist(it) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Bibliotheek", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))

        val totalTracks = tracksByPlaylist.values.sumOf { it.size }
        Text(
            text = "${playlists.size} playlists, $totalTracks tracks",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Nog geen tracks opgeslagen",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Zoek een nummer en tik op het playlist-icoon",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                playlists.forEach { playlistName ->
                    val tracks = tracksByPlaylist[playlistName] ?: emptyList()

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "$playlistName (${tracks.size})",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }

                    items(tracks) { track ->
                        SavedTrackItem(
                            track = track,
                            onSetAsRingtone = {
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
                                        val deezerTrack = DeezerTrack(
                                            id = track.deezerTrackId,
                                            title = track.title,
                                            artist = DeezerArtist(name = track.artist),
                                            preview = track.previewUrl
                                        )
                                        val file = ringtoneManager.downloadPreview(deezerTrack)
                                        val success = ringtoneManager.setAsRingtone(deezerTrack, file)
                                        snackbarHostState.showSnackbar(
                                            if (success) "Ringtone ingesteld: ${track.artist} - ${track.title}"
                                            else "Instellen mislukt"
                                        )
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Fout: ${e.message}")
                                    }
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    db.savedTrackDao().removeFromPlaylist(track.deezerTrackId, playlistName)
                                    refresh()
                                    snackbarHostState.showSnackbar("Verwijderd: ${track.artist} - ${track.title}")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedTrackItem(
    track: SavedTrack,
    onSetAsRingtone: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (track.localPath != null) Icons.Default.DownloadDone else Icons.Default.CloudQueue,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (track.localPath != null)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalButton(onClick = onSetAsRingtone) {
                Text("Ringtone")
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Verwijderen",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
