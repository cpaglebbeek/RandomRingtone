package nl.icthorse.randomringtone.ui.screens

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.icthorse.randomringtone.AppBusyState
import nl.icthorse.randomringtone.data.*
import java.io.File

/**
 * v2.7.0 — het enige bronscherm: zoeken op de eigen server YourTube en de audio downloaden.
 *
 * Zoekveld → trefferlijst (titel, kanaal, duur, miniatuur) → tik op downloaden → M4A in de downloadmap →
 * "Openen in editor" of "Opslaan in bibliotheek". Een geplakte YouTube-link downloadt die video direct.
 */
@Composable
fun YourTubeScreen(
    ringtoneManager: AppRingtoneManager,
    db: RingtoneDatabase,
    snackbarHostState: SnackbarHostState,
    onOpenEditor: ((String, File) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val licenseManager = remember { LicenseManager(context) }

    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<YourTube.Hit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchedFor by remember { mutableStateOf<String?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }

    var isDownloading by remember { mutableStateOf(false) }
    var downloadPhase by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    var lastFile by remember { mutableStateOf<File?>(null) }
    var lastTitle by remember { mutableStateOf("") }
    var lastArtist by remember { mutableStateOf("") }
    var showActionsDialog by remember { mutableStateOf(false) }
    var pendingOverwrite by remember { mutableStateOf<YourTube.Hit?>(null) }
    var pendingOverwriteFile by remember { mutableStateOf<File?>(null) }

    fun download(hit: YourTube.Hit, force: Boolean) {
        if (isDownloading) return
        isDownloading = true
        AppBusyState.isBusy = true
        downloadPhase = "Verbinden met YourTube..."
        downloadProgress = 0f
        scope.launch {
            val result = try {
                YourTube.downloadAudio(licenseManager, hit.videoId, ringtoneManager.storage.getDownloadDir(),
                    displayTitle = hit.title.ifBlank { null }, displayArtist = hit.channel.ifBlank { null },
                    forceOverwrite = force) { phase, progress ->
                    downloadPhase = phase
                    downloadProgress = progress
                }
            } finally {
                isDownloading = false
                AppBusyState.isBusy = false
            }
            val file = result.file
            when {
                result.fileExists && file != null -> { pendingOverwrite = hit.copy(title = result.title ?: hit.title); pendingOverwriteFile = file }
                result.success && file != null -> {
                    result.cover?.let { saveArt(context, file, it) }
                    lastFile = file
                    lastTitle = result.title ?: file.nameWithoutExtension
                    lastArtist = result.artist.orEmpty()
                    showActionsDialog = true
                }
                else -> snackbarHostState.showSnackbar(result.error ?: "Download mislukt")
            }
        }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || searching || isDownloading) return
        keyboard?.hide()
        // Geplakte YouTube-link: direct downloaden
        YourTube.videoId(q)?.let { id ->
            RemoteLogger.trigger("YourTube", "Link geplakt → direct downloaden", mapOf("videoId" to id))
            download(YourTube.Hit(id, "", "", "", 0, ""), force = false)
            return
        }
        searching = true
        searchError = null
        scope.launch {
            try {
                hits = YourTube.search(licenseManager, q)
                searchedFor = q
            } catch (e: Exception) {
                hits = emptyList()
                searchedFor = null
                searchError = e.message ?: "Zoeken mislukt"
            } finally {
                searching = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text("Zoek op YourTube") },
                placeholder = { Text("Artiest, titel of YouTube-link") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Wissen")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() })
            )
            Button(
                onClick = { search() },
                enabled = query.isNotBlank() && !searching && !isDownloading,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Zoeken")
            }
            Spacer(Modifier.height(8.dp))

            when {
                searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                searchError != null -> Hint(searchError!!, isError = true)
                searchedFor != null && hits.isEmpty() -> Hint("Niets gevonden voor \"$searchedFor\".")
                hits.isEmpty() -> Hint("Zoek een nummer op artiest en titel, of plak een YouTube-link.\n" +
                    "Tik bij een treffer op de downloadknop; de audio komt via je eigen server YourTube.")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = if (isDownloading) 120.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(hits, key = { it.videoId }) { hit ->
                        HitRow(hit, enabled = !isDownloading) { download(hit, force = false) }
                    }
                }
            }
        }

        if (isDownloading) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(downloadPhase, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    }
                    LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    // === Bestand bestaat al ===
    pendingOverwrite?.let { hit ->
        val close = { pendingOverwrite = null; pendingOverwriteFile = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text("Bestand bestaat al") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pendingOverwriteFile?.name ?: "", style = MaterialTheme.typography.bodyMedium)
                    Text("Dit nummer is al eerder gedownload. Opnieuw downloaden en overschrijven?",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { close(); download(hit, force = true) }) { Text("Overschrijven") } },
            dismissButton = { TextButton(onClick = close) { Text("Annuleren") } }
        )
    }

    // === Na de download ===
    val file = lastFile
    if (showActionsDialog && file != null) {
        suspend fun register(trackId: Long) {
            db.savedTrackDao().insert(SavedTrack(
                deezerTrackId = trackId,
                title = lastTitle,
                artist = lastArtist.ifBlank { "YouTube" },
                previewUrl = "",
                localPath = file.absolutePath,
                playlistName = "_youtube",
                markerType = "youtube",
                albumArtPath = artPath(context, file)
            ))
        }
        AlertDialog(
            onDismissRequest = { showActionsDialog = false },
            title = { Text("Nummer gedownload") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(lastTitle, style = MaterialTheme.typography.bodyMedium)
                    if (lastArtist.isNotBlank()) Text(lastArtist, style = MaterialTheme.typography.bodySmall)
                    Text("Grootte: ${file.length() / 1024} KB", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Wat wil je doen?", style = MaterialTheme.typography.labelLarge)
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onOpenEditor != null) {
                        Button(onClick = {
                            showActionsDialog = false
                            // Vooraf in de database zodat de editor het nummer met hoes vindt
                            scope.launch { register(TrackIdResolver.canonicalTrackIdForName(file.name)) }
                            onOpenEditor(lastTitle, file)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Openen in editor")
                        }
                    }
                    Button(onClick = {
                        showActionsDialog = false
                        scope.launch {
                            register(file.name.hashCode().toLong())
                            snackbarHostState.showSnackbar("Opgeslagen in bibliotheek")
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Opslaan in bibliotheek")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showActionsDialog = false }) { Text("Sluiten") } }
        )
    }
}

@Composable
private fun Hint(text: String, isError: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HitRow(hit: YourTube.Hit, enabled: Boolean, onDownload: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onDownload)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)) {
                Thumb(hit.thumb)
                if (hit.duration.isNotBlank()) {
                    Text(hit.duration, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(hit.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(hit.channel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDownload, enabled = enabled) {
                Icon(Icons.Default.Download, contentDescription = "Downloaden")
            }
        }
    }
}

// --- Miniaturen (klein geheugen-cache; geen aparte afbeeldingsbibliotheek) ---

private val thumbCache = LruCache<String, ImageBitmap>(60)

@Composable
private fun Thumb(url: String) {
    var bmp by remember(url) { mutableStateOf(thumbCache.get(url)) }
    LaunchedEffect(url) {
        if (bmp == null && url.isNotBlank()) {
            bmp = withContext(Dispatchers.IO) {
                YourTube.fetchBytes(url)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }
            }?.also { thumbCache.put(url, it) }
        }
    }
    bmp?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

// --- Hoes naast het bestand (cache), zodat bibliotheek en editor hem direct tonen ---

private fun artFile(context: android.content.Context, audioFile: File) =
    File(File(context.cacheDir, "album_art").apply { mkdirs() }, "${audioFile.nameWithoutExtension.hashCode()}.jpg")

private fun saveArt(context: android.content.Context, audioFile: File, jpeg: ByteArray) {
    try { artFile(context, audioFile).writeBytes(jpeg) } catch (_: Exception) { }
}

private fun artPath(context: android.content.Context, audioFile: File): String? =
    artFile(context, audioFile).takeIf { it.exists() && it.length() > 1000 }?.absolutePath
