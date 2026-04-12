package nl.icthorse.randomringtone.ui.screens

import nl.icthorse.randomringtone.AppBusyState
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.audio.AudioPlayer
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
    val isScanned: Boolean,
    val albumArtPath: String? = null,
    val isTrimmed: Boolean = false
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

    // Audio player voor afspelen vanuit bibliotheek
    val player = remember { AudioPlayer() }
    var playingTrackId by remember { mutableStateOf<Long?>(null) }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Single point of truth: DB-gedreven, gesplitst op marker
    var downloads by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var ringtones by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var youtubeClips by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // Zoeken en sorteren
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var sortAsc by remember { mutableStateOf(true) } // true = A-Z, false = Z-A

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

    // === SINGLE POINT OF TRUTH: refresh uit DB, split op cached markerType ===
    fun refresh() {
        scope.launch {
            // Stap 1: Cache markers voor tracks die nog geen markerType hebben (eenmalig disk I/O)
            val uncached = db.savedTrackDao().getTracksWithoutMarker()
            for (track in uncached) {
                val file = File(track.localPath!!)
                val marker = if (!file.exists()) "track"
                else when (file.extension.lowercase()) {
                    "mp3" -> when {
                        Mp3Marker.isYouTube(file) -> "youtube"
                        Mp3Marker.isTrimmed(file) -> "trimmed"
                        else -> "track"
                    }
                    "m4a", "aac" -> "trimmed"
                    else -> "track"
                }
                db.savedTrackDao().updateMarkerType(track.deezerTrackId, marker)
            }

            // Stap 2: Enrich ID3 alleen voor tracks zonder ID3 data (niet elke keer alles)
            if (uncached.isNotEmpty()) Mp3TagReader.enrichAll(context, db)

            // Stap 2b: Dedup — verwijder DB entries met duplicate bestandsnamen (houdt oudste)
            val allTrx = db.savedTrackDao().getAll()
            val seenNames = mutableSetOf<String>()
            for (track in allTrx) {
                val name = track.localPath?.let { File(it).name } ?: continue
                if (name in seenNames) {
                    db.savedTrackDao().delete(track)
                    db.playlistTrackDao().removeByTrackId(track.deezerTrackId)
                } else {
                    seenNames.add(name)
                }
            }

            // Stap 2c: Orphan cleanup — verwijder playlist_tracks die naar niet-bestaande saved_tracks wijzen
            val allSavedIds = db.savedTrackDao().getAll().map { it.deezerTrackId }.toSet()
            val allPtIds = db.playlistTrackDao().getAllTrackIds()
            for (orphanId in allPtIds) {
                if (orphanId !in allSavedIds) db.playlistTrackDao().removeByTrackId(orphanId)
            }

            // Stap 3: Lees alle tracks uit DB — geen disk I/O meer
            val allItems = db.savedTrackDao().getAll()
                .filter { track ->
                    val path = track.localPath
                    if (path == null || path.isBlank()) return@filter false
                    val name = File(path).name.lowercase()
                    path.contains("RandomRingtone", ignoreCase = true) ||
                        name.startsWith("spotify_mp3_") ||
                        name.startsWith("youtube_mp3_") ||
                        name.startsWith("ringtone_") ||
                        name.startsWith("download_")
                }
                .distinctBy { it.localPath }
                .map { track ->
                    val file = File(track.localPath!!)
                    LibraryItem(
                        file = file,
                        trackId = track.deezerTrackId,
                        title = track.id3Title?.takeIf { it.isNotBlank() } ?: track.title.ifBlank { file.nameWithoutExtension },
                        artist = track.id3Artist?.takeIf { it.isNotBlank() } ?: track.artist,
                        sizeFormatted = if (file.exists()) formatFileSize(file.length()) else "niet op schijf",
                        isScanned = true,
                        albumArtPath = track.albumArtPath,
                        isTrimmed = track.markerType == "trimmed"
                    )
                }

            // Split op cached markerType — geen disk I/O
            val allTracks = db.savedTrackDao().getAll().associateBy { it.deezerTrackId }
            youtubeClips = allItems.filter { allTracks[it.trackId]?.markerType == "youtube" }
            ringtones = allItems.filter { it.isTrimmed && allTracks[it.trackId]?.markerType != "youtube" }
            downloads = allItems.filter { !it.isTrimmed && allTracks[it.trackId]?.markerType != "youtube" }
        }
    }

    // === SCAN ===
    fun doScan() {
        isScanning = true
        AppBusyState.isBusy = true
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
                    // Bouw dedup-set op basis van bestandsnaam (voorkomt dubbelen door pad-variaties)
                    val knownFileNames = db.savedTrackDao().getAll()
                        .mapNotNull { it.localPath?.let { lp -> File(lp).name } }
                        .toMutableSet()

                    var added = 0
                    for (sf in scanned) {
                        if (sf.localPath.isNotBlank()) {
                            val fileName = File(sf.localPath).name
                            // Dedup op bestandsnaam (vangt /data/user/0/ vs /data/data/ symlink verschil)
                            if (fileName in knownFileNames) continue
                            val byPath = db.savedTrackDao().getByLocalPath(sf.localPath)
                            if (byPath != null) continue
                            knownFileNames.add(fileName)
                        }

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
                    val markerCount = scanned.count { it.source == "marker" }
                    val msg = buildString {
                        append("$added nieuw van ${scanned.size} bestanden")
                        if (markerCount > 0) append(" ($markerCount via marker)")
                        if (msCount > 0) append(" ($msCount via MediaStore)")
                    }
                    snackbarHostState.showSnackbar(msg)
                }
                // Orphan cleanup: verwijder DB tracks waarvan het bestand niet meer bestaat
                var removed = 0
                for (track in db.savedTrackDao().getAll()) {
                    val lp = track.localPath
                    if (lp != null && lp.isNotBlank() && !File(lp).exists()) {
                        db.savedTrackDao().delete(track)
                        db.playlistTrackDao().removeByTrackId(track.deezerTrackId)
                        removed++
                    }
                }
                if (removed > 0) snackbarHostState.showSnackbar("$removed verwijderde bestanden opgeruimd uit bibliotheek")

                refresh()
            } catch (e: Exception) {
                scanDiagnostic = "Scan mislukt:\n${e.message}\n\n${e.stackTraceToString().take(500)}"
            } finally {
                isScanning = false
                AppBusyState.isBusy = false
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
                                // Verwijder ALLE DB entries voor dit bestand (vangt dubbelen op)
                                val fileName = item.file.name
                                db.savedTrackDao().getAll()
                                    .filter { it.localPath?.let { lp -> File(lp).name } == fileName }
                                    .forEach { track ->
                                        db.savedTrackDao().delete(track)
                                        db.playlistTrackDao().removeByTrackId(track.deezerTrackId)
                                    }
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
                                // Verwijder ALLE DB entries voor dit bestand (vangt dubbelen op)
                                val fileName = item.file.name
                                db.savedTrackDao().getAll()
                                    .filter { it.localPath?.let { lp -> File(lp).name } == fileName }
                                    .forEach { track ->
                                        db.savedTrackDao().delete(track)
                                        db.playlistTrackDao().removeByTrackId(track.deezerTrackId)
                                    }
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
            modifier = Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Bibliotheek",
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

        // Zoekbalk + sorteerknoppen
        if (showSearch) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Zoeken op titel of artiest...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    Row {
                        IconButton(onClick = { sortAsc = !sortAsc }) {
                            Icon(
                                if (sortAsc) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                contentDescription = if (sortAsc) "A-Z" else "Z-A"
                            )
                        }
                        IconButton(onClick = { searchQuery = ""; showSearch = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Sluiten")
                        }
                    }
                }
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { showSearch = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Zoeken", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { sortAsc = !sortAsc }) {
                    Icon(
                        if (sortAsc) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        contentDescription = if (sortAsc) "A-Z" else "Z-A",
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    if (sortAsc) "A-Z" else "Z-A",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Tabs: Downloads / Tones / YouTube
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                text = { Text("Downloads (${downloads.size})") },
                icon = { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp)) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                text = { Text("Tones (${ringtones.size})") },
                icon = { Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp)) })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                text = { Text("YouTube (${youtubeClips.size})") },
                icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp)) })
        }

        val rawItems = when (selectedTab) {
            0 -> downloads
            1 -> ringtones
            2 -> youtubeClips
            else -> downloads
        }
        val items = remember(rawItems, searchQuery, sortAsc) {
            var list = rawItems
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase()
                list = list.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
            }
            if (sortAsc) list.sortedBy { it.title.lowercase() }
            else list.sortedByDescending { it.title.lowercase() }
        }
        val emptyIcon = when (selectedTab) {
            0 -> Icons.Default.Download
            1 -> Icons.Default.MusicNote
            2 -> Icons.Default.VideoLibrary
            else -> Icons.Default.Download
        }
        val emptyTitle = when (selectedTab) {
            0 -> "Geen downloads"
            1 -> "Geen tones"
            2 -> "Geen YouTube clips"
            else -> "Leeg"
        }
        val emptySubtitle = when (selectedTab) {
            0 -> "Download nummers via Spotify en druk op Scan"
            1 -> "Trim een download via de editor"
            2 -> "Download nummers via de YouTube tab"
            else -> ""
        }

        if (items.isEmpty()) {
            EmptyState(icon = emptyIcon, title = emptyTitle, subtitle = emptySubtitle)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items, key = { it.trackId }) { item ->
                    val isItemPlaying = playingTrackId == item.trackId
                    LibraryCard(
                        item = item,
                        actions = {
                            // Play/Stop knop
                            IconButton(onClick = {
                                if (isItemPlaying) {
                                    player.stop()
                                    playingTrackId = null
                                } else if (item.file.exists()) {
                                    player.stop()
                                    player.playSelection(item.file, 0, Long.MAX_VALUE) {
                                        playingTrackId = null
                                    }
                                    playingTrackId = item.trackId
                                }
                            }) {
                                Icon(
                                    if (isItemPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (isItemPlaying) "Stop" else "Afspelen",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (onOpenEditor != null) {
                                // Trim/Edit knop voor alle tabs
                                IconButton(onClick = {
                                    player.stop(); playingTrackId = null
                                    onOpenEditor.invoke(item.title, item.artist, item.file, item.trackId, "")
                                }) {
                                    Icon(Icons.Default.ContentCut, contentDescription = "Bewerken")
                                }
                            }
                            IconButton(onClick = {
                                player.stop(); playingTrackId = null
                                showDeleteDialog = item
                            }) {
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
            val albumBitmap = remember(item.albumArtPath) {
                item.albumArtPath?.let { path ->
                    try { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                    catch (_: Exception) { null }
                }
            }
            if (albumBitmap != null) {
                Image(
                    bitmap = albumBitmap,
                    contentDescription = "Album art",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
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
