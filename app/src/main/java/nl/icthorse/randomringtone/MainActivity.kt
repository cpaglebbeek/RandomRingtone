package nl.icthorse.randomringtone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import nl.icthorse.randomringtone.data.AppRingtoneManager
import nl.icthorse.randomringtone.data.BackupManager
import nl.icthorse.randomringtone.data.RingtoneDatabase
import nl.icthorse.randomringtone.ui.screens.*
import java.io.File
import nl.icthorse.randomringtone.ui.theme.RandomRingtoneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    // Auto-restore bij startup als DB leeg is + auto-backup bestaat
    LaunchedEffect(Unit) {
        val restored = backupManager.autoRestoreFromLocal(db, ringtoneManager.storage)
        if (restored) {
            snackbarHostState.showSnackbar("Data hersteld vanuit lokale backup")
        }
    }

    // Auto-backup bij elke tab-wissel (debounced, lightweight)
    LaunchedEffect(selectedTab) {
        backupManager.autoBackupToLocal(db, ringtoneManager.storage)
    }

    val tabs = listOf(
        Triple("spotify", "Spotify", Icons.Default.CloudDownload),
        Triple("youtube", "YouTube", Icons.Default.OndemandVideo),
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
