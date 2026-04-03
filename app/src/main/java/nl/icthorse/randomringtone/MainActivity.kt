package nl.icthorse.randomringtone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import nl.icthorse.randomringtone.data.AppRingtoneManager
import nl.icthorse.randomringtone.data.BackupManager
import nl.icthorse.randomringtone.data.LicenseManager
import nl.icthorse.randomringtone.data.RemoteLogger
import nl.icthorse.randomringtone.data.RingtoneDatabase
import nl.icthorse.randomringtone.ui.screens.*
import java.io.File
import nl.icthorse.randomringtone.ui.theme.RandomRingtoneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RemoteLogger.init(this)
        RemoteLogger.trigger("MainActivity", "onCreate — app start")
        enableEdgeToEdge()
        setContent {
            RandomRingtoneTheme {
                RandomRingtoneApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RandomRingtoneApp() {
    val navController = rememberNavController()
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val ringtoneManager = remember { AppRingtoneManager(context) }
    val db = remember { RingtoneDatabase.getInstance(context) }
    val backupManager = remember { BackupManager(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    // License check bij startup
    val licenseManager = remember { LicenseManager(context) }
    var licenseStatus by remember { mutableStateOf(licenseManager.getCachedStatus()) }
    var licenseChecked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        licenseStatus = licenseManager.checkLicense()
        licenseChecked = true
        if (licenseStatus.isGracePeriod) {
            snackbarHostState.showSnackbar("Grace period: ${licenseStatus.graceHoursLeft} uur resterend")
        }
    }

    // Blocking screen als niet gelicenseerd
    if (licenseChecked && !licenseStatus.active) {
        LicenseBlockScreen(licenseStatus, licenseManager)
        return
    }

    // Auto-restore bij startup als DB leeg is + auto-backup bestaat
    LaunchedEffect(Unit) {
        RemoteLogger.i("Startup", "Auto-restore check gestart")
        val restored = backupManager.autoRestoreFromLocal(db, ringtoneManager.storage)
        if (restored) {
            RemoteLogger.output("Startup", "Auto-restore uitgevoerd vanuit lokale backup")
            snackbarHostState.showSnackbar("Data hersteld vanuit lokale backup")
        } else {
            RemoteLogger.d("Startup", "Auto-restore overgeslagen (DB niet leeg of geen backup)")
        }
    }

    // Auto-backup bij elke tab-wissel (debounced, lightweight)
    LaunchedEffect(selectedTab) {
        RemoteLogger.d("Navigation", "Tab wissel → auto-backup", mapOf("tabIndex" to selectedTab.toString()))
        backupManager.autoBackupToLocal(db, ringtoneManager.storage)
    }

    val tabs = listOf(
        Triple("spotify", "Spotify", Icons.Default.CloudDownload),
        Triple("youtube", "YouTube", Icons.Default.VideoLibrary),
        Triple("library", "Bibliotheek", Icons.Default.LibraryMusic),
        Triple("playlists", "Playlists", Icons.Default.QueueMusic),
        Triple("overview", "Overzicht", Icons.Default.Dashboard),
        Triple("backup", "Backup", Icons.Default.Cloud),
        Triple("settings", "Instellingen", Icons.Default.Settings),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RandomRingtone v${BuildConfig.VERSION_NAME}") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, (route, label, icon) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        selected = selectedTab == index,
                        onClick = {
                            RemoteLogger.trigger("Navigation", "Tab tapped: $label (index=$index)")
                            selectedTab = index
                            // Pop editor van back stack als die er op zit
                            navController.popBackStack("editor", inclusive = true)
                            navController.navigate(route) {
                                popUpTo("spotify") { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "spotify",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable("spotify") {
                SpotifyScreen(
                    ringtoneManager = ringtoneManager,
                    db = db,
                    snackbarHostState = snackbarHostState,
                    onOpenEditor = { title, file ->
                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            set("editorTrackTitle", title)
                            set("editorTrackArtist", "Spotify")
                            set("editorFilePath", file.absolutePath)
                            set("editorTrackId", file.name.hashCode().toLong())
                            set("editorPreviewUrl", "")
                        }
                        navController.navigate("editor")
                    }
                )
            }
            composable("youtube") {
                YouTubeScreen(
                    ringtoneManager = ringtoneManager,
                    db = db,
                    snackbarHostState = snackbarHostState,
                    onOpenEditor = { title, file ->
                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            set("editorTrackTitle", title)
                            set("editorTrackArtist", "YouTube")
                            set("editorFilePath", file.absolutePath)
                            set("editorTrackId", file.name.hashCode().toLong())
                            set("editorPreviewUrl", "")
                        }
                        navController.navigate("editor")
                    }
                )
            }
            composable("editor") {
                val prevEntry = navController.previousBackStackEntry
                val title = prevEntry?.savedStateHandle?.get<String>("editorTrackTitle") ?: ""
                val artist = prevEntry?.savedStateHandle?.get<String>("editorTrackArtist") ?: ""
                val filePath = prevEntry?.savedStateHandle?.get<String>("editorFilePath") ?: ""
                val trackId = prevEntry?.savedStateHandle?.get<Long>("editorTrackId") ?: 0L
                val previewUrl = prevEntry?.savedStateHandle?.get<String>("editorPreviewUrl") ?: ""

                if (filePath.isNotBlank()) {
                    EditorScreen(
                        trackTitle = title,
                        trackArtist = artist,
                        audioFile = File(filePath),
                        deezerTrackId = trackId,
                        previewUrl = previewUrl,
                        db = db,
                        ringtoneManager = ringtoneManager,
                        snackbarHostState = snackbarHostState,
                        onDone = { navController.popBackStack() }
                    )
                }
            }
            composable("library") {
                LibraryScreen(
                    db = db,
                    ringtoneManager = ringtoneManager,
                    snackbarHostState = snackbarHostState,
                    onOpenEditor = { title, artist, file, trackId, previewUrl ->
                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            set("editorTrackTitle", title)
                            set("editorTrackArtist", artist)
                            set("editorFilePath", file.absolutePath)
                            set("editorTrackId", trackId)
                            set("editorPreviewUrl", previewUrl)
                        }
                        navController.navigate("editor")
                    }
                )
            }
            composable("playlists") {
                PlaylistManagerScreen(
                    db = db,
                    snackbarHostState = snackbarHostState
                )
            }
            composable("overview") {
                OverviewScreen(
                    db = db,
                    snackbarHostState = snackbarHostState
                )
            }
            composable("backup") {
                BackupScreen(
                    ringtoneManager = ringtoneManager,
                    db = db,
                    snackbarHostState = snackbarHostState
                )
            }
            composable("settings") {
                SettingsScreen(
                    ringtoneManager = ringtoneManager,
                    db = db,
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }
}

@Composable
private fun LicenseBlockScreen(
    status: LicenseManager.LicenseStatus,
    licenseManager: LicenseManager
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(Icons.Default.Lock, contentDescription = null,
                modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
            Text("Geen geldige licentie", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.error)
            Text(status.message.ifBlank { "Deze app vereist een geldige licentie." },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Device ID:", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(licenseManager.deviceHash, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                    if (status.error != null) {
                        Text("Fout: ${status.error}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text("Neem contact op met iCt Horse voor een licentie.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
