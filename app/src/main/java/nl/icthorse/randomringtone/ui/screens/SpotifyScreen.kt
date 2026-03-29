package nl.icthorse.randomringtone.ui.screens

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.*
import android.widget.Toast
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

/**
 * Spotify-naar-MP3 scherm.
 * Laadt de gekozen converter-site in een WebView.
 * Intercepteert MP3 downloads via DownloadListener.
 * Na download: opslaan in app download-dir, snackbar met acties.
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
    var converterUrl by remember { mutableStateOf("") }
    var converterName by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf("") }
    var lastDownloadedFile by remember { mutableStateOf<File?>(null) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var existingPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    // Laad converter URL uit instellingen
    LaunchedEffect(Unit) {
        val converterId = ringtoneManager.storage.getSpotifyConverter()
        val converter = SpotifyConverter.findById(converterId)
        converterUrl = converter.url
        converterName = converter.name
        existingPlaylists = db.playlistDao().getAll()
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
                                lastDownloadedFile = file
                                scope.launch {
                                    // Kopieer naar app download-dir
                                    val destFile = File(
                                        ringtoneManager.storage.getDownloadDir(),
                                        "spotify_${System.currentTimeMillis()}.mp3"
                                    )
                                    file.copyTo(destFile, overwrite = true)
                                    lastDownloadedFile = destFile

                                    snackbarHostState.showSnackbar(
                                        message = "MP3 gedownload: ${file.name}",
                                        actionLabel = "Acties",
                                        duration = SnackbarDuration.Long
                                    ).let { result ->
                                        if (result == SnackbarResult.ActionPerformed) {
                                            showPlaylistDialog = true
                                        }
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

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

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
            IconButton(
                onClick = { webView?.goForward() }
            ) {
                Icon(Icons.Default.ArrowForward, contentDescription = "Vooruit")
            }
            IconButton(
                onClick = { webView?.reload() }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Vernieuwen")
            }

            Text(
                text = converterName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        // URL balk
        Text(
            text = currentUrl,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        )

        Spacer(modifier = Modifier.height(2.dp))

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // WebView
        if (converterUrl.isNotBlank()) {
            SpotifyWebView(
                url = converterUrl,
                onWebViewCreated = { webView = it },
                onPageStarted = { url ->
                    isLoading = true
                    currentUrl = url
                },
                onPageFinished = { url ->
                    isLoading = false
                    currentUrl = url
                    canGoBack = webView?.canGoBack() == true
                },
                onDownloadStart = { url, userAgent, contentDisposition, mimeType, contentLength ->
                    // Start download via DownloadManager
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
    }

    // Playlist / acties dialoog na download
    if (showPlaylistDialog && lastDownloadedFile != null) {
        val file = lastDownloadedFile!!
        AlertDialog(
            onDismissRequest = { showPlaylistDialog = false },
            title = { Text("MP3 gedownload") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Wat wil je doen?",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Editor
                    if (onOpenEditor != null) {
                        Button(
                            onClick = {
                                showPlaylistDialog = false
                                onOpenEditor(file.nameWithoutExtension, file)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Openen in editor")
                        }
                    }
                    // Toevoegen aan playlist
                    Button(
                        onClick = {
                            showPlaylistDialog = false
                            scope.launch {
                                val trackId = file.name.hashCode().toLong()
                                db.savedTrackDao().insert(
                                    SavedTrack(
                                        deezerTrackId = trackId,
                                        title = file.nameWithoutExtension,
                                        artist = "Spotify",
                                        previewUrl = "",
                                        localPath = file.absolutePath,
                                        playlistName = "_spotify"
                                    )
                                )
                                existingPlaylists = db.playlistDao().getAll()
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
                TextButton(onClick = { showPlaylistDialog = false }) {
                    Text("Sluiten")
                }
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpotifyWebView(
    url: String,
    onWebViewCreated: (WebView) -> Unit,
    onPageStarted: (String) -> Unit,
    onPageFinished: (String) -> Unit,
    onDownloadStart: (url: String, userAgent: String, contentDisposition: String, mimeType: String, contentLength: Long) -> Unit
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

                // Cookie support voor converter-sites
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
                        // Externe app-links (play store etc.) niet in WebView openen
                        if (requestUrl.startsWith("intent://") || requestUrl.startsWith("market://")) {
                            return true
                        }
                        return false
                    }
                }

                webChromeClient = WebChromeClient()

                // Download interceptie
                setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, contentLength ->
                    onDownloadStart(downloadUrl, userAgent, contentDisposition, mimeType, contentLength)
                }

                onWebViewCreated(this)
                loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
