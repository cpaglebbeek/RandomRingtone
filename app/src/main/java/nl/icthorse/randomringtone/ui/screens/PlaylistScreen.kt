package nl.icthorse.randomringtone.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.AppRingtoneManager
import nl.icthorse.randomringtone.data.DeezerApi
import nl.icthorse.randomringtone.data.DeezerTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    ringtoneManager: AppRingtoneManager,
    snackbarHostState: SnackbarHostState
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
                                // Check WRITE_SETTINGS permissie
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

                                // Download + instellen
                                try {
                                    snackbarHostState.showSnackbar(
                                        "Downloaden: ${track.artist.name} - ${track.titleShort}...",
                                        duration = SnackbarDuration.Short
                                    )
                                    val file = ringtoneManager.downloadPreview(track)
                                    val success = ringtoneManager.setAsRingtone(track, file)
                                    if (success) {
                                        snackbarHostState.showSnackbar(
                                            "Ringtone ingesteld: ${track.artist.name} - ${track.titleShort}"
                                        )
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            "Instellen mislukt — controleer permissies"
                                        )
                                    }
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar(
                                        "Fout: ${e.message}"
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackItem(
    track: DeezerTrack,
    onSetRingtone: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
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
            FilledTonalButton(onClick = onSetRingtone) {
                Text("Instellen")
            }
        }
    }
}
