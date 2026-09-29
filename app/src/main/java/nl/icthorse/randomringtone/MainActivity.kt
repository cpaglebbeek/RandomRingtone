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
import nl.icthorse.randomringtone.data.RemoteVersion
import nl.icthorse.randomringtone.data.RingtoneDatabase
import nl.icthorse.randomringtone.data.UpdateManager
import nl.icthorse.randomringtone.ui.screens.*
import java.io.File
import nl.icthorse.randomringtone.ui.theme.RandomRingtoneTheme

/** Gedeelde staat: voorkomt tab-wisseling tijdens actieve processen */
object AppBusyState {
    var isBusy by androidx.compose.runtime.mutableStateOf(false)
}

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
    var pendingUpdate by remember { mutableStateOf<RemoteVersion?>(null) }

    LaunchedEffect(Unit) {
        licenseStatus = licenseManager.checkLicense()
        licenseChecked = true
        if (licenseStatus.isGracePeriod) {
            snackbarHostState.showSnackbar("Grace period: ${licenseStatus.graceHoursLeft} uur resterend")
        }
    }

    // Blocking screen als niet gelicenseerd
    if (licenseChecked && !licenseStatus.active) {
        LicenseActivationScreen(licenseStatus, licenseManager, onRecheck = { licenseStatus = licenseManager.checkLicense() })
        return
    }

    // v2.1.0: rechten + mappen controleren bij opstarten, vóór restore en vóór de Bibliotheek
    val setupGate = nl.icthorse.randomringtone.ui.screens.rememberSetupGate(ringtoneManager.storage, db)
    val openStorageSettings: () -> Unit = {
        selectedTab = 6
        navController.navigate("settings") {
            popUpTo("spotify") { inclusive = false }
            launchSingleTop = true
        }
    }
    nl.icthorse.randomringtone.ui.screens.SetupGateDialog(setupGate, ringtoneManager.storage, openStorageSettings)
    LaunchedEffect(licenseChecked && licenseStatus.active) {
        if (licenseChecked && licenseStatus.active) setupGate.check(nl.icthorse.randomringtone.data.CheckScope.STARTUP)
    }

    // v2.0.0: toestel-token (backup/restore + Spotify-bron) en aanbod om een backup van een ander toestel terug te zetten
    RestoreOfferHost(licenseManager, db, ringtoneManager, snackbarHostState, licenseChecked && licenseStatus.active, setupGate)

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

        // TrackId-migratie v8 (R11) — eenmalig na auto-restore
        val migrationResult = nl.icthorse.randomringtone.data.TrackIdMigration
            .migrateV8IfNeeded(context, db, ringtoneManager.storage)
        if (migrationResult.ran) {
            RemoteLogger.output("Startup", "TrackId-migratie v8: ${migrationResult.message}")
            if (migrationResult.remapped > 0 || migrationResult.merged > 0) {
                snackbarHostState.showSnackbar(migrationResult.message)
            }
        }
    }

    // Auto-backup bij elke tab-wissel (debounced, lightweight)
    LaunchedEffect(selectedTab) {
        RemoteLogger.d("Navigation", "Tab wissel → auto-backup", mapOf("tabIndex" to selectedTab.toString()))
        backupManager.autoBackupToLocal(db, ringtoneManager.storage)
    }

    // Debug mode init + auto-update check (1x per 24 uur)
    LaunchedEffect(Unit) {
        RemoteLogger.enabled = ringtoneManager.storage.isDebugLoggingEnabled()
        val debugBuild = ringtoneManager.storage.isDebugBuildEnabled()

        if (!debugBuild) {
            val lastCheck = ringtoneManager.storage.getLastUpdateCheck()
            val now = System.currentTimeMillis()
            if (now - lastCheck > 24 * 60 * 60 * 1000) {
                val updateMgr = UpdateManager(context)
                val versions = updateMgr.fetchVersions()
                val best = updateMgr.getBestUpdate(versions, BuildConfig.BUILD_NUMBER)
                if (best != null) {
                    pendingUpdate = best
                }
                ringtoneManager.storage.setLastUpdateCheck(now)
            }
        }
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
                        enabled = !AppBusyState.isBusy,
                        onClick = {
                            RemoteLogger.trigger("Navigation", "Tab tapped: $label (index=$index)")
                            val go = {
                                selectedTab = index
                                // Pop editor van back stack als die er op zit
                                navController.popBackStack("editor", inclusive = true)
                                navController.navigate(route) {
                                    popUpTo("spotify") { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                            // Bibliotheek: eerst rechten/mappen/ontbrekende bestanden controleren
                            if (route == "library" && selectedTab != index) {
                                setupGate.check(nl.icthorse.randomringtone.data.CheckScope.LIBRARY) { go() }
                            } else go()
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
                    snackbarHostState = snackbarHostState,
                    onOpenStorageSettings = openStorageSettings
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

    // Auto-update notificatie dialog
    if (pendingUpdate != null) {
        val update = pendingUpdate!!
        AlertDialog(
            onDismissRequest = { pendingUpdate = null },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text("Update beschikbaar") },
            text = {
                Text("v${update.version} (Build ${update.build}) is beschikbaar.\nWil je naar Instellingen gaan om te updaten?")
            },
            confirmButton = {
                Button(onClick = {
                    pendingUpdate = null
                    selectedTab = 6
                    navController.navigate("settings") {
                        popUpTo("spotify") { inclusive = false }
                        launchSingleTop = true
                    }
                }) { Text("Naar updates") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUpdate = null }) { Text("Later") }
            }
        )
    }
}
