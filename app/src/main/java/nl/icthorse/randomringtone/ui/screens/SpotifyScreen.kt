package nl.icthorse.randomringtone.ui.screens

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.*
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.*
import java.io.File

private const val SPOTIFY_WEB_URL = "https://open.spotify.com"
private val SPOTIFY_TRACK_REGEX = Regex("open\\.spotify\\.com/track/([a-zA-Z0-9]+)")

/**
 * Spotify-naar-MP3 scherm.
 *
 * Architectuur: twee gescheiden WebViews.
 * 1. Spotify WebView (altijd actief) — browse open.spotify.com
 * 2. Converter WebView (fullscreen dialoog) — aparte schone instantie zonder Spotify cookies
 *
 * Flow: zoek track → delen/kopieer URL → FAB → converter dialoog → download → acties
 */
@Composable
fun SpotifyScreen(
    ringtoneManager: AppRingtoneManager,
    db: RingtoneDatabase,
    snackbarHostState: SnackbarHostState,
    onOpenEditor: ((String, File) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Converter config
    var converterUrl by remember { mutableStateOf("") }
    var converterName by remember { mutableStateOf("") }
    var useDirectApi by remember { mutableStateOf(false) }

    // Direct API state
    val spotMateClient = remember { SpotMateDirectClient() }
    var isDirectDownloading by remember { mutableStateOf(false) }
    var directDownloadPhase by remember { mutableStateOf("") }
    var directDownloadProgress by remember { mutableFloatStateOf(0f) }

    // Spotify WebView state
    var spotifyWebView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf("") }

    // Track detectie
    var detectedTrackUrl by remember { mutableStateOf<String?>(null) }
    var processedClipUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var detectedTrackName by remember { mutableStateOf("") }
    var detectedArtist by remember { mutableStateOf("") }

    // Converter dialoog
    var showConverter by remember { mutableStateOf(false) }

    // Download state
    var lastDownloadedFile by remember { mutableStateOf<File?>(null) }
    var showActionsDialog by remember { mutableStateOf(false) }

    // Overwrite state — voor bestaande bestanden
    var showOverwriteDialog by remember { mutableStateOf(false) }
    var pendingOverwriteUrl by remember { mutableStateOf<String?>(null) }
    var pendingOverwriteFile by remember { mutableStateOf<File?>(null) }
    var pendingOverwriteTrackInfo by remember { mutableStateOf<SpotMateDirectClient.TrackInfo?>(null) }
    // Voor WebView converter flow
    var pendingConverterSource by remember { mutableStateOf<File?>(null) }
    var pendingConverterDest by remember { mutableStateOf<File?>(null) }

    // Track bevestiging state — vergelijk SpotMate metadata met Spotify pagina
    var showConfirmTrackDialog by remember { mutableStateOf(false) }
    var confirmTrackInfo by remember { mutableStateOf<SpotMateDirectClient.TrackInfo?>(null) }
    var confirmTrackUrl by remember { mutableStateOf<String?>(null) }
    var confirmSpotifyTitle by remember { mutableStateOf("") }
    var confirmSpotifyArtist by remember { mutableStateOf("") }

    // Laad converter uit instellingen
    LaunchedEffect(Unit) {
        val converterId = ringtoneManager.storage.getSpotifyConverter()
        val converter = SpotifyConverter.findById(converterId)
        converterUrl = converter.url
        converterName = converter.name
        useDirectApi = ringtoneManager.storage.isDirectApiEnabled()
    }

    // Download completion receiver
    DisposableEffect(Unit) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (downloadId == -1L) return

                val query = DownloadManager.Query().setFilterById(downloadId)
                downloadManager.query(query)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        if (statusIdx >= 0 && cursor.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL) {
                            val localUri = if (uriIdx >= 0) cursor.getString(uriIdx) else null
                            if (localUri != null) {
                                val file = File(Uri.parse(localUri).path ?: return)
                                scope.launch {
                                    val destName = if (detectedTrackName.isNotBlank() && detectedArtist.isNotBlank()) {
                                        val sanitized = "${detectedTrackName}-${detectedArtist}"
                                            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                                            .replace(Regex("_+"), "_")
                                            .trim('_')
                                        "spotify_mp3_${sanitized}.mp3"
                                    } else {
                                        "spotify_mp3_${System.currentTimeMillis()}.mp3"
                                    }
                                    val destFile = File(
                                        ringtoneManager.storage.getDownloadDir(),
                                        destName
                                    )
                                    if (destFile.exists()) {
                                        // Bestand bestaat al — vraag gebruiker
                                        pendingConverterSource = file
                                        pendingConverterDest = destFile
                                        showConverter = false
                                        showOverwriteDialog = true
                                    } else {
                                        file.copyTo(destFile, overwrite = false)
                                        lastDownloadedFile = destFile
                                        showConverter = false
                                        showActionsDialog = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Check URL voor Spotify track URLs
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    LaunchedEffect(currentUrl) {
        val match = SPOTIFY_TRACK_REGEX.find(currentUrl)
        if (match != null) {
            detectedTrackUrl = currentUrl.split("?").first()
        } else {
            detectedTrackUrl = null
        }
    }

    // Klembord LIVE monitoren — reageert direct als Spotify "Delen → Link kopiëren" wordt gebruikt
    DisposableEffect(Unit) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (showConverter) return@OnPrimaryClipChangedListener
            try {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val clipText = clip.getItemAt(0).text?.toString() ?: ""
                    val clipMatch = SPOTIFY_TRACK_REGEX.find(clipText)
                    if (clipMatch != null) {
                        val clipUrl = clipText.split("?").first()
                        if (clipUrl !in processedClipUrls) {
                            detectedTrackUrl = clipUrl
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
        onDispose { clipboardManager.removePrimaryClipChangedListener(listener) }
    }

    // === SPOTIFY BROWSE UI ===
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Navigatiebalk
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { spotifyWebView?.goBack() },
                    enabled = canGoBack
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Terug")
                }
                IconButton(onClick = { spotifyWebView?.goForward() }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Vooruit")
                }
                IconButton(onClick = { spotifyWebView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Vernieuwen")
                }

                AssistChip(
                    onClick = { },
                    label = { Text("Spotify", style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )

                Spacer(modifier = Modifier.weight(1f))

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            // URL balk
            Text(
                text = currentUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Spotify WebView
            CreateWebView(
                url = SPOTIFY_WEB_URL,
                onWebViewCreated = { spotifyWebView = it },
                onPageStarted = { url -> isLoading = true; currentUrl = url },
                onPageFinished = { url ->
                    isLoading = false; currentUrl = url
                    canGoBack = spotifyWebView?.canGoBack() == true
                },
                onDownloadStart = null // Spotify heeft geen downloads
            )
        }

        // FAB: "Download MP3"
        AnimatedVisibility(
            visible = detectedTrackUrl != null && !showConverter && !isDirectDownloading,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = {
                    val trackUrl = detectedTrackUrl ?: return@ExtendedFloatingActionButton
                    // Extract track naam en artiest uit Spotify page title
                    val pageTitle = spotifyWebView?.title ?: ""
                    val titleMatch = Regex("^(.+?)\\s*[-–—]\\s*(?:song(?:\\s+and\\s+lyrics)?\\s+by\\s+)?(.+?)\\s*\\|\\s*Spotify")
                        .find(pageTitle)
                    if (titleMatch != null) {
                        detectedTrackName = titleMatch.groupValues[1].trim()
                        detectedArtist = titleMatch.groupValues[2].trim()
                    } else {
                        detectedTrackName = ""
                        detectedArtist = ""
                    }
                    // Markeer als verwerkt
                    processedClipUrls = processedClipUrls + trackUrl
                    detectedTrackUrl = null

                    if (useDirectApi) {
                        // === DIRECTE API: FASE 1 — metadata ophalen + bevestigen ===
                        isDirectDownloading = true
                        scope.launch {
                            directDownloadPhase = "Track info ophalen..."
                            directDownloadProgress = 0.1f
                            val trackInfo = spotMateClient.fetchTrackInfo(trackUrl)
                            isDirectDownloading = false
                            if (trackInfo != null) {
                                confirmTrackInfo = trackInfo
                                confirmTrackUrl = trackUrl
                                confirmSpotifyTitle = detectedTrackName
                                confirmSpotifyArtist = detectedArtist
                                showConfirmTrackDialog = true
                            } else {
                                snackbarHostState.showSnackbar(
                                    "Track info ophalen mislukt — probeer WebView converter"
                                )
                            }
                        }
                    } else {
                        // === WEBVIEW CONVERTER FLOW ===
                        clipboardManager.setPrimaryClip(ClipData.newPlainText("Spotify URL", trackUrl))
                        showConverter = true
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Spotify URL op klembord — plak in $converterName zoekveld",
                                duration = SnackbarDuration.Long
                            )
                        }
                    }
                },
                icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                text = { Text(if (useDirectApi) "Direct downloaden" else "Download MP3") },
                containerColor = MaterialTheme.colorScheme.primary
            )
        }

        // Directe download voortgang overlay
        if (isDirectDownloading) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(
                            text = directDownloadPhase,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    LinearProgressIndicator(
                        progress = { directDownloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // === CONVERTER FULLSCREEN DIALOOG (eigen schone WebView) ===
    if (showConverter && converterUrl.isNotBlank()) {
        ConverterDialog(
            converterUrl = converterUrl,
            converterName = converterName,
            onDismiss = { showConverter = false },
            onDownloadStart = { url, userAgent, contentDisposition, mimeType, _ ->
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    setTitle(fileName)
                    setDescription("Spotify to MP3 — $converterName")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(context, "Downloaden: $fileName", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // === TRACK BEVESTIGING DIALOOG — vergelijk SpotMate vs Spotify ===
    if (showConfirmTrackDialog && confirmTrackInfo != null) {
        val info = confirmTrackInfo!!
        val hasMismatch = confirmSpotifyTitle.isNotBlank() &&
            !info.name.equals(confirmSpotifyTitle, ignoreCase = true)

        AlertDialog(
            onDismissRequest = {
                showConfirmTrackDialog = false
                confirmTrackInfo = null
                confirmTrackUrl = null
            },
            title = {
                Text(if (hasMismatch) "Let op: ander nummer gevonden" else "Track bevestigen")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasMismatch) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Spotify pagina:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    "$confirmSpotifyTitle — $confirmSpotifyArtist",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (hasMismatch)
                                MaterialTheme.colorScheme.tertiaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "SpotMate vindt:",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                info.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                info.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (hasMismatch) {
                        Text(
                            "De converter geeft een ander nummer terug dan je Spotify pagina toont. Toch downloaden?",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showConfirmTrackDialog = false
                    val trackUrl = confirmTrackUrl ?: return@Button
                    confirmTrackInfo = null
                    confirmTrackUrl = null
                    // === FASE 2: daadwerkelijk downloaden ===
                    isDirectDownloading = true
                    scope.launch {
                        val result = spotMateClient.downloadTrack(
                            spotifyUrl = trackUrl,
                            destDir = ringtoneManager.storage.getDownloadDir(),
                            onProgress = { phase, progress ->
                                directDownloadPhase = phase
                                directDownloadProgress = progress
                            }
                        )
                        isDirectDownloading = false
                        if (result.fileExists && result.file != null) {
                            pendingOverwriteUrl = trackUrl
                            pendingOverwriteFile = result.file
                            pendingOverwriteTrackInfo = result.trackInfo
                            showOverwriteDialog = true
                        } else if (result.success && result.file != null) {
                            lastDownloadedFile = result.file
                            showActionsDialog = true
                            if (result.trackInfo != null) {
                                detectedTrackName = result.trackInfo.name
                                detectedArtist = result.trackInfo.artist
                            }
                        } else {
                            snackbarHostState.showSnackbar(
                                result.error ?: "Download mislukt — probeer WebView converter"
                            )
                        }
                    }
                }) { Text("Downloaden") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showConfirmTrackDialog = false
                    confirmTrackInfo = null
                    confirmTrackUrl = null
                }) { Text("Annuleren") }
            }
        )
    }

    // === OVERWRITE DIALOOG — bestand bestaat al ===
    if (showOverwriteDialog) {
        val existingFile = pendingOverwriteFile ?: pendingConverterDest
        AlertDialog(
            onDismissRequest = {
                showOverwriteDialog = false
                pendingOverwriteUrl = null
                pendingOverwriteFile = null
                pendingOverwriteTrackInfo = null
                pendingConverterSource = null
                pendingConverterDest = null
            },
            title = { Text("Bestand bestaat al") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        existingFile?.name ?: "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Dit nummer is al eerder gedownload. Opnieuw downloaden en overschrijven?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showOverwriteDialog = false
                    if (pendingConverterSource != null && pendingConverterDest != null) {
                        // WebView converter flow — overschrijf direct
                        scope.launch {
                            pendingConverterSource!!.copyTo(pendingConverterDest!!, overwrite = true)
                            lastDownloadedFile = pendingConverterDest
                            pendingConverterSource = null
                            pendingConverterDest = null
                            showActionsDialog = true
                        }
                    } else if (pendingOverwriteUrl != null) {
                        // SpotMate direct API flow — opnieuw downloaden met forceOverwrite
                        val url = pendingOverwriteUrl!!
                        pendingOverwriteUrl = null
                        pendingOverwriteFile = null
                        pendingOverwriteTrackInfo = null
                        isDirectDownloading = true
                        scope.launch {
                            val result = spotMateClient.downloadTrack(
                                spotifyUrl = url,
                                destDir = ringtoneManager.storage.getDownloadDir(),
                                onProgress = { phase, progress ->
                                    directDownloadPhase = phase
                                    directDownloadProgress = progress
                                },
                                forceOverwrite = true
                            )
                            isDirectDownloading = false
                            if (result.success && result.file != null) {
                                lastDownloadedFile = result.file
                                showActionsDialog = true
                                if (result.trackInfo != null) {
                                    detectedTrackName = result.trackInfo.name
                                    detectedArtist = result.trackInfo.artist
                                }
                            } else {
                                snackbarHostState.showSnackbar(
                                    result.error ?: "Download mislukt"
                                )
                            }
                        }
                    }
                }) { Text("Overschrijven") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOverwriteDialog = false
                    pendingOverwriteUrl = null
                    pendingOverwriteFile = null
                    pendingOverwriteTrackInfo = null
                    pendingConverterSource = null
                    pendingConverterDest = null
                }) { Text("Annuleren") }
            }
        )
    }

    // === ACTIES DIALOOG na download ===
    if (showActionsDialog && lastDownloadedFile != null) {
        val file = lastDownloadedFile!!
        AlertDialog(
            onDismissRequest = { showActionsDialog = false },
            title = { Text("MP3 gedownload") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(file.name, style = MaterialTheme.typography.bodyMedium)
                    Text("Wat wil je doen?", style = MaterialTheme.typography.labelLarge)
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onOpenEditor != null) {
                        Button(
                            onClick = {
                                showActionsDialog = false
                                onOpenEditor(file.nameWithoutExtension, file)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Openen in editor")
                        }
                    }
                    Button(
                        onClick = {
                            showActionsDialog = false
                            scope.launch {
                                val trackId = file.name.hashCode().toLong()
                                db.savedTrackDao().insert(
                                    SavedTrack(
                                        deezerTrackId = trackId,
                                        title = file.nameWithoutExtension,
                                        artist = "Spotify",
                                        previewUrl = "",
                                        localPath = file.absolutePath,
                                        playlistName = "_spotify",
                                        markerType = "track"
                                    )
                                )
                                snackbarHostState.showSnackbar("Track opgeslagen in bibliotheek")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Opslaan in bibliotheek")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showActionsDialog = false }) { Text("Sluiten") }
            }
        )
    }
}

// === CONVERTER DIALOOG — eigen schone WebView ===

@Composable
private fun ConverterDialog(
    converterUrl: String,
    converterName: String,
    onDismiss: () -> Unit,
    onDownloadStart: (url: String, userAgent: String, contentDisposition: String, mimeType: String, contentLength: Long) -> Unit
) {
    var converterWebView by remember { mutableStateOf<WebView?>(null) }
    var converterLoading by remember { mutableStateOf(true) }
    var converterCanGoBack by remember { mutableStateOf(false) }
    var converterCurrentUrl by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Converter navigatiebalk
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Sluiten")
                    }
                    IconButton(
                        onClick = { converterWebView?.goBack() },
                        enabled = converterCanGoBack
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Terug")
                    }
                    IconButton(onClick = { converterWebView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Vernieuwen")
                    }

                    AssistChip(
                        onClick = { },
                        label = { Text(converterName, style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    if (converterLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }

                Text(
                    text = converterCurrentUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                )

                if (converterLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Hint
                Text(
                    text = "Plak de Spotify URL (zit op je klembord) in het zoekveld",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                )

                // Converter WebView — schone instantie, blokkeert Spotify redirects
                CreateWebView(
                    url = converterUrl,
                    blockSpotifyDomain = true,
                    onWebViewCreated = { converterWebView = it },
                    onPageStarted = { url -> converterLoading = true; converterCurrentUrl = url },
                    onPageFinished = { url ->
                        converterLoading = false; converterCurrentUrl = url
                        converterCanGoBack = converterWebView?.canGoBack() == true
                    },
                    onDownloadStart = onDownloadStart
                )
            }
        }
    }
}

// === GEDEELDE WEBVIEW FACTORY ===

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CreateWebView(
    url: String,
    blockSpotifyDomain: Boolean = false,
    onWebViewCreated: (WebView) -> Unit,
    onPageStarted: (String) -> Unit,
    onPageFinished: (String) -> Unit,
    onDownloadStart: ((url: String, userAgent: String, contentDisposition: String, mimeType: String, contentLength: Long) -> Unit)?
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowContentAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportMultipleWindows(false)
                settings.mediaPlaybackRequiresUserGesture = false

                // Chrome Mobile UA voor alle WebViews — voorkomt blokkades
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                        onPageStarted(pageUrl ?: "")
                    }
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        onPageFinished(pageUrl ?: "")
                    }
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val requestUrl = request?.url?.toString() ?: return false
                        // Blokkeer navigatie naar Spotify vanuit converter
                        if (blockSpotifyDomain && (
                            requestUrl.contains("open.spotify.com") ||
                            requestUrl.contains("accounts.spotify.com") ||
                            requestUrl.contains("spotify.com/login")
                        )) {
                            return true // Blokkeer — niet navigeren
                        }
                        if (requestUrl.startsWith("intent://") || requestUrl.startsWith("market://")) {
                            return true
                        }
                        return false
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                        request.grant(request.resources)
                    }
                }

                if (onDownloadStart != null) {
                    setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, contentLength ->
                        onDownloadStart(downloadUrl, userAgent, contentDisposition, mimeType, contentLength)
                    }
                }

                onWebViewCreated(this)
                loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
