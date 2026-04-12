package nl.icthorse.randomringtone.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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

    // Werkbestand — start als origineel, verandert na elke "Toepassen"
    var workingFile by remember { mutableStateOf(audioFile) }
    var isInitialLoad by remember { mutableStateOf(true) }

    var waveformData by remember { mutableStateOf<AudioDecoder.WaveformData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableFloatStateOf(0f) }
    var loadStartTime by remember { mutableStateOf(0L) }
    var startFraction by remember { mutableFloatStateOf(0f) }
    var endFraction by remember { mutableFloatStateOf(1f) }
    val player = remember { AudioPlayer() }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackFraction by remember { mutableFloatStateOf(-1f) }
    var isPreviewLoading by remember { mutableStateOf(false) }
    var fadePreviewStartFrac by remember { mutableFloatStateOf(0f) }
    var fadePreviewEndFrac by remember { mutableFloatStateOf(1f) }
    var isFadePreview by remember { mutableStateOf(false) }

    // Zoom state
    var viewStart by remember { mutableFloatStateOf(0f) }
    var viewEnd by remember { mutableFloatStateOf(1f) }

    // Fade state
    var fadeInEnabled by remember { mutableStateOf(false) }
    var fadeOutEnabled by remember { mutableStateOf(false) }
    var fadeInMs by remember { mutableStateOf(500L) }
    var fadeOutMs by remember { mutableStateOf(500L) }

    // Apply state
    var isApplying by remember { mutableStateOf(false) }
    var applyProgress by remember { mutableFloatStateOf(-1f) }
    var applyStartTime by remember { mutableStateOf(0L) }

    // Save dialog
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveProgress by remember { mutableFloatStateOf(-1f) }
    var saveStartTime by remember { mutableStateOf(0L) }

    // Bestaande playlists
    var existingPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    LaunchedEffect(Unit) {
        existingPlaylists = db.playlistDao().getAll()
    }

    // Laad waveform data — herlaadt wanneer workingFile verandert (na "Toepassen")
    LaunchedEffect(workingFile) {
        isLoading = true
        loadProgress = 0f
        loadStartTime = System.currentTimeMillis()
        try {
            val result = AudioDecoder.extractWaveform(workingFile) { progress ->
                loadProgress = progress
            }
            if (result.error != null) {
                snackbarHostState.showSnackbar("Fout bij laden audio: ${result.error}")
                waveformData = null
            } else {
                waveformData = result
                if (isInitialLoad && result.durationMs > 0) {
                    val defaultEndMs = minOf(20_000L, result.durationMs)
                    endFraction = defaultEndMs.toFloat() / result.durationMs.toFloat()
                    isInitialLoad = false
                }
            }
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Fout bij laden audio: ${e.message}")
            waveformData = null
        }
        isLoading = false
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // Playback positie marker — remap bij fade preview (temp file begint bij 0)
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val data = waveformData
            while (isPlaying && data != null) {
                val pos = player.getCurrentPosition()
                playbackFraction = if (isFadePreview) {
                    val selDurationMs = (fadePreviewEndFrac - fadePreviewStartFrac) * data.durationMs
                    if (selDurationMs > 0) fadePreviewStartFrac + (pos / selDurationMs) * (fadePreviewEndFrac - fadePreviewStartFrac)
                    else 0f
                } else {
                    pos.toFloat() / data.durationMs
                }
                kotlinx.coroutines.delay(50)
            }
        }
        playbackFraction = -1f
        isFadePreview = false
    }

    // Heeft de gebruiker wijzigingen die nog niet toegepast zijn?
    val hasChanges = startFraction > 0.001f || endFraction < 0.999f || fadeInEnabled || fadeOutEnabled

    fun zoomIn() {
        val center = (startFraction + endFraction) / 2
        val range = viewEnd - viewStart
        val newRange = (range / 2).coerceAtLeast(0.05f)
        viewStart = (center - newRange / 2).coerceAtLeast(0f)
        viewEnd = viewStart + newRange
        if (viewEnd > 1f) { viewEnd = 1f; viewStart = (1f - newRange).coerceAtLeast(0f) }
    }

    fun zoomOut() {
        val center = (viewStart + viewEnd) / 2
        val range = viewEnd - viewStart
        val newRange = (range * 2).coerceAtMost(1f)
        viewStart = (center - newRange / 2).coerceAtLeast(0f)
        viewEnd = viewStart + newRange
        if (viewEnd > 1f) { viewEnd = 1f; viewStart = (1f - newRange).coerceAtLeast(0f) }
    }

    // === TOEPASSEN: trim+fade → nieuw werkbestand → herlaad waveform ===
    fun applyChanges() {
        isApplying = true
        applyProgress = 0f
        applyStartTime = System.currentTimeMillis()
        scope.launch {
            try {
                val data = waveformData ?: return@launch
                val sMs = (startFraction * data.durationMs).toLong()
                val eMs = (endFraction * data.durationMs).toLong()
                val hasFade = fadeInEnabled || fadeOutEnabled
                val ext = if (hasFade || workingFile.extension.lowercase() == "m4a") "m4a" else "mp3"
                val tempFile = File(context.cacheDir, "applied_${System.currentTimeMillis()}.$ext")

                player.stop()
                isPlaying = false

                if (hasFade) {
                    AudioTrimmer.trimWithFade(
                        workingFile, tempFile, sMs, eMs,
                        if (fadeInEnabled) fadeInMs else 0,
                        if (fadeOutEnabled) fadeOutMs else 0
                    ) { p -> applyProgress = p }
                } else {
                    AudioTrimmer.trim(workingFile, tempFile, sMs, eMs) { p ->
                        applyProgress = p
                    }
                }

                // Reset controls — het resultaat is nu het werkbestand
                startFraction = 0f
                endFraction = 1f
                fadeInEnabled = false
                fadeOutEnabled = false
                viewStart = 0f
                viewEnd = 1f

                // Switch naar nieuw werkbestand → triggert waveform reload
                workingFile = tempFile

                snackbarHostState.showSnackbar("Toegepast (${formatTime(eMs - sMs)})")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Fout bij toepassen: ${e.message}")
            }
            isApplying = false
            applyProgress = -1f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
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

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Waveform laden...", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { loadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                val pct = (loadProgress * 100).toInt()
                val eta = if (loadProgress > 0.05f) {
                    val elapsed = System.currentTimeMillis() - loadStartTime
                    val remaining = (elapsed / loadProgress * (1f - loadProgress)).toLong()
                    if (remaining > 1000) " — ~${remaining / 1000}s" else " — <1s"
                } else ""
                Text("$pct%$eta", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            waveformData?.let { data ->
                val startMs = (startFraction * data.durationMs).toLong()
                val endMs = (endFraction * data.durationMs).toLong()
                val selectionMs = endMs - startMs

                // === ZOOM CONTROLS ===
                val zoomDisplay = 1f / (viewEnd - viewStart)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { zoomOut() }, enabled = viewEnd - viewStart < 0.99f) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom uit")
                    }
                    Text("%.1fx".format(zoomDisplay), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(40.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { zoomIn() }, enabled = viewEnd - viewStart > 0.06f) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
                    }
                }

                // === WAVEFORM ===
                WaveformView(
                    amplitudes = data.amplitudes,
                    startFraction = startFraction,
                    endFraction = endFraction,
                    viewStart = viewStart,
                    viewEnd = viewEnd,
                    fadeInFraction = if (fadeInEnabled) fadeInMs.toFloat() / data.durationMs else 0f,
                    fadeOutFraction = if (fadeOutEnabled) fadeOutMs.toFloat() / data.durationMs else 0f,
                    playbackFraction = playbackFraction,
                    onStartChanged = { startFraction = it.coerceIn(0f, endFraction - 0.01f) },
                    onEndChanged = { endFraction = it.coerceIn(startFraction + 0.01f, 1f) },
                    onTapped = { fraction ->
                        if (isPlaying && !isFadePreview) {
                            // Tijdens afspelen: skip naar geklikte positie
                            val seekMs = (fraction * data.durationMs).toLong()
                            player.seekTo(seekMs)
                        } else if (!isPlaying) {
                            // Niet aan het spelen: verplaats start marker
                            startFraction = fraction.coerceIn(0f, endFraction - 0.01f)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // === TIJD INVOER ===
                val focusManager = LocalFocusManager.current
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimeInputField(label = "Start", timeMs = startMs, maxMs = data.durationMs,
                        onTimeChanged = { ms ->
                            startFraction = (ms.toFloat() / data.durationMs).coerceIn(0f, endFraction - 0.01f)
                            focusManager.clearFocus()
                        })
                    Text("Duur: ${formatTime(selectionMs)}", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    TimeInputField(label = "Eind", timeMs = endMs, maxMs = data.durationMs,
                        onTimeChanged = { ms ->
                            endFraction = (ms.toFloat() / data.durationMs).coerceIn(startFraction + 0.01f, 1f)
                            focusManager.clearFocus()
                        })
                }

                Spacer(modifier = Modifier.height(8.dp))

                // === SLIDERS ===
                Text("Start", style = MaterialTheme.typography.labelSmall)
                Slider(value = startFraction, onValueChange = { startFraction = it.coerceAtMost(endFraction - 0.01f) },
                    valueRange = 0f..1f, modifier = Modifier.fillMaxWidth())
                Text("Eind", style = MaterialTheme.typography.labelSmall)
                Slider(value = endFraction, onValueChange = { endFraction = it.coerceAtLeast(startFraction + 0.01f) },
                    valueRange = 0f..1f, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(8.dp))

                // === FADE CONTROLS ===
                Text("Fade", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = fadeInEnabled, onClick = { fadeInEnabled = !fadeInEnabled },
                        label = { Text("Fade in") })
                    FilterChip(selected = fadeOutEnabled, onClick = { fadeOutEnabled = !fadeOutEnabled },
                        label = { Text("Fade out") })
                }
                if (fadeInEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("In:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(28.dp))
                        Slider(value = fadeInMs.toFloat(), onValueChange = { fadeInMs = (it / 100).toLong() * 100 },
                            valueRange = 100f..5000f, modifier = Modifier.weight(1f))
                        Text("${fadeInMs}ms", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
                    }
                }
                if (fadeOutEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Uit:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(28.dp))
                        Slider(value = fadeOutMs.toFloat(), onValueChange = { fadeOutMs = (it / 100).toLong() * 100 },
                            valueRange = 100f..5000f, modifier = Modifier.weight(1f))
                        Text("${fadeOutMs}ms", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // === APPLY PROGRESS ===
                if (isApplying && applyProgress >= 0f) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Toepassen...", style = MaterialTheme.typography.labelMedium)
                        LinearProgressIndicator(progress = { applyProgress }, modifier = Modifier.fillMaxWidth())
                        val pct = (applyProgress * 100).toInt()
                        val eta = if (applyProgress > 0.05f) {
                            val elapsed = System.currentTimeMillis() - applyStartTime
                            val remaining = (elapsed / applyProgress * (1f - applyProgress)).toLong()
                            if (remaining > 1000) " — ~${remaining / 1000}s" else " — <1s"
                        } else ""
                        Text("$pct%$eta", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // === KNOPPEN: Preview | Toepassen | Opslaan ===
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (isPlaying) {
                                player.stop(); isPlaying = false
                            } else if (fadeInEnabled || fadeOutEnabled) {
                                // Preview MET fade — maak tijdelijk bestand zodat fade hoorbaar is
                                isPreviewLoading = true
                                scope.launch {
                                    try {
                                        val d = waveformData ?: return@launch
                                        val sMs = (startFraction * d.durationMs).toLong()
                                        val eMs = (endFraction * d.durationMs).toLong()
                                        val previewFile = File(context.cacheDir, "preview_fade.m4a")
                                        AudioTrimmer.trimWithFade(
                                            workingFile, previewFile, sMs, eMs,
                                            if (fadeInEnabled) fadeInMs else 0,
                                            if (fadeOutEnabled) fadeOutMs else 0
                                        )
                                        fadePreviewStartFrac = startFraction
                                        fadePreviewEndFrac = endFraction
                                        isFadePreview = true
                                        isPreviewLoading = false
                                        player.playSelection(previewFile, 0, eMs - sMs) { isPlaying = false }
                                        isPlaying = true
                                    } catch (e: Exception) {
                                        isPreviewLoading = false
                                        snackbarHostState.showSnackbar("Preview mislukt: ${e.message}")
                                    }
                                }
                            } else {
                                // Preview zonder fade — direct afspelen
                                isFadePreview = false
                                player.playSelection(workingFile, startMs, endMs) { isPlaying = false }
                                isPlaying = true
                            }
                        },
                        enabled = !isApplying && !isPreviewLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isPreviewLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(when {
                            isPreviewLoading -> "Laden..."
                            isPlaying -> "Stop"
                            else -> "Preview"
                        })
                    }

                    FilledTonalButton(
                        onClick = { applyChanges() },
                        enabled = hasChanges && !isApplying,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Toepassen")
                    }

                    Button(
                        onClick = { showSaveDialog = true },
                        enabled = !isApplying,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Opslaan")
                    }
                }
            }
        }
    }

    // === OPSLAAN DIALOOG ===
    if (showSaveDialog) {
        val defaultName = buildString {
            if (trackArtist.isNotBlank()) append("$trackArtist - ")
            append(trackTitle)
            append(" (ringtone)")
        }
        var ringtoneName by remember { mutableStateOf(defaultName) }
        var setAsMainRingtone by remember { mutableStateOf(false) }
        var addToPlaylist by remember { mutableStateOf(true) }
        var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
        var createNew by remember { mutableStateOf(existingPlaylists.isEmpty()) }
        var newPlaylistName by remember { mutableStateOf("") }
        var isSaving by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isSaving) showSaveDialog = false },
            title = { Text("Ringtone opslaan") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    // Waarschuwing als er niet-toegepaste wijzigingen zijn
                    if (hasChanges) {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                            Text("Er zijn niet-toegepaste wijzigingen. Deze worden meegenomen bij opslaan.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp))
                        }
                    }

                    OutlinedTextField(value = ringtoneName, onValueChange = { ringtoneName = it },
                        label = { Text("Naam") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    HorizontalDivider()

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = addToPlaylist, onCheckedChange = { addToPlaylist = it })
                        Text("Toevoegen aan playlist", style = MaterialTheme.typography.labelLarge)
                    }

                    if (addToPlaylist) {
                        if (existingPlaylists.isNotEmpty()) {
                            existingPlaylists.forEach { playlist ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
                                    RadioButton(selected = selectedPlaylist == playlist && !createNew,
                                        onClick = { selectedPlaylist = playlist; createNew = false })
                                    Text(playlist.name, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
                            RadioButton(selected = createNew, onClick = { createNew = true; selectedPlaylist = null })
                            Text("Nieuwe playlist", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (createNew) {
                            OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it },
                                label = { Text("Playlist naam") }, placeholder = { Text("bijv. Rock, Chill, Favoriet...") },
                                singleLine = true, modifier = Modifier.fillMaxWidth().padding(start = 56.dp))
                        }
                    }

                    HorizontalDivider()

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = setAsMainRingtone, onCheckedChange = { setAsMainRingtone = it })
                        Column {
                            Text("Direct instellen als hoofdringtone", style = MaterialTheme.typography.bodyMedium)
                            Text("Wordt meteen de actieve beltoon", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (isSaving && saveProgress >= 0f) {
                        HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Opslaan...", style = MaterialTheme.typography.labelMedium)
                            LinearProgressIndicator(progress = { saveProgress }, modifier = Modifier.fillMaxWidth())
                            val pct = (saveProgress * 100).toInt()
                            val eta = if (saveProgress > 0.05f) {
                                val elapsed = System.currentTimeMillis() - saveStartTime
                                val remaining = (elapsed / saveProgress * (1f - saveProgress)).toLong()
                                if (remaining > 1000) " — ~${remaining / 1000}s" else " — <1s"
                            } else ""
                            Text("$pct%$eta", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                val canSave = ringtoneName.isNotBlank() &&
                    (!addToPlaylist || (if (createNew) newPlaylistName.isNotBlank() else selectedPlaylist != null))
                Button(
                    onClick = {
                        isSaving = true
                        saveProgress = 0f
                        saveStartTime = System.currentTimeMillis()
                        scope.launch {
                            try {
                                val data = waveformData ?: return@launch
                                val name = ringtoneName.trim()
                                val pName = when {
                                    !addToPlaylist -> "Geen"
                                    createNew -> newPlaylistName.trim()
                                    else -> selectedPlaylist!!.name
                                }
                                val safeName = name.replace(Regex("[^a-zA-Z0-9_\\- ]"), "_")

                                player.stop()
                                isPlaying = false

                                val trimDir = ringtoneManager.storage.getRingtoneDir()

                                if (hasChanges) {
                                    // Er zijn niet-toegepaste wijzigingen → apply + save
                                    val sMs = (startFraction * data.durationMs).toLong()
                                    val eMs = (endFraction * data.durationMs).toLong()
                                    val hasFade = fadeInEnabled || fadeOutEnabled
                                    val ext = if (hasFade || workingFile.extension.lowercase() == "m4a") "m4a" else "mp3"
                                    val finalFile = File(trimDir, "$safeName.$ext")

                                    if (hasFade) {
                                        AudioTrimmer.trimWithFade(workingFile, finalFile, sMs, eMs,
                                            if (fadeInEnabled) fadeInMs else 0,
                                            if (fadeOutEnabled) fadeOutMs else 0
                                        ) { p -> saveProgress = p }
                                    } else {
                                        AudioTrimmer.trim(workingFile, finalFile, sMs, eMs) { p -> saveProgress = p }
                                    }

                                    if (ext == "mp3") Mp3Marker.injectTrimmedMarker(finalFile, name, trackArtist)
                                    // Album art overnemen van origineel bestand
                                    val artPath = extractAlbumArt(context, audioFile, finalFile)
                                    // M4A metadata embedden (titel, artiest, cover, marker)
                                    if (ext == "m4a") {
                                        val artBytes = artPath?.let { File(it).takeIf { f -> f.exists() }?.readBytes() }
                                        M4aMetadata.write(finalFile, name, trackArtist, artBytes, "RandomRingtone trimmed")
                                    }
                                    val savedTrackId = saveToDB(db, deezerTrackId, name, trackArtist, previewUrl, finalFile, pName,
                                        addToPlaylist, createNew, selectedPlaylist, artPath)
                                    handlePostSave(setAsMainRingtone, ringtoneManager, snackbarHostState,
                                        savedTrackId, name, trackArtist, previewUrl, finalFile, pName, addToPlaylist)
                                } else {
                                    // Alles is al toegepast → kopieer werkbestand
                                    val ext = workingFile.extension.lowercase().ifBlank { "mp3" }
                                    val finalFile = File(trimDir, "$safeName.$ext")
                                    workingFile.copyTo(finalFile, overwrite = true)

                                    if (ext == "mp3") Mp3Marker.injectTrimmedMarker(finalFile, name, trackArtist)
                                    saveProgress = 1f
                                    val artPath = extractAlbumArt(context, audioFile, finalFile)
                                    if (ext == "m4a") {
                                        val artBytes = artPath?.let { File(it).takeIf { f -> f.exists() }?.readBytes() }
                                        M4aMetadata.write(finalFile, name, trackArtist, artBytes, "RandomRingtone trimmed")
                                    }
                                    val savedTrackId = saveToDB(db, deezerTrackId, name, trackArtist, previewUrl, finalFile, pName,
                                        addToPlaylist, createNew, selectedPlaylist, artPath)
                                    handlePostSave(setAsMainRingtone, ringtoneManager, snackbarHostState,
                                        savedTrackId, name, trackArtist, previewUrl, finalFile, pName, addToPlaylist)
                                }

                                existingPlaylists = db.playlistDao().getAll()
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Fout: ${e.message}")
                            }
                            isSaving = false
                            saveProgress = -1f
                            showSaveDialog = false
                        }
                    },
                    enabled = canSave && !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Opslaan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }, enabled = !isSaving) { Text("Annuleren") }
            }
        )
    }
}

// --- Save helpers ---

private suspend fun saveToDB(
    db: RingtoneDatabase, deezerTrackId: Long, name: String, artist: String,
    previewUrl: String, file: File, playlistName: String,
    addToPlaylist: Boolean, createNew: Boolean, selectedPlaylist: Playlist?,
    albumArtPath: String? = null
): Long {
    // Bij trim: origineel behouden, nieuw record aanmaken met unieke ID
    val existing = db.savedTrackDao().getById(deezerTrackId)
    val isDifferentFile = existing != null && existing.localPath != null &&
        existing.localPath != file.absolutePath
    val trackId = if (isDifferentFile) {
        // Genereer unieke ID voor het getrimde bestand, behoud origineel
        file.absolutePath.hashCode().toLong().let { if (it < 0) -it else it }
    } else if (existing == null) {
        // Nieuw bestand zonder bestaand record → gebruik hash als ID
        file.absolutePath.hashCode().toLong().let { if (it < 0) -it else it }
    } else {
        deezerTrackId
    }
    db.savedTrackDao().insert(SavedTrack(
        deezerTrackId = trackId, title = name, artist = artist,
        previewUrl = previewUrl, localPath = file.absolutePath, playlistName = playlistName,
        markerType = "trimmed",  // Editor slaat altijd getrimde bestanden op
        id3Title = name, id3Artist = artist,
        albumArtPath = albumArtPath
    ))
    if (addToPlaylist) {
        val playlistId = if (createNew) db.playlistDao().insert(Playlist(name = playlistName))
        else selectedPlaylist!!.id
        val sortOrder = db.playlistTrackDao().getNextSortOrder(playlistId)
        db.playlistTrackDao().insert(PlaylistTrack(playlistId, trackId, sortOrder))
    }
    return trackId
}

/**
 * Extract album art uit het bronbestand en sla op in cache.
 * Probeert eerst het origineel, dan het getrimde bestand.
 */
private fun extractAlbumArt(context: android.content.Context, originalFile: File, trimmedFile: File): String? {
    val retriever = android.media.MediaMetadataRetriever()
    return try {
        // Probeer origineel bestand (heeft waarschijnlijk embedded art)
        retriever.setDataSource(originalFile.absolutePath)
        val artBytes = retriever.embeddedPicture
        if (artBytes != null) {
            val artDir = File(context.cacheDir, "album_art").apply { mkdirs() }
            val artFile = File(artDir, "${trimmedFile.nameWithoutExtension.hashCode()}.jpg")
            artFile.outputStream().use { it.write(artBytes) }
            RemoteLogger.d("EditorScreen", "Album art geëxtraheerd", mapOf(
                "source" to originalFile.name, "size" to "${artBytes.size / 1024}KB"
            ))
            artFile.absolutePath
        } else null
    } catch (e: Exception) {
        RemoteLogger.w("EditorScreen", "Album art extractie mislukt", mapOf("error" to (e.message ?: "")))
        null
    } finally {
        try { retriever.release() } catch (_: Exception) {}
    }
}

private suspend fun handlePostSave(
    setAsMainRingtone: Boolean, ringtoneManager: AppRingtoneManager,
    snackbarHostState: SnackbarHostState, deezerTrackId: Long, name: String,
    artist: String, previewUrl: String, file: File, playlistName: String, addToPlaylist: Boolean
) {
    if (setAsMainRingtone) {
        val deezerTrack = DeezerTrack(id = deezerTrackId, title = name, titleShort = name,
            artist = DeezerArtist(name = artist), preview = previewUrl)
        val success = ringtoneManager.setAsRingtone(deezerTrack, file)
        val target = if (addToPlaylist) "in '$playlistName' + " else ""
        snackbarHostState.showSnackbar(
            if (success) "Opgeslagen ${target}ingesteld als hoofdringtone"
            else "Opgeslagen — ringtone instellen mislukt (permissie?)"
        )
    } else {
        val msg = if (addToPlaylist) "Opgeslagen in '$playlistName'" else "Opgeslagen"
        snackbarHostState.showSnackbar(msg)
    }
}

// --- Waveform met zoom + fade overlay + playback marker ---

@Composable
private fun WaveformView(
    amplitudes: List<Float>,
    startFraction: Float,
    endFraction: Float,
    viewStart: Float,
    viewEnd: Float,
    fadeInFraction: Float,
    fadeOutFraction: Float,
    playbackFraction: Float = -1f,
    onStartChanged: (Float) -> Unit,
    onEndChanged: (Float) -> Unit,
    onTapped: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val selectionColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val handleColor = MaterialTheme.colorScheme.primary
    val fadeColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
    val viewRange = viewEnd - viewStart

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(8.dp)) {
        Canvas(
            modifier = Modifier.fillMaxSize()
                .pointerInput(amplitudes.size, viewStart, viewEnd) {
                    detectTapGestures { offset ->
                        val relFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        val absFraction = viewStart + relFraction * viewRange
                        onTapped?.invoke(absFraction)
                    }
                }
                .pointerInput(amplitudes.size, viewStart, viewEnd) {
                    detectHorizontalDragGestures { change, _ ->
                        val relFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        val absFraction = viewStart + relFraction * viewRange
                        val distToStart = kotlin.math.abs(absFraction - startFraction)
                        val distToEnd = kotlin.math.abs(absFraction - endFraction)
                        if (distToStart < distToEnd) onStartChanged(absFraction) else onEndChanged(absFraction)
                    }
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val center = canvasHeight / 2
            val startIdx = (viewStart * amplitudes.size).toInt().coerceIn(0, amplitudes.size)
            val endIdx = (viewEnd * amplitudes.size).toInt().coerceIn(startIdx, amplitudes.size)
            val visibleAmps = if (startIdx < endIdx) amplitudes.subList(startIdx, endIdx) else emptyList()
            val barWidth = canvasWidth / visibleAmps.size.coerceAtLeast(1)

            visibleAmps.forEachIndexed { index, amplitude ->
                val x = index * barWidth
                val absFrac = viewStart + index.toFloat() / visibleAmps.size * viewRange
                val inSelection = absFrac in startFraction..endFraction
                val barHeight = amplitude * canvasHeight * 0.8f
                val color = if (inSelection) primaryColor else inactiveColor
                drawLine(color = color, start = Offset(x + barWidth / 2, center - barHeight / 2),
                    end = Offset(x + barWidth / 2, center + barHeight / 2), strokeWidth = barWidth * 0.7f)
            }

            val relStart = ((startFraction - viewStart) / viewRange).coerceIn(0f, 1f)
            val relEnd = ((endFraction - viewStart) / viewRange).coerceIn(0f, 1f)
            val startX = relStart * canvasWidth
            val endX = relEnd * canvasWidth
            drawRect(color = selectionColor, topLeft = Offset(startX, 0f), size = Size(endX - startX, canvasHeight))

            // Fade overlays
            if (fadeInFraction > 0f) {
                val fadeEndRel = ((startFraction + fadeInFraction - viewStart) / viewRange).coerceIn(relStart, relEnd)
                val fadeEndX = fadeEndRel * canvasWidth
                if (fadeEndX > startX) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(startX, 0f); lineTo(startX, canvasHeight); lineTo(fadeEndX, 0f); close()
                    }
                    drawPath(path, fadeColor)
                }
            }
            if (fadeOutFraction > 0f) {
                val fadeStartRel = ((endFraction - fadeOutFraction - viewStart) / viewRange).coerceIn(relStart, relEnd)
                val fadeStartX = fadeStartRel * canvasWidth
                if (endX > fadeStartX) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(endX, 0f); lineTo(endX, canvasHeight); lineTo(fadeStartX, 0f); close()
                    }
                    drawPath(path, fadeColor)
                }
            }

            // Handles
            drawLine(color = handleColor, start = Offset(startX, 0f), end = Offset(startX, canvasHeight), strokeWidth = 4f)
            drawLine(color = handleColor, start = Offset(endX, 0f), end = Offset(endX, canvasHeight), strokeWidth = 4f)
            drawCircle(color = handleColor, radius = 8f, center = Offset(startX, center))
            drawCircle(color = handleColor, radius = 8f, center = Offset(endX, center))

            // Playback marker
            if (playbackFraction in viewStart..viewEnd) {
                val playX = ((playbackFraction - viewStart) / viewRange).coerceIn(0f, 1f) * canvasWidth
                drawLine(color = androidx.compose.ui.graphics.Color.White,
                    start = Offset(playX, 0f), end = Offset(playX, canvasHeight), strokeWidth = 3f)
            }
        }
    }
}

// --- Tijd invoerveld ---

@Composable
private fun TimeInputField(
    label: String, timeMs: Long, maxMs: Long,
    onTimeChanged: (Long) -> Unit, modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(formatTimeDetailed(timeMs)) }
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(timeMs) { if (!isFocused) text = formatTimeDetailed(timeMs) }

    OutlinedTextField(
        value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            parseTimeToMs(text)?.let { ms -> onTimeChanged(ms.coerceIn(0, maxMs)) }
            focusManager.clearFocus()
        }),
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = modifier.width(110.dp).onFocusChanged { focusState ->
            if (isFocused && !focusState.isFocused) {
                parseTimeToMs(text)?.let { ms -> onTimeChanged(ms.coerceIn(0, maxMs)) }
                text = formatTimeDetailed(timeMs)
            }
            isFocused = focusState.isFocused
        }
    )
}

// --- Helpers ---

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000; val min = totalSec / 60; val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

private fun formatTimeDetailed(ms: Long): String {
    val totalSec = ms / 1000; val min = totalSec / 60; val sec = totalSec % 60; val tenths = (ms % 1000) / 100
    return if (tenths > 0) "%d:%02d.%d".format(min, sec, tenths) else "%d:%02d".format(min, sec)
}

private fun parseTimeToMs(text: String): Long? {
    val trimmed = text.trim(); if (trimmed.isEmpty()) return null
    val parts = trimmed.split(":")
    return try {
        when (parts.size) {
            1 -> (parts[0].toDouble() * 1000).toLong()
            2 -> (parts[0].toLong() * 60_000 + parts[1].toDouble() * 1000).toLong()
            else -> null
        }
    } catch (_: NumberFormatException) { null }
}
