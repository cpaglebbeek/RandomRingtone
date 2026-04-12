package nl.icthorse.randomringtone.ui.screens

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.icthorse.randomringtone.AppBusyState
import nl.icthorse.randomringtone.data.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private const val YOUTUBE_URL = "https://www.youtube.com"
private val YOUTUBE_WATCH_REGEX = Regex("""youtube\.com/watch\?v=([a-zA-Z0-9_\-]{11})""")
private val YOUTUBE_SHORTS_REGEX = Regex("""youtube\.com/shorts/([a-zA-Z0-9_\-]{11})""")
private val YOUTUBE_SHORT_LINK_REGEX = Regex("""youtu\.be/([a-zA-Z0-9_\-]{11})""")

/**
 * YouTube-naar-MP3 scherm.
 *
 * Architectuur: WebView naar m.youtube.com.
 * Detecteert /watch?v= URLs en toont FAB om MP3 te downloaden via Y2Mate API.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeScreen(
    ringtoneManager: AppRingtoneManager,
    db: RingtoneDatabase,
    snackbarHostState: SnackbarHostState,
    onOpenEditor: ((String, File) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val y2MateClient = remember { Y2MateClient() }

    // WebView state
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf("") }

    // Track detectie
    var detectedVideoId by remember { mutableStateOf<String?>(null) }
    var detectedVideoTitle by remember { mutableStateOf("") }
    var processedClipUrls by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Clipboard monitoring voor youtu.be share links
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    DisposableEffect(Unit) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val clipText = clip.getItemAt(0).text?.toString() ?: ""
                    val shortMatch = YOUTUBE_SHORT_LINK_REGEX.find(clipText)
                    val watchMatch = YOUTUBE_WATCH_REGEX.find(clipText)
                    val match = shortMatch ?: watchMatch
                    if (match != null) {
                        val videoId = match.groupValues[1]
                        if (clipText !in processedClipUrls) {
                            detectedVideoId = videoId
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
        onDispose { clipboardManager.removePrimaryClipChangedListener(listener) }
    }

    // Download state
    var isDownloading by remember { mutableStateOf(false) }
    var downloadPhase by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    // Post-download dialogen
    var lastDownloadedFile by remember { mutableStateOf<File?>(null) }
    var showActionsDialog by remember { mutableStateOf(false) }
    var showOverwriteDialog by remember { mutableStateOf(false) }
    var pendingOverwriteVideoId by remember { mutableStateOf<String?>(null) }
    var pendingOverwriteFile by remember { mutableStateOf<File?>(null) }
    var pendingOverwriteTitle by remember { mutableStateOf<String?>(null) }

    // URL monitoring voor video detectie (adresbalk + youtu.be)
    LaunchedEffect(currentUrl) {
        val watchMatch = YOUTUBE_WATCH_REGEX.find(currentUrl)
        val shortsMatch = YOUTUBE_SHORTS_REGEX.find(currentUrl)
        val shortLinkMatch = YOUTUBE_SHORT_LINK_REGEX.find(currentUrl)
        val match = watchMatch ?: shortsMatch ?: shortLinkMatch
        if (match != null) {
            detectedVideoId = match.groupValues[1]
        } else {
            detectedVideoId = null
        }
    }

    // === UI ===
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
                    onClick = { webView?.goBack() },
                    enabled = canGoBack
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Terug")
                }
                IconButton(onClick = { webView?.goForward() }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Vooruit")
                }
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Vernieuwen")
                }

                AssistChip(
                    onClick = { },
                    label = { Text("YouTube", style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = {
                        Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
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

            // YouTube WebView
            YouTubeWebView(
                onWebViewCreated = { webView = it },
                onPageStarted = { url -> isLoading = true; currentUrl = url },
                onPageFinished = { url ->
                    isLoading = false; currentUrl = url
                    canGoBack = webView?.canGoBack() == true
                    // Extract title
                    webView?.evaluateJavascript("document.title") { title ->
                        val cleaned = title?.trim('"') ?: ""
                        if (cleaned.isNotBlank() && cleaned != "null") {
                            // Strip " - YouTube" suffix
                            detectedVideoTitle = cleaned
                                .replace(Regex("\\s*[-–—]\\s*YouTube\\s*$"), "")
                                .trim()
                        }
                    }
                }
            )
        }

        // FAB: "Download MP3"
        AnimatedVisibility(
            visible = detectedVideoId != null && !isDownloading,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = {
                    val videoId = detectedVideoId ?: return@ExtendedFloatingActionButton
                    // Markeer als verwerkt (voorkom dubbele clipboard triggers)
                    processedClipUrls = processedClipUrls + "youtu.be/$videoId" + "youtube.com/watch?v=$videoId"
                    isDownloading = true
                    AppBusyState.isBusy = true
                    scope.launch {
                        val result = y2MateClient.downloadTrack(
                            videoId = videoId,
                            destDir = ringtoneManager.storage.getDownloadDir(),
                            videoTitle = detectedVideoTitle.ifBlank { null },
                            onProgress = { phase, progress ->
                                downloadPhase = phase
                                downloadProgress = progress
                            }
                        )
                        isDownloading = false
                        AppBusyState.isBusy = false
                        if (result.fileExists && result.file != null) {
                            pendingOverwriteVideoId = videoId
                            pendingOverwriteFile = result.file
                            pendingOverwriteTitle = detectedVideoTitle.ifBlank { result.title }
                            showOverwriteDialog = true
                        } else if (result.success && result.file != null) {
                            lastDownloadedFile = result.file
                            detectedVideoTitle = result.title ?: detectedVideoTitle
                            // Fetch YouTube thumbnail als album art
                            result.videoId?.let { vid ->
                                fetchYouTubeThumbnail(context, vid, result.file)
                            }
                            showActionsDialog = true
                        } else {
                            snackbarHostState.showSnackbar(
                                result.error ?: "Download mislukt"
                            )
                        }
                    }
                },
                icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                text = { Text("Download MP3") },
                containerColor = MaterialTheme.colorScheme.error
            )
        }

        // Download voortgang overlay
        if (isDownloading) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
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
                            text = downloadPhase,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2
                        )
                    }
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // === OVERWRITE DIALOOG ===
    if (showOverwriteDialog) {
        AlertDialog(
            onDismissRequest = {
                showOverwriteDialog = false
                pendingOverwriteVideoId = null
                pendingOverwriteFile = null
                pendingOverwriteTitle = null
            },
            title = { Text("Bestand bestaat al") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        pendingOverwriteFile?.name ?: "",
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
                    val videoId = pendingOverwriteVideoId ?: return@Button
                    val title = pendingOverwriteTitle
                    pendingOverwriteVideoId = null
                    pendingOverwriteFile = null
                    pendingOverwriteTitle = null
                    isDownloading = true
                    AppBusyState.isBusy = true
                    scope.launch {
                        val result = y2MateClient.downloadTrack(
                            videoId = videoId,
                            destDir = ringtoneManager.storage.getDownloadDir(),
                            videoTitle = title,
                            onProgress = { phase, progress ->
                                downloadPhase = phase
                                downloadProgress = progress
                            },
                            forceOverwrite = true
                        )
                        isDownloading = false
                        AppBusyState.isBusy = false
                        if (result.success && result.file != null) {
                            lastDownloadedFile = result.file
                            showActionsDialog = true
                        } else {
                            snackbarHostState.showSnackbar(
                                result.error ?: "Download mislukt"
                            )
                        }
                    }
                }) { Text("Overschrijven") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOverwriteDialog = false
                    pendingOverwriteVideoId = null
                    pendingOverwriteFile = null
                    pendingOverwriteTitle = null
                }) { Text("Annuleren") }
            }
        )
    }

    // === ACTIES DIALOOG na download ===
    if (showActionsDialog && lastDownloadedFile != null) {
        val file = lastDownloadedFile!!
        AlertDialog(
            onDismissRequest = { showActionsDialog = false },
            title = { Text("YouTube MP3 gedownload") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        detectedVideoTitle.ifBlank { file.nameWithoutExtension },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Grootte: ${file.length() / 1024} KB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                    val trackId = file.name.hashCode().toLong()
                                    val artPath = getYouTubeArtPath(context, file)
                                    db.savedTrackDao().insert(
                                        SavedTrack(
                                            deezerTrackId = trackId,
                                            title = detectedVideoTitle.ifBlank { file.nameWithoutExtension },
                                            artist = "YouTube",
                                            previewUrl = "",
                                            localPath = file.absolutePath,
                                            playlistName = "_youtube",
                                            markerType = "youtube",
                                            albumArtPath = artPath
                                        )
                                    )
                                }
                                onOpenEditor(detectedVideoTitle.ifBlank { file.nameWithoutExtension }, file)
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
                                val artPath = getYouTubeArtPath(context, file)
                                db.savedTrackDao().insert(
                                    SavedTrack(
                                        deezerTrackId = trackId,
                                        title = detectedVideoTitle.ifBlank { file.nameWithoutExtension },
                                        artist = "YouTube",
                                        previewUrl = "",
                                        localPath = file.absolutePath,
                                        playlistName = "_youtube",
                                        markerType = "youtube",
                                        albumArtPath = artPath
                                    )
                                )
                                snackbarHostState.showSnackbar("YouTube clip opgeslagen in bibliotheek")
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

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun YouTubeWebView(
    onWebViewCreated: (WebView) -> Unit,
    onPageStarted: (String) -> Unit,
    onPageFinished: (String) -> Unit
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowContentAccess = true
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = false
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.setSupportMultipleWindows(false)
                settings.mediaPlaybackRequiresUserGesture = true

                // Mobiele UA → m.youtube.com met native mobiel zoekveld
                // (desktop UA had CSS-hack nodig die niet werkte)

                // Touch events doorlaten naar WebView
                requestFocusFromTouch()
                setOnTouchListener { v, event ->
                    v.performClick()
                    false // laat event door naar WebView
                }

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
                loadUrl(YOUTUBE_URL)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

// --- YouTube thumbnail helpers ---

private val thumbClient = OkHttpClient.Builder()
    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
    .build()

/** Fetch YouTube thumbnail en sla op als album art cache file. */
private suspend fun fetchYouTubeThumbnail(context: android.content.Context, videoId: String, audioFile: File) {
    withContext(Dispatchers.IO) {
        try {
            val url = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
            val request = Request.Builder().url(url).build()
            thumbClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext
                val bytes = response.body?.bytes() ?: return@withContext
                if (bytes.size < 1000) return@withContext // te klein, geen geldige afbeelding
                val artDir = File(context.cacheDir, "album_art").apply { mkdirs() }
                val artFile = File(artDir, "${audioFile.nameWithoutExtension.hashCode()}.jpg")
                artFile.outputStream().use { it.write(bytes) }
                RemoteLogger.d("YouTubeScreen", "Thumbnail opgeslagen", mapOf(
                    "videoId" to videoId, "size" to "${bytes.size / 1024}KB"
                ))
            }
        } catch (_: Exception) {
            // Thumbnail ophalen mislukt — geen probleem, gewoon geen art
        }
    }
}

/** Haal het pad op van de eerder opgeslagen thumbnail (als die bestaat). */
private fun getYouTubeArtPath(context: android.content.Context, audioFile: File): String? {
    val artFile = File(File(context.cacheDir, "album_art"), "${audioFile.nameWithoutExtension.hashCode()}.jpg")
    return if (artFile.exists() && artFile.length() > 1000) artFile.absolutePath else null
}
