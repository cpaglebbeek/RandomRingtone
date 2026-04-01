package nl.icthorse.randomringtone.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
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

private const val YOUTUBE_MUSIC_URL = "https://music.youtube.com"
private val YOUTUBE_VIDEO_REGEX = Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/|music\\.youtube\\.com/watch\\?v=)([a-zA-Z0-9_\\-]{11})")

/**
 * YouTube/YouTube Music → MP3 scherm.
 *
 * Flow: browse YouTube Music → detecteer video URL → FAB → Cobalt download → acties
 */
@Composable
fun YouTubeScreen(
    ringtoneManager: AppRingtoneManager,
    db: RingtoneDatabase,
    snackbarHostState: SnackbarHostState,
    onOpenEditor: ((String, File) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Cobalt client
    val cobaltClient = remember { CobaltClient() }

    // WebView state
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf("") }

    // Track detectie
    var detectedVideoUrl by remember { mutableStateOf<String?>(null) }
    var detectedTitle by remember { mutableStateOf("") }
    var processedUrls by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Download state
    var isDownloading by remember { mutableStateOf(false) }
    var downloadPhase by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var lastDownloadedFile by remember { mutableStateOf<File?>(null) }
    var showActionsDialog by remember { mutableStateOf(false) }

    // Overwrite state
    var showOverwriteDialog by remember { mutableStateOf(false) }
    var pendingOverwriteUrl by remember { mutableStateOf<String?>(null) }
    var pendingOverwriteFile by remember { mutableStateOf<File?>(null) }

    // Clipboard monitoring
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    // Detecteer YouTube URL uit navigatie
    LaunchedEffect(currentUrl) {
        val match = YOUTUBE_VIDEO_REGEX.find(currentUrl)
        if (match != null) {
            val cleanUrl = extractCleanYouTubeUrl(currentUrl)
            if (cleanUrl !in processedUrls) {
                detectedVideoUrl = cleanUrl
            }
        } else {
            detectedVideoUrl = null
        }
    }

    // Clipboard LIVE monitoren
    DisposableEffect(Unit) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val clipText = clip.getItemAt(0).text?.toString() ?: ""
                    val clipMatch = YOUTUBE_VIDEO_REGEX.find(clipText)
                    if (clipMatch != null) {
                        val clipUrl = extractCleanYouTubeUrl(clipText)
                        if (clipUrl !in processedUrls) {
                            detectedVideoUrl = clipUrl
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
        onDispose { clipboardManager.removePrimaryClipChangedListener(listener) }
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
                        Icon(Icons.Default.OndemandVideo, contentDescription = null, modifier = Modifier.size(16.dp))
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

            // YouTube Music WebView
            YouTubeWebView(
                url = YOUTUBE_MUSIC_URL,
                onWebViewCreated = { webView = it },
                onPageStarted = { url -> isLoading = true; currentUrl = url },
                onPageFinished = { url ->
                    isLoading = false; currentUrl = url
                    canGoBack = webView?.canGoBack() == true
                }
            )
        }

        // FAB: "Download MP3"
        AnimatedVisibility(
            visible = detectedVideoUrl != null && !isDownloading,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = {
                    val videoUrl = detectedVideoUrl ?: return@ExtendedFloatingActionButton

                    // Haal titel uit pagina
                    val pageTitle = webView?.title ?: ""
                    detectedTitle = pageTitle
                        .replace(" - YouTube Music", "")
                        .replace(" - YouTube", "")
                        .trim()

                    // Markeer als verwerkt
                    processedUrls = processedUrls + videoUrl
                    detectedVideoUrl = null

                    // Start download via Cobalt
                    isDownloading = true
                    scope.launch {
                        val result = cobaltClient.downloadAudio(
                            youtubeUrl = videoUrl,
                            destDir = ringtoneManager.storage.getDownloadDir(),
                            onProgress = { phase, progress ->
                                downloadPhase = phase
                                downloadProgress = progress
                            }
                        )
                        isDownloading = false
                        if (result.fileExists && result.file != null) {
                            pendingOverwriteUrl = videoUrl
                            pendingOverwriteFile = result.file
                            showOverwriteDialog = true
                        } else if (result.success && result.file != null) {
                            lastDownloadedFile = result.file
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
                containerColor = MaterialTheme.colorScheme.primary
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
                            text = downloadPhase,
                            style = MaterialTheme.typography.bodyMedium
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
    if (showOverwriteDialog && pendingOverwriteFile != null) {
        AlertDialog(
            onDismissRequest = {
                showOverwriteDialog = false
                pendingOverwriteUrl = null
                pendingOverwriteFile = null
            },
            title = { Text("Bestand bestaat al") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pendingOverwriteFile?.name ?: "", style = MaterialTheme.typography.bodyMedium)
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
                    val url = pendingOverwriteUrl ?: return@Button
                    pendingOverwriteUrl = null
                    pendingOverwriteFile = null
                    isDownloading = true
                    scope.launch {
                        val result = cobaltClient.downloadAudio(
                            youtubeUrl = url,
                            destDir = ringtoneManager.storage.getDownloadDir(),
                            onProgress = { phase, progress ->
                                downloadPhase = phase
                                downloadProgress = progress
                            },
                            forceOverwrite = true
                        )
                        isDownloading = false
                        if (result.success && result.file != null) {
                            lastDownloadedFile = result.file
                            showActionsDialog = true
                        } else {
                            snackbarHostState.showSnackbar(result.error ?: "Download mislukt")
                        }
                    }
                }) { Text("Overschrijven") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOverwriteDialog = false
                    pendingOverwriteUrl = null
                    pendingOverwriteFile = null
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
                                val trackId = file.name.hashCode().toLong().let { if (it < 0) -it else it }
                                db.savedTrackDao().insert(
                                    SavedTrack(
                                        deezerTrackId = trackId,
                                        title = detectedTitle.ifBlank { file.nameWithoutExtension },
                                        artist = "YouTube",
                                        previewUrl = "",
                                        localPath = file.absolutePath,
                                        playlistName = "_youtube"
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

// === HELPERS ===

private fun extractCleanYouTubeUrl(url: String): String {
    val match = YOUTUBE_VIDEO_REGEX.find(url)
    return if (match != null) {
        "https://www.youtube.com/watch?v=${match.groupValues[1]}"
    } else {
        url.split("&").first()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeWebView(
    url: String,
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
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportMultipleWindows(false)
                settings.mediaPlaybackRequiresUserGesture = false

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
