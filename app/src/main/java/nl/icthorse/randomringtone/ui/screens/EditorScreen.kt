package nl.icthorse.randomringtone.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.audio.AudioDecoder
import nl.icthorse.randomringtone.audio.AudioPlayer
import nl.icthorse.randomringtone.audio.AudioTrimmer
import nl.icthorse.randomringtone.data.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    trackTitle: String,
    trackArtist: String,
    audioFile: File,
    deezerTrackId: Long,
    previewUrl: String,
    db: RingtoneDatabase,
    ringtoneManager: AppRingtoneManager,
    snackbarHostState: SnackbarHostState,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var waveformData by remember { mutableStateOf<AudioDecoder.WaveformData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var startFraction by remember { mutableFloatStateOf(0f) }
    var endFraction by remember { mutableFloatStateOf(0f) }
    val player = remember { AudioPlayer() }
    var isPlaying by remember { mutableStateOf(false) }

    // Dialoog state
    var showRingtoneDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var ringtoneName by remember { mutableStateOf(trackTitle) }

    // Bestaande playlists laden
    var existingPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    LaunchedEffect(Unit) {
        existingPlaylists = db.playlistDao().getAll()
    }

    // Laad waveform data
    LaunchedEffect(audioFile) {
        try {
            waveformData = AudioDecoder.extractWaveform(audioFile)
            waveformData?.let { data ->
                val defaultEndMs = minOf(20_000L, data.durationMs)
                endFraction = defaultEndMs.toFloat() / data.durationMs.toFloat()
            }
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Fout bij laden audio: ${e.message}")
        }
        isLoading = false
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { player.release(); onDone() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Terug")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(trackTitle, style = MaterialTheme.typography.titleLarge)
                Text(trackArtist, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            waveformData?.let { data ->
                Text(
                    formatTime(data.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Waveform laden...")
            }
        } else {
            waveformData?.let { data ->
                val startMs = (startFraction * data.durationMs).toLong()
                val endMs = (endFraction * data.durationMs).toLong()
                val selectionMs = endMs - startMs

                // Waveform visualisatie
                WaveformView(
                    amplitudes = data.amplitudes,
                    startFraction = startFraction,
                    endFraction = endFraction,
                    onStartChanged = { startFraction = it.coerceIn(0f, endFraction - 0.01f) },
                    onEndChanged = { endFraction = it.coerceIn(startFraction + 0.01f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Tijd indicators
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Start: ${formatTime(startMs)}",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Duur: ${formatTime(selectionMs)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text("Eind: ${formatTime(endMs)}",
                        style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Sliders
                Text("Start", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = startFraction,
                    onValueChange = { startFraction = it.coerceAtMost(endFraction - 0.01f) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Eind", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = endFraction,
                    onValueChange = { endFraction = it.coerceAtLeast(startFraction + 0.01f) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Knoppen
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Preview
                    OutlinedButton(
                        onClick = {
                            if (isPlaying) {
                                player.stop()
                                isPlaying = false
                            } else {
                                player.playSelection(audioFile, startMs, endMs) {
                                    isPlaying = false
                                }
                                isPlaying = true
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isPlaying) "Stop" else "Preview")
                    }

                    // Direct als ringtone — vraagt eerst om naam
                    Button(
                        onClick = {
                            ringtoneName = trackTitle
                            showRingtoneDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Ringtone")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Opslaan in bibliotheek
                OutlinedButton(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.LibraryAdd, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Opslaan in playlist")
                }
            }
        }
    }

    // === RINGTONE NAAM DIALOOG ===
    if (showRingtoneDialog) {
        AlertDialog(
            onDismissRequest = { showRingtoneDialog = false },
            title = { Text("Ringtone instellen") },
            text = {
                OutlinedTextField(
                    value = ringtoneName,
                    onValueChange = { ringtoneName = it },
                    label = { Text("Naam voor ringtone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRingtoneDialog = false
                        scope.launch {
                            player.stop()
                            isPlaying = false
                            try {
                                val data = waveformData ?: return@launch
                                val startMs = (startFraction * data.durationMs).toLong()
                                val endMs = (endFraction * data.durationMs).toLong()

                                val trimDir = ringtoneManager.storage.getDownloadDir()
                                val trimmedFile = File(trimDir, "trimmed_${System.currentTimeMillis()}.mp3")
                                AudioTrimmer.trim(audioFile, trimmedFile, startMs, endMs)

                                val name = ringtoneName.trim().ifBlank { trackTitle }
                                val deezerTrack = DeezerTrack(
                                    id = deezerTrackId,
                                    title = name,
                                    titleShort = name,
                                    artist = DeezerArtist(name = trackArtist),
                                    preview = previewUrl
                                )
                                val success = ringtoneManager.setAsRingtone(deezerTrack, trimmedFile)
                                snackbarHostState.showSnackbar(
                                    if (success) "Ringtone ingesteld: $name"
                                    else "Instellen mislukt — controleer permissies"
                                )
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Fout: ${e.message}")
                            }
                        }
                    },
                    enabled = ringtoneName.isNotBlank()
                ) { Text("Instellen") }
            },
            dismissButton = {
                TextButton(onClick = { showRingtoneDialog = false }) { Text("Annuleren") }
            }
        )
    }

    // === OPSLAAN IN PLAYLIST DIALOOG (met bestaande playlists) ===
    if (showSaveDialog) {
        var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
        var createNew by remember { mutableStateOf(existingPlaylists.isEmpty()) }
        var newPlaylistName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Opslaan in playlist") },
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
                                    onClick = { selectedPlaylist = playlist; createNew = false }
                                )
                                Text(playlist.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }

                    // Nieuwe playlist
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
                            try {
                                val data = waveformData ?: return@launch
                                val startMs = (startFraction * data.durationMs).toLong()
                                val endMs = (endFraction * data.durationMs).toLong()
                                val pName = if (createNew) newPlaylistName.trim() else selectedPlaylist!!.name

                                // Trim naar ringtone dir
                                val trimDir = ringtoneManager.storage.getDownloadDir()
                                val trimmedFile = File(trimDir, "lib_${deezerTrackId}_${pName}.mp3")
                                AudioTrimmer.trim(audioFile, trimmedFile, startMs, endMs)

                                // Sla op in saved_tracks
                                db.savedTrackDao().insert(
                                    SavedTrack(
                                        deezerTrackId = deezerTrackId,
                                        title = trackTitle,
                                        artist = trackArtist,
                                        previewUrl = previewUrl,
                                        localPath = trimmedFile.absolutePath,
                                        playlistName = pName
                                    )
                                )

                                // Link aan playlist via playlist_tracks
                                val playlistId = if (createNew) {
                                    db.playlistDao().insert(Playlist(name = pName))
                                } else {
                                    selectedPlaylist!!.id
                                }
                                val sortOrder = db.playlistTrackDao().getNextSortOrder(playlistId)
                                db.playlistTrackDao().insert(
                                    PlaylistTrack(playlistId, deezerTrackId, sortOrder)
                                )

                                existingPlaylists = db.playlistDao().getAll()
                                snackbarHostState.showSnackbar(
                                    "Opgeslagen in '$pName' (${formatTime(endMs - startMs)})"
                                )
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Fout bij opslaan: ${e.message}")
                            }
                            showSaveDialog = false
                        }
                    },
                    enabled = canSave
                ) { Text("Opslaan") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Annuleren") }
            }
        )
    }
}

// --- Waveform Composable ---

@Composable
private fun WaveformView(
    amplitudes: List<Float>,
    startFraction: Float,
    endFraction: Float,
    onStartChanged: (Float) -> Unit,
    onEndChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val selectionColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val handleColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp)
            )
            .padding(8.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(amplitudes.size) {
                    detectHorizontalDragGestures { change, _ ->
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        val distToStart = kotlin.math.abs(fraction - startFraction)
                        val distToEnd = kotlin.math.abs(fraction - endFraction)
                        if (distToStart < distToEnd) {
                            onStartChanged(fraction)
                        } else {
                            onEndChanged(fraction)
                        }
                    }
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val center = canvasHeight / 2
            val barWidth = canvasWidth / amplitudes.size.coerceAtLeast(1)

            amplitudes.forEachIndexed { index, amplitude ->
                val x = index * barWidth
                val fraction = index.toFloat() / amplitudes.size
                val inSelection = fraction in startFraction..endFraction
                val barHeight = amplitude * canvasHeight * 0.8f
                val color = if (inSelection) primaryColor else inactiveColor

                drawLine(
                    color = color,
                    start = Offset(x + barWidth / 2, center - barHeight / 2),
                    end = Offset(x + barWidth / 2, center + barHeight / 2),
                    strokeWidth = barWidth * 0.7f
                )
            }

            val startX = startFraction * canvasWidth
            val endX = endFraction * canvasWidth
            drawRect(
                color = selectionColor,
                topLeft = Offset(startX, 0f),
                size = Size(endX - startX, canvasHeight)
            )

            drawLine(color = handleColor, start = Offset(startX, 0f), end = Offset(startX, canvasHeight), strokeWidth = 4f)
            drawLine(color = handleColor, start = Offset(endX, 0f), end = Offset(endX, canvasHeight), strokeWidth = 4f)
            drawCircle(color = handleColor, radius = 8f, center = Offset(startX, center))
            drawCircle(color = handleColor, radius = 8f, center = Offset(endX, center))
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
