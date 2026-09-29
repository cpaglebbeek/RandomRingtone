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
    var youtubeMethod by remember { mutableStateOf(nl.icthorse.randomringtone.data.YouTubeMethod.DEVICE) }
    LaunchedEffect(Unit) { youtubeMethod = ringtoneManager.storage.getYouTubeMethod() }

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
                        val result = downloadYouTube(y2MateClient, youtubeMethod, videoId,
                            ringtoneManager.storage.getDownloadDir(), detectedVideoTitle.ifBlank { null }, false) { phase, progress ->
                            downloadPhase = phase
                            downloadProgress = progress
                        }
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
                            result.videoId?.takeIf { result.file.extension.equals("mp3", true) }?.let { vid ->
                                fetchYouTubeThumbnail(context, vid, result.file, result.title)
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
                        val result = downloadYouTube(y2MateClient, youtubeMethod, videoId,
                            ringtoneManager.storage.getDownloadDir(), title, true) { phase, progress ->
                            downloadPhase = phase
                            downloadProgress = progress
                        }
                        isDownloading = false
                        AppBusyState.isBusy = false
                        if (result.success && result.file != null) {
                            lastDownloadedFile = result.file
                            // Ook bij overschrijven de thumbnail als album art (ontbrak tot v2.2.0)
                            if (result.file.extension.equals("mp3", true))
                                fetchYouTubeThumbnail(context, videoId, result.file, result.title ?: title)
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
                                    val trackId = TrackIdResolver.canonicalTrackIdForName(file.name)
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

/** Fetch YouTube thumbnail, sla op als album art cache file en embed in MP3 (APIC). */
private suspend fun fetchYouTubeThumbnail(context: android.content.Context, videoId: String, audioFile: File, title: String? = null) {
    withContext(Dispatchers.IO) {
        try {
            // hqdefault bestaat bijna altijd; mqdefault als vangnet (v2.2.0)
            val bytes = listOf("hqdefault", "mqdefault").firstNotNullOfOrNull { variant ->
                try {
                    thumbClient.newCall(Request.Builder().url("https://i.ytimg.com/vi/$videoId/$variant.jpg").build())
                        .execute().use { r -> r.body?.bytes()?.takeIf { r.isSuccessful && it.size >= 1000 } }
                } catch (_: Exception) { null }
            } ?: return@withContext
            run {
                val artDir = File(context.cacheDir, "album_art").apply { mkdirs() }
                val artFile = File(artDir, "${audioFile.nameWithoutExtension.hashCode()}.jpg")
                artFile.outputStream().use { it.write(bytes) }
                val embedded = Mp3AlbumArt.write(audioFile, bytes, title)
                RemoteLogger.d("YouTubeScreen", "Thumbnail opgeslagen", mapOf(
                    "videoId" to videoId,
                    "size" to "${bytes.size / 1024}KB",
                    "embedded" to embedded.toString()
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

/**
 * v2.3.0: YouTube-download volgens de gekozen methode. "Op toestel" (NewPipeExtractor, M4A) valt bij een fout
 * automatisch terug op Y2Mate (MP3); "bestaat al" wordt niet als fout gezien.
 */
private suspend fun downloadYouTube(
    y2mate: Y2MateClient,
    method: nl.icthorse.randomringtone.data.YouTubeMethod,
    videoId: String,
    destDir: File,
    title: String?,
    force: Boolean,
    onProgress: (String, Float) -> Unit
): Y2MateClient.DownloadResult {
    if (method == nl.icthorse.randomringtone.data.YouTubeMethod.DEVICE) {
        val r = nl.icthorse.randomringtone.data.YouTubeOnDevice.downloadAudio(videoId, destDir, prefix = "youtube_mp3_",
            baseName = title, displayTitle = title, forceOverwrite = force, onProgress = onProgress)
        if (r.success || r.fileExists) return Y2MateClient.DownloadResult(r.success, r.file, r.title, r.error, r.fileExists, r.videoId)
        RemoteLogger.w("YouTube", "Op toestel mislukt — terugval Y2Mate", mapOf("videoId" to videoId, "error" to (r.error ?: "")))
        onProgress("Op toestel mislukt — via Y2Mate...", 0f)
        val y = y2mate.downloadTrack(videoId = videoId, destDir = destDir, videoTitle = title, onProgress = onProgress, forceOverwrite = force)
        return if (y.success || y.fileExists) y else y.copy(error = "Op toestel: ${r.error}\nY2Mate: ${y.error}")
    }
    return y2mate.downloadTrack(videoId = videoId, destDir = destDir, videoTitle = title, onProgress = onProgress, forceOverwrite = force)
}
