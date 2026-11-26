package nl.icthorse.randomringtone.ui.screens

import nl.icthorse.randomringtone.AppBusyState
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.webkit.*
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
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.*
import java.io.File

private const val SPOTIFY_WEB_URL = "https://open.spotify.com"
private val SPOTIFY_TRACK_REGEX = Regex("open\\.spotify\\.com/track/([a-zA-Z0-9]+)")

/**
 * Spotify-scherm (v2.6.0: alleen nog volledig nummer via YouTube).
 *
 * Eén WebView met open.spotify.com. Flow: nummer zoeken → Delen → Link kopiëren → de app zoekt het nummer op
 * YouTube en downloadt de audio → acties. Playlist of album in beeld → importeren.
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

    // Direct API state
    val spotifyPreviewClient = remember { SpotifyPreviewClient(context) }
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
    // v2.5.0: playlist of album in beeld (of op het klembord) → importknop
    var detectedCollection by remember { mutableStateOf<SpotifyCollection.Ref?>(null) }
    var importRef by remember { mutableStateOf<SpotifyCollection.Ref?>(null) }
    var processedClipUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var detectedTrackName by remember { mutableStateOf("") }
    var detectedArtist by remember { mutableStateOf("") }

    // Download state
    var lastDownloadedFile by remember { mutableStateOf<File?>(null) }
    var showActionsDialog by remember { mutableStateOf(false) }

    // Overwrite state — voor bestaande bestanden
    var showOverwriteDialog by remember { mutableStateOf(false) }
    var pendingOverwriteUrl by remember { mutableStateOf<String?>(null) }
    var pendingOverwriteFile by remember { mutableStateOf<File?>(null) }
    var pendingOverwriteTrackInfo by remember { mutableStateOf<SpotifyPreviewClient.TrackInfo?>(null) }

    // Track bevestiging state — vergelijk SpotMate metadata met Spotify pagina
    var showConfirmTrackDialog by remember { mutableStateOf(false) }
    var confirmTrackInfo by remember { mutableStateOf<SpotifyPreviewClient.TrackInfo?>(null) }
    var confirmTrackUrl by remember { mutableStateOf<String?>(null) }
    var confirmSpotifyTitle by remember { mutableStateOf("") }
    var confirmSpotifyArtist by remember { mutableStateOf("") }

    // Check URL voor Spotify track URLs
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    LaunchedEffect(currentUrl) {
        val match = SPOTIFY_TRACK_REGEX.find(currentUrl)
        if (match != null) {
            detectedTrackUrl = currentUrl.split("?").first()
        } else {
            detectedTrackUrl = null
        }
        detectedCollection = SpotifyCollection.parseLink(currentUrl)
    }

    // Eén downloadpad voor de knop (na bevestiging) en voor delen (v2.5.1: start direct, zonder bevestiging).
    val startDownload: (String) -> Unit = { trackUrl ->
        isDirectDownloading = true
        AppBusyState.isBusy = true
        directDownloadPhase = "Nummer opzoeken..."
        directDownloadProgress = 0.05f
        scope.launch {
            val result = try {
                spotifyPreviewClient.downloadFull(trackUrl,
                    ringtoneManager.storage.getDownloadDir(), { phase, progress ->
                    directDownloadPhase = phase
                    directDownloadProgress = progress
                }, false)
            } finally {
                isDirectDownloading = false
                AppBusyState.isBusy = false
            }
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
                    result.error ?: "Download mislukt"
                )
            }
        }
    }
    val startDownloadNow by rememberUpdatedState(startDownload)

    // Klembord LIVE monitoren — reageert direct als Spotify "Delen → Link kopiëren" wordt gebruikt
    DisposableEffect(Unit) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val clipText = clip.getItemAt(0).text?.toString() ?: ""
                    SpotifyCollection.parseLink(clipText)?.let { detectedCollection = it }
                    val clipMatch = SPOTIFY_TRACK_REGEX.find(clipText)
                    if (clipMatch != null) {
                        val clipUrl = clipText.split("?").first()
                        if (clipUrl !in processedClipUrls) {
                            if (!isDirectDownloading) {
                                // v2.5.1: delen is genoeg — direct zoeken en downloaden
                                processedClipUrls = processedClipUrls + clipUrl
                                detectedTrackUrl = null
                                RemoteLogger.i("Spotify", "Delen → direct downloaden", mapOf("method" to "full"))
                                startDownloadNow(clipUrl)
                            } else {
                                detectedTrackUrl = clipUrl
                            }
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
                }
            )
        }

        // FAB: playlist of album importeren (v2.5.0)
        AnimatedVisibility(
            visible = detectedCollection != null && detectedTrackUrl == null && !isDirectDownloading,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = { importRef = detectedCollection; detectedCollection = null },
                icon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                text = { Text(if (detectedCollection?.kind == SpotifyCollection.Kind.ALBUM) "Album importeren" else "Playlist importeren") }
            )
        }

        // FAB: "Download MP3"
        AnimatedVisibility(
            visible = detectedTrackUrl != null && !isDirectDownloading,
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

                    // Fase 1 — metadata ophalen + bevestigen
                    isDirectDownloading = true
                    AppBusyState.isBusy = true
                    scope.launch {
                        directDownloadPhase = "Track info ophalen..."
                        directDownloadProgress = 0.1f
                        val trackInfo = spotifyPreviewClient.fetchTrackInfoEmbed(trackUrl)
                        isDirectDownloading = false
                        AppBusyState.isBusy = false
                        if (trackInfo != null) {
                            confirmTrackInfo = trackInfo
                            confirmTrackUrl = trackUrl
                            confirmSpotifyTitle = detectedTrackName
                            confirmSpotifyArtist = detectedArtist
                            showConfirmTrackDialog = true
                        } else {
                            snackbarHostState.showSnackbar(
                                spotifyPreviewClient.lastError ?: "Track info ophalen mislukt"
                            )
                        }
                    }
                },
                icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                text = { Text("Volledig nummer downloaden") },
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
                                "Gevonden:",
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
                            "Spotify geeft een ander nummer terug dan de pagina toont. Toch downloaden?",
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
                    startDownload(trackUrl)
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
        val existingFile = pendingOverwriteFile
        AlertDialog(
            onDismissRequest = {
                showOverwriteDialog = false
                pendingOverwriteUrl = null
                pendingOverwriteFile = null
                pendingOverwriteTrackInfo = null
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
                    if (pendingOverwriteUrl != null) {
                        // opnieuw downloaden met forceOverwrite
                        val url = pendingOverwriteUrl!!
                        pendingOverwriteUrl = null
                        pendingOverwriteFile = null
                        pendingOverwriteTrackInfo = null
                        isDirectDownloading = true
                        AppBusyState.isBusy = true
                        scope.launch {
                            val result = spotifyPreviewClient.downloadFull(url,
                                ringtoneManager.storage.getDownloadDir(), { phase, progress ->
                                directDownloadPhase = phase
                                directDownloadProgress = progress
                            }, true)
                            isDirectDownloading = false
                            AppBusyState.isBusy = false
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
                }) { Text("Annuleren") }
            }
        )
    }

    // === ACTIES DIALOOG na download ===
    importRef?.let { ref ->
        SpotifyImportDialog(ref = ref, client = spotifyPreviewClient, ringtoneManager = ringtoneManager, db = db,
            onDismiss = { importRef = null })
    }

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
                                // Pre-register in DB met albumArt zodat editor het kan vinden
                                scope.launch {
                                    val trackId = TrackIdResolver.canonicalTrackIdForName(file.name)
                                    val artPath = extractSpotifyArt(context, file)
                                    db.savedTrackDao().insert(
                                        SavedTrack(
                                            deezerTrackId = trackId,
                                            title = file.nameWithoutExtension,
                                            artist = "Spotify",
                                            previewUrl = "",
                                            localPath = file.absolutePath,
                                            playlistName = "_spotify",
                                            markerType = "track",
                                            albumArtPath = artPath
                                        )
                                    )
                                }
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
                                val trackId = TrackIdResolver.canonicalTrackIdForName(file.name)
                                val artPath = extractSpotifyArt(context, file)
                                db.savedTrackDao().insert(
                                    SavedTrack(
                                        deezerTrackId = trackId,
                                        title = file.nameWithoutExtension,
                                        artist = "Spotify",
                                        previewUrl = "",
                                        localPath = file.absolutePath,
                                        playlistName = "_spotify",
                                        markerType = "track",
                                        albumArtPath = artPath
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

// === GEDEELDE WEBVIEW FACTORY ===

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CreateWebView(
    url: String,
    onWebViewCreated: (WebView) -> Unit,
    onPageFinished: (String) -> Unit
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


                onWebViewCreated(this)
                loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

/** Extract album art uit Spotify MP3 (embedded via converter) en sla op in cache. */
internal fun extractSpotifyArt(context: android.content.Context, audioFile: File): String? {
    val retriever = android.media.MediaMetadataRetriever()
    return try {
        retriever.setDataSource(audioFile.absolutePath)
        val artBytes = retriever.embeddedPicture ?: return null
        if (artBytes.size < 1000) return null
        val artDir = File(context.cacheDir, "album_art").apply { mkdirs() }
        val artFile = File(artDir, "${audioFile.nameWithoutExtension.hashCode()}.jpg")
        artFile.outputStream().use { it.write(artBytes) }
        artFile.absolutePath
    } catch (_: Exception) { null }
    finally { try { retriever.release() } catch (_: Exception) {} }
}

