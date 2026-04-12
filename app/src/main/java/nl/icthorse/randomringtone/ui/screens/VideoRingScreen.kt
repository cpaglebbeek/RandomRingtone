package nl.icthorse.randomringtone.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.icthorse.randomringtone.AppBusyState
import nl.icthorse.randomringtone.audio.VideoTrimmer
import nl.icthorse.randomringtone.data.*
import java.io.File

data class VideoItem(
    val id: Long,
    val title: String,
    val artist: String,
    val file: File,
    val durationMs: Long,
    val thumbnailPath: String?,
    val isActive: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoRingScreen(
    db: RingtoneDatabase,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val yt1sClient = remember { Yt1sClient() }
    var youtubeUrl by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadPhase by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    // Quality selection
    var selectedQuality by remember { mutableStateOf("720p") }
    val qualityOptions = listOf("360p", "720p", "1080p")
    var detectedVideoId by remember { mutableStateOf<String?>(null) }

    // Video library
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }

    // Trim dialog
    var showTrimDialog by remember { mutableStateOf<VideoItem?>(null) }
    var trimStartSec by remember { mutableFloatStateOf(0f) }
    var trimEndSec by remember { mutableFloatStateOf(20f) }
    var isTrimming by remember { mutableStateOf(false) }
    var trimProgress by remember { mutableFloatStateOf(0f) }

    // Delete dialog
    var showDeleteDialog by remember { mutableStateOf<VideoItem?>(null) }

    // Folder picker / indexing
    var isIndexing by remember { mutableStateOf(false) }
    var indexProgress by remember { mutableFloatStateOf(0f) }
    var indexStatus by remember { mutableStateOf("") }

    val videoDir = remember {
        File(context.filesDir, "videoring").apply { mkdirs() }
    }

    fun refresh() {
        scope.launch {
            videos = db.videoRingtoneDao().getAll().map { vr ->
                VideoItem(
                    id = vr.id,
                    title = vr.title,
                    artist = vr.artist,
                    file = File(vr.localPath),
                    durationMs = vr.durationMs,
                    thumbnailPath = vr.thumbnailPath,
                    isActive = vr.isActive
                )
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Persistent permission zodat we er later bij kunnen
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        isIndexing = true
        AppBusyState.isBusy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                val docTree = DocumentFile.fromTreeUri(context, uri) ?: return@withContext
                val mp4Files = docTree.listFiles().filter { doc ->
                    doc.isFile && doc.name?.lowercase()?.endsWith(".mp4") == true
                }
                if (mp4Files.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        indexStatus = ""
                        isIndexing = false
                        AppBusyState.isBusy = false
                        snackbarHostState.showSnackbar("Geen MP4-bestanden gevonden in deze map")
                    }
                    return@withContext
                }

                var imported = 0
                var skipped = 0
                val existingPaths = db.videoRingtoneDao().getAll().map { it.localPath }.toSet()

                mp4Files.forEachIndexed { idx, doc ->
                    val fileName = doc.name ?: "video_${System.currentTimeMillis()}.mp4"
                    val safeName = fileName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
                    val destFile = File(videoDir, safeName)

                    // Skip als al geïndexeerd
                    if (existingPaths.contains(destFile.absolutePath)) {
                        skipped++
                        indexProgress = (idx + 1).toFloat() / mp4Files.size
                        indexStatus = "Overslaan: $fileName (al geïmporteerd)"
                        return@forEachIndexed
                    }

                    indexProgress = (idx + 0.3f) / mp4Files.size
                    indexStatus = "Kopiëren: $fileName (${idx + 1}/${mp4Files.size})"

                    // Kopieer naar interne opslag
                    try {
                        context.contentResolver.openInputStream(doc.uri)?.use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output, bufferSize = 8192)
                            }
                        }
                    } catch (e: Exception) {
                        skipped++
                        indexProgress = (idx + 1).toFloat() / mp4Files.size
                        return@forEachIndexed
                    }

                    indexProgress = (idx + 0.7f) / mp4Files.size
                    indexStatus = "Indexeren: $fileName (${idx + 1}/${mp4Files.size})"

                    // Metadata + thumbnail
                    val retriever = MediaMetadataRetriever()
                    var durationMs = 0L
                    var title = destFile.nameWithoutExtension
                    var artist = "Lokaal"
                    try {
                        retriever.setDataSource(destFile.absolutePath)
                        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
                    } catch (_: Exception) {} finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }

                    // Thumbnail
                    var thumbPath: String? = null
                    try {
                        val bmp = VideoTrimmer.extractThumbnail(destFile)
                        if (bmp != null) {
                            val thumbFile = File(videoDir, "${destFile.nameWithoutExtension}_thumb.jpg")
                            thumbFile.outputStream().use {
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it)
                            }
                            thumbPath = thumbFile.absolutePath
                        }
                    } catch (_: Exception) {}

                    db.videoRingtoneDao().insert(VideoRingtone(
                        title = title,
                        artist = artist,
                        localPath = destFile.absolutePath,
                        thumbnailPath = thumbPath,
                        durationMs = durationMs
                    ))
                    imported++
                    indexProgress = (idx + 1).toFloat() / mp4Files.size
                }

                withContext(Dispatchers.Main) {
                    indexStatus = ""
                    isIndexing = false
                    AppBusyState.isBusy = false
                    refresh()
                    val msg = "$imported video('s) geïmporteerd" +
                        if (skipped > 0) ", $skipped overgeslagen" else ""
                    snackbarHostState.showSnackbar(msg)
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""youtube\.com/watch\?v=([a-zA-Z0-9_\-]{11})"""),
            Regex("""youtube\.com/shorts/([a-zA-Z0-9_\-]{11})"""),
            Regex("""youtu\.be/([a-zA-Z0-9_\-]{11})""")
        )
        for (p in patterns) {
            p.find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        // === HEADER ===
        Text("VideoRing", style = MaterialTheme.typography.headlineSmall)
        Text("YouTube downloaden of lokale MP4's importeren",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(12.dp))

        // === URL INPUT ===
        OutlinedTextField(
            value = youtubeUrl,
            onValueChange = { youtubeUrl = it },
            label = { Text("YouTube URL") },
            placeholder = { Text("https://youtube.com/watch?v=...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (youtubeUrl.isNotBlank()) {
                    IconButton(onClick = { youtubeUrl = "" }) {
                        Icon(Icons.Default.Clear, "Wissen")
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // === KWALITEIT + DOWNLOAD ===
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Kwaliteit dropdown
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(selectedQuality)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    qualityOptions.forEach { q ->
                        DropdownMenuItem(
                            text = { Text(q) },
                            onClick = { selectedQuality = q; expanded = false }
                        )
                    }
                }
            }

            // Download button
            Button(
                onClick = {
                    val vidId = extractVideoId(youtubeUrl)
                    if (vidId == null) {
                        scope.launch { snackbarHostState.showSnackbar("Geen geldige YouTube URL") }
                        return@Button
                    }
                    detectedVideoId = vidId
                    isDownloading = true
                    AppBusyState.isBusy = true
                    scope.launch {
                        val result = yt1sClient.downloadVideo(
                            videoId = vidId,
                            quality = selectedQuality,
                            destDir = videoDir,
                            onProgress = { phase, progress ->
                                downloadPhase = phase
                                downloadProgress = progress
                            }
                        )
                        isDownloading = false
                        AppBusyState.isBusy = false
                        downloadProgress = 0f

                        if (result.success && result.file != null) {
                            // Thumbnail genereren
                            var thumbPath: String? = null
                            withContext(Dispatchers.IO) {
                                val bmp = VideoTrimmer.extractThumbnail(result.file)
                                if (bmp != null) {
                                    val thumbFile = File(videoDir, "${result.file.nameWithoutExtension}_thumb.jpg")
                                    thumbFile.outputStream().use {
                                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it)
                                    }
                                    thumbPath = thumbFile.absolutePath
                                }
                            }
                            db.videoRingtoneDao().insert(VideoRingtone(
                                title = result.title ?: result.file.nameWithoutExtension,
                                artist = "YouTube",
                                localPath = result.file.absolutePath,
                                thumbnailPath = thumbPath,
                                durationMs = result.durationMs,
                                videoId = vidId
                            ))
                            refresh()
                            youtubeUrl = ""
                            snackbarHostState.showSnackbar("Video opgeslagen: ${result.title ?: result.file.name}")
                        } else {
                            snackbarHostState.showSnackbar(result.error ?: "Download mislukt")
                        }
                    }
                },
                enabled = youtubeUrl.isNotBlank() && !isDownloading,
                modifier = Modifier.weight(1f)
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(Icons.Default.CloudDownload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Download MP4")
            }
        }

        // === DOWNLOAD PROGRESS ===
        if (isDownloading && downloadProgress > 0.1f) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(downloadPhase, style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(8.dp))

        // === MAP KIEZEN (LOKALE MP4's) ===
        OutlinedButton(
            onClick = { folderPickerLauncher.launch(null) },
            enabled = !isIndexing && !isDownloading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Importeer MP4's uit map")
        }

        // === INDEXERING PROGRESS ===
        if (isIndexing) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(indexStatus, style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(progress = { indexProgress }, modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(16.dp))

        // === VIDEO BIBLIOTHEEK ===
        Text("Opgeslagen video's", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        if (videos.isEmpty()) {
            Text("Nog geen video's gedownload",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(videos, key = { it.id }) { video ->
                    VideoCard(
                        video = video,
                        onActivate = {
                            scope.launch {
                                db.videoRingtoneDao().deactivateAll()
                                db.videoRingtoneDao().activate(video.id)
                                refresh()
                                snackbarHostState.showSnackbar("${video.title} ingesteld als video-ringtone")
                            }
                        },
                        onDeactivate = {
                            scope.launch {
                                db.videoRingtoneDao().deactivateAll()
                                refresh()
                                snackbarHostState.showSnackbar("Video-ringtone uitgeschakeld")
                            }
                        },
                        onTrim = {
                            trimStartSec = 0f
                            trimEndSec = (video.durationMs / 1000f).coerceAtMost(20f)
                            showTrimDialog = video
                        },
                        onDelete = { showDeleteDialog = video }
                    )
                }
            }
        }
    }



    // === TRIM DIALOOG ===
    showTrimDialog?.let { video ->
        val maxSec = video.durationMs / 1000f
        AlertDialog(
            onDismissRequest = { if (!isTrimming) showTrimDialog = null },
            title = { Text("Video trimmen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(video.title, style = MaterialTheme.typography.bodyMedium)
                    Text("Duur: %.1fs".format(maxSec), style = MaterialTheme.typography.bodySmall)

                    if (isTrimming) {
                        LinearProgressIndicator(progress = { trimProgress }, modifier = Modifier.fillMaxWidth())
                        Text("Trimmen... ${(trimProgress * 100).toInt()}%")
                    } else {
                        Text("Start (seconden)")
                        Slider(value = trimStartSec, onValueChange = {
                            trimStartSec = it.coerceAtMost(trimEndSec - 1f)
                        }, valueRange = 0f..maxSec)
                        Text("%.1fs".format(trimStartSec))

                        Text("Eind (seconden)")
                        Slider(value = trimEndSec, onValueChange = {
                            trimEndSec = it.coerceAtLeast(trimStartSec + 1f)
                        }, valueRange = 0f..maxSec)
                        Text("%.1fs — Selectie: %.1fs".format(trimEndSec, trimEndSec - trimStartSec))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isTrimming = true
                        scope.launch {
                            try {
                                val outFile = File(videoDir, "${video.file.nameWithoutExtension}_trim.mp4")
                                VideoTrimmer.trim(
                                    video.file, outFile,
                                    (trimStartSec * 1000).toLong(),
                                    (trimEndSec * 1000).toLong()
                                ) { trimProgress = it }

                                val durationMs = VideoTrimmer.getDurationMs(outFile)
                                var thumbPath: String? = null
                                withContext(Dispatchers.IO) {
                                    val bmp = VideoTrimmer.extractThumbnail(outFile)
                                    if (bmp != null) {
                                        val thumbFile = File(videoDir, "${outFile.nameWithoutExtension}_thumb.jpg")
                                        thumbFile.outputStream().use {
                                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it)
                                        }
                                        thumbPath = thumbFile.absolutePath
                                    }
                                }

                                db.videoRingtoneDao().insert(VideoRingtone(
                                    title = "${video.title} (trim)",
                                    artist = video.artist,
                                    localPath = outFile.absolutePath,
                                    thumbnailPath = thumbPath,
                                    durationMs = durationMs,
                                    videoId = null
                                ))
                                refresh()
                                snackbarHostState.showSnackbar("Video getrimd en opgeslagen")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Trim mislukt: ${e.message}")
                            }
                            isTrimming = false
                            trimProgress = 0f
                            showTrimDialog = null
                        }
                    },
                    enabled = !isTrimming
                ) { Text("Trimmen") }
            },
            dismissButton = {
                TextButton(onClick = { showTrimDialog = null }, enabled = !isTrimming) { Text("Annuleren") }
            }
        )
    }

    // === DELETE DIALOOG ===
    showDeleteDialog?.let { video ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Verwijderen: ${video.title}") },
            text = { Text("Video en bestand permanent verwijderen?") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val vr = db.videoRingtoneDao().getById(video.id)
                            if (vr != null) db.videoRingtoneDao().delete(vr)
                            video.file.delete()
                            video.thumbnailPath?.let { File(it).delete() }
                            refresh()
                            snackbarHostState.showSnackbar("Verwijderd: ${video.title}")
                        }
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Verwijderen") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Annuleren") }
            }
        )
    }
}

@Composable
private fun VideoCard(
    video: VideoItem,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onTrim: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (video.isActive) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) else CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val thumbBitmap = remember(video.thumbnailPath) {
                    video.thumbnailPath?.let { path ->
                        try {
                            android.graphics.BitmapFactory.decodeFile(path)
                        } catch (_: Exception) { null }
                    }
                }
                if (thumbBitmap != null) {
                    Image(
                        bitmap = thumbBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.Videocam, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val durationSec = video.durationMs / 1000
                val sizeKb = if (video.file.exists()) video.file.length() / 1024 else 0
                Text("${durationSec / 60}:%02d — ${sizeKb}KB".format(durationSec % 60),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (video.isActive) {
                    Text("ACTIEF", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            // Actions
            Column {
                IconButton(onClick = { if (video.isActive) onDeactivate() else onActivate() }) {
                    Icon(
                        if (video.isActive) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive,
                        contentDescription = if (video.isActive) "Deactiveren" else "Activeren",
                        tint = if (video.isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onTrim) {
                    Icon(Icons.Default.ContentCut, contentDescription = "Trimmen",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Verwijderen",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
