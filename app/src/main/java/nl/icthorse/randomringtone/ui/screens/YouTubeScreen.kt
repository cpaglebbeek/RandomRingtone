package nl.icthorse.randomringtone.ui.screens

import android.annotation.SuppressLint
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

private const val YOUTUBE_URL = "https://m.youtube.com"
private val YOUTUBE_WATCH_REGEX = Regex("""youtube\.com/watch\?v=([a-zA-Z0-9_\-]{11})""")
private val YOUTUBE_SHORTS_REGEX = Regex("""youtube\.com/shorts/([a-zA-Z0-9_\-]{11})""")

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

    // URL monitoring voor video detectie
    LaunchedEffect(currentUrl) {
        val watchMatch = YOUTUBE_WATCH_REGEX.find(currentUrl)
        val shortsMatch = YOUTUBE_SHORTS_REGEX.find(currentUrl)
        val match = watchMatch ?: shortsMatch
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
                    isDownloading = true
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
                        if (result.fileExists && result.file != null) {
                            pendingOverwriteVideoId = videoId
                            pendingOverwriteFile = result.file
                            pendingOverwriteTitle = detectedVideoTitle.ifBlank { result.title }
                            showOverwriteDialog = true
                        } else if (result.success && result.file != null) {
                            lastDownloadedFile = result.file
                            detectedVideoTitle = result.title ?: detectedVideoTitle
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
                                db.savedTrackDao().insert(
                                    SavedTrack(
                                        deezerTrackId = trackId,
                                        title = detectedVideoTitle.ifBlank { file.nameWithoutExtension },
                                        artist = "YouTube",
                                        previewUrl = "",
                                        localPath = file.absolutePath,
                                        playlistName = "_youtube"
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

@SuppressLint("SetJavaScriptEnabled")
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
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportMultipleWindows(false)
                settings.mediaPlaybackRequiresUserGesture = true

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
                loadUrl(YOUTUBE_URL)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
